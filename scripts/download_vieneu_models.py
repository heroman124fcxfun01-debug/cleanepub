#!/usr/bin/env python3
"""
Download & prepare ONLY the essential PUBLIC (non-gated) `pnnbao97/VieNeu-TTS` offline runtime
components into `app/src/main/assets/models/`:

  1. `*.gguf`                     — Compact Q4_0 language backbone (auto-discovered via HfApi.list_repo_files)
  2. `neucodec_decoder.onnx`      — Verified 24kHz NeuCodec FSQ decoder (+ companion `.data` if required)
  3. `voices.json`                — Compact preset reference bank (~15 KB)
  4. `neucodec_encoder.onnx`      — Best-effort opset-17 16kHz NeuCodec ONNX encoder (downloaded from
                                    public HF if available, or exported via PyTorch CPU + onnx/onnxscript
                                    and safely quantized; wrapped in try-except so any CI incompatibility
                                    logs a clear warning WITHOUT failing the APK build!)

Never hardcodes Hugging Face filenames (uses `HfApi().list_repo_files(...)` to avoid 404 errors),
purges all old/legacy files before downloading, and prints the exact resulting files and sizes.

Usage:
  python3 scripts/download_vieneu_models.py <models_dir>
"""

import inspect
import os
import shutil
import sys
import tempfile
import traceback
from typing import Dict, List, Optional, Set, Tuple

import numpy as np
import onnxruntime as ort
from huggingface_hub import HfApi, hf_hub_download

ENCODER_SAMPLE_RATE = 16000
ENCODER_HOP_SIZE = 320


def assert_not_lfs_pointer(path: str) -> None:
    sz = os.path.getsize(path)
    if sz < 256:
        with open(path, "rb") as f:
            head = f.read(256)
        if b"version https://git-lfs.github.com/spec/v1" in head:
            raise RuntimeError(f"File {path} is a Git LFS pointer ({sz} bytes), not binary data!")


def list_public_repo_files(api: HfApi, repo_id: str) -> Optional[List[str]]:
    """
    Safely list actual files in a public non-gated Hugging Face repo via `list_repo_files`.
    Skips private, gated, or missing repos cleanly without crashing the build.
    """
    try:
        info = api.model_info(repo_id)
        if getattr(info, "private", False):
            print(f"  [skip] Repo {repo_id} is private", flush=True)
            return None
        if getattr(info, "gated", False):
            print(f"  [skip] Repo {repo_id} is gated (requires HF login)", flush=True)
            return None
    except Exception as exc:
        print(f"  [skip] Cannot inspect model_info for {repo_id}: {exc}", flush=True)
        return None

    try:
        files = list(api.list_repo_files(repo_id=repo_id))
    except Exception as exc:
        print(f"  [warn] list_repo_files failed for {repo_id} ({exc}); falling back to siblings", flush=True)
        siblings = getattr(info, "siblings", None) or []
        files = [s.rfilename for s in siblings if getattr(s, "rfilename", None)]

    if not files:
        print(f"  [skip] Repo {repo_id} returned 0 files", flush=True)
        return None

    print(f"  [public] {repo_id}: {len(files)} files", flush=True)
    return files


def download_file_safe(repo_id: str, filename: str, dest_path: str) -> bool:
    try:
        cached = hf_hub_download(repo_id=repo_id, filename=filename)
        assert_not_lfs_pointer(cached)
        os.makedirs(os.path.dirname(os.path.abspath(dest_path)), exist_ok=True)
        shutil.copy2(cached, dest_path)
        size_mb = os.path.getsize(dest_path) / (1024.0 * 1024.0)
        print(
            f"    -> saved {os.path.basename(dest_path)} ({size_mb:.2f} MB) from {repo_id}/{filename}",
            flush=True,
        )
        return True
    except Exception as exc:
        print(f"  [warn] Download failed for {repo_id}/{filename}: {exc}", flush=True)
        if os.path.exists(dest_path):
            try:
                os.remove(dest_path)
            except OSError:
                pass
        return False


def _inspect_onnx_referenced_external_files(onnx_path: str) -> Set[str]:
    """Read exact external-data location names from ONNX protobuf if `onnx` package is available."""
    refs: Set[str] = set()
    try:
        import onnx

        model = onnx.load(onnx_path, load_external_data=False)
        for init in model.graph.initializer:
            if init.data_location == onnx.TensorProto.EXTERNAL:
                for kv in init.external_data:
                    if kv.key == "location" and kv.value:
                        refs.add(os.path.basename(kv.value))
    except Exception:
        pass
    return refs


def find_external_data_files(
    chosen_onnx: str,
    all_files: List[str],
    referenced_names: Optional[Set[str]] = None,
) -> List[str]:
    chosen_dir = os.path.dirname(chosen_onnx)
    chosen_base = os.path.basename(chosen_onnx)
    chosen_stem = os.path.splitext(chosen_base)[0]
    same_dir_onnx = [
        f for f in all_files
        if os.path.dirname(f) == chosen_dir and f.lower().endswith(".onnx")
    ]
    ref_lower = {r.lower() for r in (referenced_names or set())}

    companions: List[str] = []
    for f in all_files:
        if f == chosen_onnx:
            continue
        bn = os.path.basename(f)
        low = bn.lower()
        if ref_lower and low in ref_lower:
            companions.append(f)
            continue
        if os.path.dirname(f) != chosen_dir:
            continue
        is_ext_data = (
            low.endswith(".onnx.data")
            or low.endswith(".onnx_data")
            or low.endswith(".data")
        )
        if not is_ext_data:
            continue
        if bn.startswith(chosen_base) or bn.startswith(chosen_stem) or len(same_dir_onnx) == 1:
            companions.append(f)
    return companions


def cleanup_paths(paths: List[str]) -> None:
    for p in paths:
        if os.path.exists(p):
            try:
                os.remove(p)
            except OSError:
                pass


def download_onnx_with_companions(
    repo_id: str,
    chosen_onnx: str,
    all_files: List[str],
    dest_onnx_path: str,
    models_dir: str,
) -> Optional[List[str]]:
    if not download_file_safe(repo_id, chosen_onnx, dest_onnx_path):
        return None

    downloaded: List[str] = [dest_onnx_path]
    referenced = _inspect_onnx_referenced_external_files(dest_onnx_path)
    companions = find_external_data_files(chosen_onnx, all_files, referenced_names=referenced)
    for extra in companions:
        extra_dest = os.path.join(models_dir, os.path.basename(extra))
        if not download_file_safe(repo_id, extra, extra_dest):
            cleanup_paths(downloaded)
            return None
        downloaded.append(extra_dest)
    return downloaded


def describe_io(nodes) -> List[Dict[str, object]]:
    return [{"name": n.name, "type": n.type, "shape": list(n.shape)} for n in nodes]


def _make_speech_like_signal_16k(duration_sec: float = 2.0) -> np.ndarray:
    """Synthetic voiced speech-like 16 kHz waveform (harmonic formants + syllable envelope)."""
    n = int(round(duration_sec * ENCODER_SAMPLE_RATE))
    rem = n % ENCODER_HOP_SIZE
    if rem:
        n += ENCODER_HOP_SIZE - rem
    t = np.arange(n, dtype=np.float32) / float(ENCODER_SAMPLE_RATE)
    f0 = 165.0 + 30.0 * np.sin(2.0 * np.pi * 1.3 * t)
    phase = 2.0 * np.pi * np.cumsum(f0) / float(ENCODER_SAMPLE_RATE)
    sig = (
        0.50 * np.sin(phase)
        + 0.30 * np.sin(2.0 * phase + 0.4)
        + 0.18 * np.sin(3.0 * phase + 0.9)
        + 0.10 * np.sin(5.0 * phase + 1.2)
    )
    env = 0.25 + 0.75 * np.maximum(0.0, np.sin(2.0 * np.pi * 2.2 * t))
    wav = (sig * env).astype(np.float32)
    peak = float(np.max(np.abs(wav)))
    if peak > 1e-6:
        wav = wav * (0.6 / peak)
    return wav


def decode_codes_with_session(dec_sess: ort.InferenceSession, codes: List[int]) -> np.ndarray:
    inp = dec_sess.get_inputs()[0]
    dtype = np.int64 if "int64" in inp.type.lower() else np.int32
    arr = np.asarray(codes, dtype=dtype)
    rank = len(inp.shape)
    if rank == 3:
        arr = arr.reshape(1, 1, -1)
    elif rank == 2:
        arr = arr.reshape(1, -1)
    elif rank == 1:
        arr = arr.reshape(-1)
    else:
        arr = arr.reshape(1, 1, -1)
    out = dec_sess.run(None, {inp.name: arr})
    return np.asarray(out[0], dtype=np.float32).reshape(-1)


def verify_neucodec_decoder_onnx(onnx_path: str) -> Optional[Dict[str, object]]:
    """
    Verify via ONNX Runtime that `onnx_path` is a genuine NeuCodec 24kHz FSQ decoder
    compatible with `pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf` speech tokens (`0..65535`).
    """
    try:
        sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
        inputs = sess.get_inputs()
        outputs = sess.get_outputs()
        in_desc = describe_io(inputs)
        out_desc = describe_io(outputs)
        print(f"    [verify decoder] inputs={in_desc} | outputs={out_desc}", flush=True)

        if len(inputs) != 1:
            print(
                f"    [reject decoder] Expected 1 FSQ token input (e.g. 'codes'), "
                f"found {len(inputs)} ({[i.name for i in inputs]}).",
                flush=True,
            )
            return None

        inp = inputs[0]
        if "int" not in inp.type.lower():
            print(f"    [reject decoder] Expected integer input tensor, got {inp.type}", flush=True)
            return None

        wav = decode_codes_with_session(sess, [6181, 6377, 55510, 53535])
        if wav.size < 1000 or not np.all(np.isfinite(wav)):
            print(f"    [reject decoder] Invalid dry-run output (samples={wav.size})", flush=True)
            return None

        print(
            f"    [ok decoder] Verified NeuCodec FSQ 24kHz decoder: "
            f"4 tokens -> {wav.size} samples (peak={float(np.max(np.abs(wav))):.4f})",
            flush=True,
        )
        return {"inputs": in_desc, "outputs": out_desc}
    except Exception as exc:
        print(f"    [reject decoder] ONNX Runtime verification failed: {exc}", flush=True)
        return None


def verify_neucodec_encoder_onnx(
    onnx_path: str,
    decoder_onnx_path: Optional[str] = None,
) -> Optional[Dict[str, object]]:
    """
    Verify via ONNX Runtime that `onnx_path` is a genuine 16kHz NeuCodec FSQ encoder
    that maps 16kHz Mono PCM -> 50Hz FSQ codes (0..65535) compatible with `neucodec_decoder.onnx`.
    """
    try:
        sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
        inputs = sess.get_inputs()
        outputs_meta = sess.get_outputs()
        in_desc = describe_io(inputs)
        out_desc = describe_io(outputs_meta)
        print(f"    [verify encoder] inputs={in_desc} | outputs={out_desc}", flush=True)

        wav_16k = _make_speech_like_signal_16k(duration_sec=2.0)
        feed: Dict[str, np.ndarray] = {}
        has_float_audio = False

        for inp in inputs:
            low = inp.name.lower()
            t_low = inp.type.lower()
            if "int" in t_low or "len" in low:
                dt = np.int64 if "int64" in t_low else np.int32
                feed[inp.name] = np.asarray([wav_16k.size], dtype=dt)
            elif "float" in t_low:
                has_float_audio = True
                rank = len(inp.shape)
                if rank == 3:
                    arr = wav_16k.reshape(1, 1, -1)
                elif rank == 2:
                    arr = wav_16k.reshape(1, -1)
                elif rank == 1:
                    arr = wav_16k.reshape(-1)
                else:
                    arr = wav_16k.reshape(1, 1, -1)
                feed[inp.name] = arr.astype(np.float16 if "float16" in t_low else np.float32)
            else:
                print(f"    [reject encoder] Unsupported input type {inp.name}: {inp.type}", flush=True)
                return None

        if not has_float_audio:
            print("    [reject encoder] Model has no float waveform input!", flush=True)
            return None

        outputs = sess.run(None, feed)
        chosen_codes: Optional[List[int]] = None

        # Pass 1: Look for explicit integer / named codes output
        for idx, raw_out in enumerate(outputs):
            arr = np.asarray(raw_out)
            o_type = outputs_meta[idx].type.lower()
            o_name = outputs_meta[idx].name.lower()
            if "int" in o_type or "code" in o_name or "token" in o_name or "idx" in o_name:
                flat = arr.reshape(-1)
                if flat.size >= 15 and np.all(np.isfinite(flat)):
                    ints = [int(round(float(v))) for v in flat.tolist()]
                    if all(0 <= c <= 65535 for c in ints):
                        chosen_codes = ints
                        break

        # Pass 2: Fallback to first output if it contains valid integer-like FSQ codes in 0..65535
        if chosen_codes is None and outputs:
            flat = np.asarray(outputs[0]).reshape(-1)
            if flat.size >= 15 and np.all(np.isfinite(flat)):
                rounded = np.rint(flat)
                if np.max(np.abs(flat - rounded)) < 1e-3:
                    ints = [int(v) for v in rounded.tolist()]
                    if all(0 <= c <= 65535 for c in ints):
                        chosen_codes = ints

        if chosen_codes is None:
            print(
                f"    [reject encoder] No valid 0..65535 FSQ codes output tensor found "
                f"(outputs={[(o.name, o.type, np.asarray(v).shape) for o, v in zip(outputs_meta, outputs)]})",
                flush=True,
            )
            return None

        unique_count = len(set(chosen_codes))
        if len(chosen_codes) < 30 or unique_count < 4:
            print(
                f"    [reject encoder] Degenerate codes output (len={len(chosen_codes)}, unique={unique_count})",
                flush=True,
            )
            return None

        if decoder_onnx_path and os.path.isfile(decoder_onnx_path):
            dec_sess = ort.InferenceSession(decoder_onnx_path, providers=["CPUExecutionProvider"])
            rt_wav = decode_codes_with_session(dec_sess, chosen_codes[:60])
            rt_peak = float(np.max(np.abs(rt_wav))) if rt_wav.size else 0.0
            if rt_wav.size < 4000 or not np.all(np.isfinite(rt_wav)) or rt_peak < 0.005:
                print(
                    f"    [reject encoder] Roundtrip decode failed (samples={rt_wav.size}, peak={rt_peak:.5f})",
                    flush=True,
                )
                return None
            print(
                f"    [ok encoder] Verified NeuCodec 16kHz FSQ encoder + roundtrip 24kHz decode: "
                f"2.0s -> {len(chosen_codes)} codes ({unique_count} unique) -> {rt_wav.size} samples (peak={rt_peak:.4f})",
                flush=True,
            )
        else:
            print(
                f"    [ok encoder] Verified NeuCodec 16kHz FSQ encoder: "
                f"2.0s -> {len(chosen_codes)} codes ({unique_count} unique)",
                flush=True,
            )
        return {"inputs": in_desc, "outputs": out_desc, "sample_codes": chosen_codes[:12]}
    except Exception as exc:
        print(f"    [reject encoder] ONNX Runtime verification failed: {exc}", flush=True)
        return None


def discover_public_repos(
    api: HfApi,
    author: Optional[str] = None,
    search: Optional[str] = None,
    limit: int = 30,
) -> List[str]:
    found: List[str] = []
    try:
        models = api.list_models(author=author, search=search, limit=limit)
        for m in models:
            if getattr(m, "gated", False) or getattr(m, "private", False):
                continue
            repo_id = getattr(m, "id", None)
            if repo_id and repo_id not in found:
                found.append(repo_id)
    except Exception as exc:
        print(f"  [warn] HF discovery failed (author={author}, search={search}): {exc}", flush=True)
    return found


def rank_decoder_onnx_candidates(files: List[str]) -> List[str]:
    """Return all non-encoder `.onnx` files in a repo sorted by preference (INT8 decoder first)."""
    onnx_files = [f for f in files if f.lower().endswith(".onnx")]
    candidates = [f for f in onnx_files if "enc" not in os.path.basename(f).lower()]

    def score(path: str) -> Tuple[int, int, int, str]:
        bn = os.path.basename(path).lower()
        has_dec = 0 if ("dec" in bn or bn == "model.onnx") else 1
        has_int8 = 0 if ("int8" in bn or "q8" in bn or "quant" in bn) else 1
        return (has_dec, has_int8, len(path), path)

    candidates.sort(key=score)
    return candidates


def rank_encoder_onnx_candidates(repo_id: str, files: List[str]) -> List[str]:
    """Return all candidate encoder `.onnx` files in a repo sorted by preference."""
    onnx_files = [f for f in files if f.lower().endswith(".onnx")]
    repo_low = repo_id.lower()
    candidates = []
    for f in onnx_files:
        bn = os.path.basename(f).lower()
        if "dec" in bn and "enc" not in bn:
            continue
        if "enc" in bn or "codec" in bn or "enc" in repo_low or "distill" in repo_low or bn == "model.onnx":
            candidates.append(f)

    def score(path: str) -> Tuple[int, int, int, int, str]:
        bn = os.path.basename(path).lower()
        has_enc = 0 if "enc" in bn else 1
        has_distill = 0 if ("distill" in bn or "distill" in repo_low) else 1
        has_quant = 0 if ("int8" in bn or "q8" in bn or "quant" in bn) else 1
        return (has_enc, has_distill, has_quant, len(path), path)

    candidates.sort(key=score)
    return candidates


def _total_encoder_size_bytes(encoder_onnx_path: str, companion_paths: List[str]) -> int:
    total = os.path.getsize(encoder_onnx_path) if os.path.isfile(encoder_onnx_path) else 0
    for p in companion_paths:
        if p != encoder_onnx_path and os.path.isfile(p):
            total += os.path.getsize(p)
    return total


def try_quantize_encoder_for_mobile(
    encoder_onnx_path: str,
    companion_paths: List[str],
    decoder_onnx_path: str,
) -> List[str]:
    """
    If `neucodec_encoder.onnx` (+ external `.data`) is large FP32 (> 115 MB), attempt safe dynamic
    QUInt8 quantization on transformer `MatMul`/`Gemm` layers into a single compact `.onnx` file
    (~65-95 MB) and verify via ONNX Runtime + roundtrip 24kHz decode. Falls back cleanly to the
    already-verified FP32 encoder if quantization fails.
    """
    orig_bytes = _total_encoder_size_bytes(encoder_onnx_path, companion_paths)
    orig_mb = orig_bytes / (1024.0 * 1024.0)
    if orig_mb <= 115.0:
        print(f"    [size] Encoder already compact ({orig_mb:.2f} MB) — skipping quantization.", flush=True)
        return companion_paths

    print(
        f"    [quantize] Encoder is {orig_mb:.2f} MB; attempting safe mobile QUInt8 quantization "
        f"(MatMul/Gemm) to shrink APK...",
        flush=True,
    )
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
    except Exception as exc:
        print(f"    [quantize] onnxruntime.quantization unavailable ({exc}); keeping FP32 encoder.", flush=True)
        return companion_paths

    with tempfile.TemporaryDirectory(prefix="neucodec_enc_q8_") as tmpdir:
        q8_path = os.path.join(tmpdir, "neucodec_encoder_q8.onnx")
        try:
            quantize_dynamic(
                model_input=encoder_onnx_path,
                model_output=q8_path,
                weight_type=QuantType.QUInt8,
                op_types_to_quantize=["MatMul", "Gemm"],
            )
        except Exception as exc:
            print(f"    [quantize] quantize_dynamic failed ({exc}); keeping verified FP32 encoder.", flush=True)
            return companion_paths

        if not os.path.isfile(q8_path):
            return companion_paths

        q8_mb = os.path.getsize(q8_path) / (1024.0 * 1024.0)
        print(f"    [quantize] Candidate QUInt8 encoder size: {q8_mb:.2f} MB (was {orig_mb:.2f} MB)", flush=True)
        if q8_mb >= orig_mb * 0.95:
            print("    [quantize] No meaningful size savings; keeping original encoder.", flush=True)
            return companion_paths

        if verify_neucodec_encoder_onnx(q8_path, decoder_onnx_path=decoder_onnx_path) is None:
            print("    [quantize] QUInt8 candidate failed verification; keeping verified FP32 encoder.", flush=True)
            return companion_paths

        shutil.copy2(q8_path, encoder_onnx_path)
        for p in companion_paths:
            if os.path.abspath(p) != os.path.abspath(encoder_onnx_path) and os.path.exists(p):
                try:
                    os.remove(p)
                    print(f"    [clean] Removed superseded external weights: {os.path.basename(p)}", flush=True)
                except OSError:
                    pass
        print(
            f"    [ok quantize] Compact single-file neucodec_encoder.onnx ready ({q8_mb:.2f} MB).",
            flush=True,
        )
        return [encoder_onnx_path]


def _build_pure_torch_neucodec_encoder_wrapper(codec):
    """
    Build a pure-PyTorch nn.Module wrapping NeuCodec / DistillNeuCodec's 16kHz -> FSQ codes
    encoder pipeline so `torch.onnx.export(..., opset_version=17)` avoids numpy feature extractors
    and PyTorch 2.x SDPA opset-17 export issues.
    """
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    # Force eager attention on HF semantic_model if supported (avoids opset 17 SDPA export failure)
    sem_model = getattr(codec, "semantic_model", None)
    if sem_model is not None:
        cfg = getattr(sem_model, "config", None)
        if cfg is not None:
            try:
                setattr(cfg, "_attn_implementation", "eager")
            except Exception:
                pass
            if hasattr(sem_model, "set_attn_implementation"):
                try:
                    sem_model.set_attn_implementation("eager")
                except Exception:
                    pass

    try:
        src = inspect.getsource(codec.encode_code)
        first_lines = " | ".join(line.strip() for line in src.splitlines()[:12] if line.strip())
        print(f"    [export] encode_code preview: {first_lines[:240]}", flush=True)
    except Exception:
        pass

    class PureTorchNeuCodecEncoder(nn.Module):
        def __init__(self, inner_codec):
            super().__init__()
            self.codec = inner_codec
            self.has_semantic_model = hasattr(inner_codec, "semantic_model")
            self.has_semantic_encoder = hasattr(inner_codec, "SemanticEncoder_module")
            self.has_fc_prior = hasattr(inner_codec, "fc_prior")
            self.has_generator = hasattr(inner_codec, "generator")
            self.has_codec_enc = hasattr(inner_codec, "codec_enc")

        def _extract_semantic_features(self, audio_b_t: torch.Tensor) -> torch.Tensor:
            mean = audio_b_t.mean(dim=-1, keepdim=True)
            var = audio_b_t.var(dim=-1, unbiased=False, keepdim=True)
            normed = (audio_b_t - mean) / torch.sqrt(var + 1e-7)

            out = self.codec.semantic_model(normed, output_hidden_states=True)
            hidden_states = getattr(out, "hidden_states", None)
            if hidden_states is not None:
                feat = hidden_states[16] if len(hidden_states) > 16 else hidden_states[-1]
            elif hasattr(out, "last_hidden_state"):
                feat = out.last_hidden_state
            else:
                feat = out[0]
            return feat

        def forward(self, audio_16k: torch.Tensor) -> torch.Tensor:
            if audio_16k.dim() == 3:
                wav_bt = audio_16k.squeeze(1)
                wav_b1t = audio_16k
            elif audio_16k.dim() == 2:
                wav_bt = audio_16k
                wav_b1t = audio_16k.unsqueeze(1)
            else:
                wav_bt = audio_16k.unsqueeze(0)
                wav_b1t = audio_16k.unsqueeze(0).unsqueeze(0)

            if self.has_semantic_model and self.has_semantic_encoder and self.has_fc_prior and self.has_generator:
                wav_sem = F.pad(wav_bt, (160, 160))
                sem_feat = self._extract_semantic_features(wav_sem)
                sem_feat_t = sem_feat.transpose(1, 2)
                x_sem = self.codec.SemanticEncoder_module(sem_feat_t)
                if self.has_codec_enc:
                    x_ac = self.codec.codec_enc(wav_b1t)
                    if x_ac.dim() == 3 and x_ac.shape[1] != x_sem.shape[1] and x_ac.shape[2] == x_sem.shape[1]:
                        x_ac = x_ac.transpose(1, 2)
                    min_t = x_sem.shape[-1] if x_sem.shape[-1] <= x_ac.shape[-1] else x_ac.shape[-1]
                    x = torch.cat([x_sem[..., :min_t], x_ac[..., :min_t]], dim=1)
                else:
                    x = x_sem

                if isinstance(self.codec.fc_prior, nn.Linear):
                    z = self.codec.fc_prior(x.transpose(1, 2)).transpose(1, 2)
                else:
                    z = self.codec.fc_prior(x)

                quantizer = getattr(self.codec.generator, "quantizer", None)
                if quantizer is None:
                    raise RuntimeError("codec.generator has no quantizer attribute")
                q_params = inspect.signature(quantizer.forward).parameters
                q_out = quantizer(z, vq=True) if "vq" in q_params else quantizer(z)
                if isinstance(q_out, (tuple, list)):
                    codes = None
                    for item in q_out:
                        if isinstance(item, torch.Tensor) and item.dtype in (torch.int64, torch.int32):
                            codes = item
                            break
                    if codes is None:
                        codes = q_out[1]
                else:
                    codes = q_out
                return codes.to(torch.int64)

            codes = self.codec.encode_code(wav_b1t)
            return codes.to(torch.int64)

    wrapper = PureTorchNeuCodecEncoder(codec)
    wrapper.eval()
    return wrapper


def export_neucodec_encoder_from_python(
    dest_onnx_path: str,
    decoder_onnx_path: str,
) -> Optional[List[str]]:
    """
    Best-effort fallback when no public pre-exported NeuCodec ONNX encoder exists on HF:
    Load `DistillNeuCodec` (preferred) or `NeuCodec` from `neucodec` and export its
    16kHz -> FSQ codes encoder graph to ONNX opset 17.
    """
    print("\n=== [3/3 Fallback] Attempting PyTorch ONNX opset-17 export of NeuCodec Encoder ===", flush=True)
    try:
        import torch
        import onnx  # noqa: F401
    except Exception as exc:
        print(f"  [warn export] Missing torch/onnx dependency: {exc}", flush=True)
        return None

    codec = None
    codec_name = ""
    try:
        from neucodec import DistillNeuCodec

        print("  [export] Loading DistillNeuCodec.from_pretrained('neuphonic/distill-neucodec')...", flush=True)
        codec = DistillNeuCodec.from_pretrained("neuphonic/distill-neucodec")
        codec_name = "DistillNeuCodec"
    except Exception as exc:
        print(f"  [warn export] DistillNeuCodec load failed ({exc}); trying NeuCodec...", flush=True)
        try:
            from neucodec import NeuCodec

            print("  [export] Loading NeuCodec.from_pretrained('neuphonic/neucodec')...", flush=True)
            codec = NeuCodec.from_pretrained("neuphonic/neucodec")
            codec_name = "NeuCodec"
        except Exception as exc2:
            print(f"  [warn export] NeuCodec load failed ({exc2})", flush=True)
            return None

    codec.eval()
    for p in codec.parameters():
        p.requires_grad_(False)

    dummy_np = _make_speech_like_signal_16k(duration_sec=2.0)
    dummy_input = torch.from_numpy(dummy_np).reshape(1, 1, -1)

    class DirectEncodeWrapper(torch.nn.Module):
        def __init__(self, inner):
            super().__init__()
            self.inner = inner

        def forward(self, audio_16k: torch.Tensor) -> torch.Tensor:
            return self.inner.encode_code(audio_16k).to(torch.int64)

    candidates = [
        (f"{codec_name} (pure-torch)", _build_pure_torch_neucodec_encoder_wrapper(codec)),
        (f"{codec_name} (direct encode_code)", DirectEncodeWrapper(codec)),
    ]

    models_dir = os.path.dirname(os.path.abspath(dest_onnx_path))
    for label, module in candidates:
        before_files = set(os.listdir(models_dir))
        print(f"  [export] Attempting ONNX opset 17 export with {label}...", flush=True)
        try:
            with torch.no_grad():
                test_out = module(dummy_input)
                print(
                    f"    [export] PyTorch forward OK: shape={tuple(test_out.shape)}, dtype={test_out.dtype}, "
                    f"range={int(test_out.min().item())}..{int(test_out.max().item())}",
                    flush=True,
                )

            export_kwargs = dict(
                f=dest_onnx_path,
                export_params=True,
                opset_version=17,
                do_constant_folding=True,
                input_names=["audio"],
                output_names=["codes"],
                dynamic_axes={
                    "audio": {2: "num_samples"},
                    "codes": {2: "num_codes"},
                },
            )
            try:
                torch.onnx.export(module, (dummy_input,), dynamo=False, **export_kwargs)
            except TypeError:
                torch.onnx.export(module, (dummy_input,), **export_kwargs)

            after_files = set(os.listdir(models_dir))
            created = [os.path.join(models_dir, fn) for fn in sorted(after_files - before_files)]
            if dest_onnx_path not in created and os.path.isfile(dest_onnx_path):
                created.insert(0, dest_onnx_path)

            if verify_neucodec_encoder_onnx(dest_onnx_path, decoder_onnx_path=decoder_onnx_path) is not None:
                print(f"  [ok export] Successfully exported & verified {label} -> {dest_onnx_path}", flush=True)
                return created

            cleanup_paths(created)
        except Exception as exc:
            print(f"  [warn export] {label} failed: {exc}", flush=True)
            after_files = set(os.listdir(models_dir))
            cleanup_paths([os.path.join(models_dir, fn) for fn in (after_files - before_files)])

    return None


def purge_assets_junk(models_dir: str) -> None:
    """Clean out `models_dir` and delete any legacy/unused model folders or junk files in parent `assets/`."""
    if os.path.exists(models_dir):
        shutil.rmtree(models_dir)
    os.makedirs(models_dir, exist_ok=True)

    assets_dir = os.path.dirname(os.path.abspath(models_dir))
    legacy_names = (
        "VieNeuV3Turbo",
        "MOSS-Audio-Tokenizer-Nano",
        "sea_g2p.bin",
        "distill_neucodec_encoder.onnx",
    )
    if os.path.isdir(assets_dir):
        for entry in os.listdir(assets_dir):
            if entry == os.path.basename(os.path.abspath(models_dir)):
                continue
            full = os.path.join(assets_dir, entry)
            low = entry.lower()
            is_legacy = (
                entry in legacy_names
                or "vieneuv3" in low
                or "moss" in low
                or "sea_g2p" in low
                or low.endswith((".wav", ".zip", ".tar", ".gz", ".md", ".onnx", ".gguf", ".bin", ".data"))
            )
            if is_legacy:
                if os.path.isdir(full):
                    shutil.rmtree(full)
                elif os.path.isfile(full):
                    os.remove(full)
                print(f"  [clean] Removed legacy/unused asset: {entry}", flush=True)


def obtain_optional_neucodec_encoder(
    api: HfApi,
    pnnbao_public_repos: List[str],
    models_dir: str,
    dec_dest: str,
) -> Optional[str]:
    """
    Safe, non-fatal encoder acquisition:
      1) Try pre-exported public ONNX encoders from Hugging Face (using `list_repo_files`, never hardcoded names)
      2) Fall back to PyTorch opset-17 export of DistillNeuCodec / NeuCodec
      3) Optimize large FP32 encoder via verified QUInt8 quantization
    Wrapped cleanly so any error logs a detailed warning and returns None WITHOUT breaking the APK build.
    """
    enc_dest = os.path.join(models_dir, "neucodec_encoder.onnx")
    before_step_files = set(os.listdir(models_dir))

    try:
        encoder_candidates: List[str] = [
            "KevinAHM/distill-neucodec-onnx",
            "pnnbao-ump/neucodec-onnx-encoder",
            "pnnbao-ump/distill-neucodec-onnx",
            "pnnbao-ump/neucodec-onnx",
            "pnnbao-ump/VieNeu-TTS-v3-Nano",
            "pnnbao-ump/VieNeu-TTS-v3-Turbo",
        ]
        for r in pnnbao_public_repos:
            low = r.lower()
            if any(k in low for k in ("enc", "distill-neucodec", "neucodec", "v3-nano", "v3-turbo")):
                if "decoder" not in low and r not in encoder_candidates:
                    encoder_candidates.append(r)

        for query in ("distill-neucodec-onnx", "neucodec-onnx-encoder", "neucodec-encoder"):
            for r in discover_public_repos(api, search=query, limit=20):
                low = r.lower()
                if "decoder" in low and "enc" not in low:
                    continue
                if r not in encoder_candidates:
                    encoder_candidates.append(r)

        encoder_files: Optional[List[str]] = None
        encoder_source: Optional[str] = None

        print("\n=== [3/3] Obtaining & Verifying NeuCodec ONNX Encoder (non-fatal if CI export unsupported) ===", flush=True)
        for repo in encoder_candidates:
            files = list_public_repo_files(api, repo)
            if not files:
                continue
            onnx_candidates = rank_encoder_onnx_candidates(repo, files)
            if not onnx_candidates:
                continue
            print(f"    [candidates encoder] {repo}: {onnx_candidates}", flush=True)
            for chosen_enc in onnx_candidates:
                downloaded = download_onnx_with_companions(repo, chosen_enc, files, enc_dest, models_dir)
                if not downloaded:
                    continue
                schema = verify_neucodec_encoder_onnx(enc_dest, decoder_onnx_path=dec_dest)
                if schema is None:
                    cleanup_paths(downloaded)
                    continue
                encoder_files = downloaded
                encoder_source = f"{repo}/{chosen_enc}"
                break
            if encoder_files is not None:
                break

        if encoder_files is None:
            encoder_files = export_neucodec_encoder_from_python(enc_dest, decoder_onnx_path=dec_dest)
            if encoder_files is not None:
                encoder_source = "PyTorch export (DistillNeuCodec/NeuCodec opset 17)"

        if encoder_files is not None and os.path.isfile(enc_dest):
            encoder_files = try_quantize_encoder_for_mobile(enc_dest, encoder_files, decoder_onnx_path=dec_dest)
            print(f"  [OK ENCODER] Ready: {encoder_source}", flush=True)
            return encoder_source

        # Clean any leftover partial files from Step 3
        after_step_files = set(os.listdir(models_dir))
        cleanup_paths([os.path.join(models_dir, fn) for fn in (after_step_files - before_step_files)])
        print(
            "  [WARN ENCODER] Could not download or export a verified neucodec_encoder.onnx on this CI runner. "
            "Continuing APK build with verified VieNeu-TTS GGUF + NeuCodec Decoder + voices.json!",
            flush=True,
        )
        return None
    except Exception as exc:
        print(
            f"  [WARN ENCODER] Non-fatal error while obtaining/exporting neucodec_encoder.onnx: "
            f"{type(exc).__name__}: {exc}",
            flush=True,
        )
        traceback.print_exc()
        after_step_files = set(os.listdir(models_dir))
        cleanup_paths([os.path.join(models_dir, fn) for fn in (after_step_files - before_step_files)])
        print(
            "  [WARN ENCODER] Continuing APK build with verified VieNeu-TTS GGUF + NeuCodec Decoder + voices.json!",
            flush=True,
        )
        return None


def main() -> int:
    models_dir = sys.argv[1] if len(sys.argv) >= 2 else "app/src/main/assets/models"
    purge_assets_junk(models_dir)

    api = HfApi()
    pnnbao_public_repos = discover_public_repos(api, author="pnnbao-ump", limit=60)
    print(f"Public repos discovered under pnnbao-ump ({len(pnnbao_public_repos)}): {pnnbao_public_repos}", flush=True)

    # =========================================================================
    # 1. Official VieNeu-TTS-0.3B Q4_0 GGUF Backbone + voices.json (Dynamic listing)
    # =========================================================================
    gguf_candidates: List[str] = [
        "pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-0.3B-q8-gguf",
    ]
    for r in pnnbao_public_repos:
        if "gguf" in r.lower() and r not in gguf_candidates:
            gguf_candidates.append(r)

    gguf_dest: Optional[str] = None
    voices_dest: Optional[str] = None

    print("\n=== [1/3] Downloading Compact GGUF Backbone (Q4_0) + voices.json ===", flush=True)
    for repo in gguf_candidates:
        files = list_public_repo_files(api, repo)
        if not files:
            continue
        gguf_files = [f for f in files if f.lower().endswith(".gguf")]
        if not gguf_files:
            continue
        gguf_files.sort(key=lambda s: (0 if "q4_0" in s.lower() else (1 if "q4" in s.lower() else 2), len(s), s))
        for chosen_gguf in gguf_files:
            dest = os.path.join(models_dir, os.path.basename(chosen_gguf))
            if download_file_safe(repo, chosen_gguf, dest):
                gguf_dest = dest
                break
        if not gguf_dest:
            continue

        voices_files = [f for f in files if os.path.basename(f).lower() == "voices.json"]
        if voices_files:
            v_dest = os.path.join(models_dir, "voices.json")
            if download_file_safe(repo, voices_files[0], v_dest):
                voices_dest = v_dest
        break

    if not gguf_dest:
        raise RuntimeError("Failed to download *.gguf from public pnnbao-ump repositories!")

    if not voices_dest:
        for v_repo in ("pnnbao-ump/VieNeu-TTS-0.3B", "pnnbao-ump/VieNeu-TTS", "pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf"):
            files = list_public_repo_files(api, v_repo) or []
            v_files = [f for f in files if os.path.basename(f).lower() == "voices.json"]
            if v_files:
                v_dest = os.path.join(models_dir, "voices.json")
                if download_file_safe(v_repo, v_files[0], v_dest):
                    voices_dest = v_dest
                    break

    if not voices_dest:
        raise RuntimeError("Failed to download voices.json from public pnnbao-ump repositories!")

    # =========================================================================
    # 2. Compact NeuCodec ONNX 24kHz FSQ Decoder (Dynamic listing, no hardcoded filename)
    # =========================================================================
    decoder_candidates: List[str] = [
        "pnnbao-ump/neucodec-onnx-decoder-int8",
        "TamPham92/neucodec-onnx-decoder-int8",
        "aoiandroid/neuphonic-neucodec-onnx-decoder-int8-mirror",
        "pnnbao-ump/neucodec-onnx-decoder",
        "pnnbao-ump/VieNeu-TTS-v3-Nano",
    ]
    for r in pnnbao_public_repos:
        low = r.lower()
        if ("neucodec" in low or "decoder" in low) and "enc" not in low and "vieneu-codec" not in low:
            if r not in decoder_candidates:
                decoder_candidates.append(r)

    for query in ("neucodec-onnx-decoder-int8", "neucodec-onnx-decoder"):
        for r in discover_public_repos(api, search=query, limit=20):
            low = r.lower()
            if low.startswith("neuphonic/") or "vieneu-codec" in low or "enc" in low:
                continue
            if r not in decoder_candidates:
                decoder_candidates.append(r)

    dec_dest = os.path.join(models_dir, "neucodec_decoder.onnx")
    decoder_repo_used: Optional[str] = None
    print("\n=== [2/3] Downloading & Verifying Compact NeuCodec ONNX Decoder (24kHz) ===", flush=True)
    for repo in decoder_candidates:
        files = list_public_repo_files(api, repo)
        if not files:
            continue
        onnx_candidates = rank_decoder_onnx_candidates(files)
        if not onnx_candidates:
            continue
        print(f"    [candidates decoder] {repo}: {onnx_candidates}", flush=True)
        for chosen_dec in onnx_candidates:
            downloaded = download_onnx_with_companions(repo, chosen_dec, files, dec_dest, models_dir)
            if not downloaded:
                continue
            schema = verify_neucodec_decoder_onnx(dec_dest)
            if schema is None:
                cleanup_paths(downloaded)
                continue
            decoder_repo_used = f"{repo}/{chosen_dec}"
            break
        if decoder_repo_used:
            break

    if not decoder_repo_used:
        raise RuntimeError(
            "Failed to download a verified NeuCodec FSQ 24kHz neucodec_decoder.onnx! Checked: "
            + ", ".join(decoder_candidates)
        )
    print(f"  [OK DECODER] Ready: {decoder_repo_used}", flush=True)

    # =========================================================================
    # 3. NeuCodec ONNX Encoder (Non-fatal try-except: never fails APK build!)
    # =========================================================================
    obtain_optional_neucodec_encoder(
        api=api,
        pnnbao_public_repos=pnnbao_public_repos,
        models_dir=models_dir,
        dec_dest=dec_dest,
    )

    # =========================================================================
    # 4. Print exact file list & sizes for APK size audit
    # =========================================================================
    print("\n=== OFFLINE MODEL BUNDLE (app/src/main/assets/models) ===", flush=True)
    total_bytes = 0
    for fn in sorted(os.listdir(models_dir)):
        fp = os.path.join(models_dir, fn)
        if os.path.isfile(fp):
            sz = os.path.getsize(fp)
            total_bytes += sz
            print(f"  - {fn:40s} {sz / (1024 * 1024):8.2f} MB ({sz:,} bytes)", flush=True)
    print(f"TOTAL MODEL BUNDLE SIZE: {total_bytes / (1024 * 1024):.2f} MB", flush=True)

    if total_bytes < 50 * 1024 * 1024:
        raise RuntimeError(f"Downloaded bundle unexpectedly small ({total_bytes} bytes)!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
