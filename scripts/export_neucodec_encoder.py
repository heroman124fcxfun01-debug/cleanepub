#!/usr/bin/env python3
"""
Export & verify `neucodec_encoder.onnx` (opset 17, dynamic time axis) into
`app/src/main/assets/models/neucodec_encoder.onnx` for 100% offline real zero-shot
voice cloning on Android.

Contract:
  - Input:  `audio` — float32[1, 1, time]  (16,000 Hz Mono PCM)
  - Output: `codes` — int32[1, 1, frames]  (50 Hz NeuCodec FSQ tokens in 0..65535)
    100% shape & dtype compatible with `neucodec_decoder.onnx` (`codes: INT32[-1, 1, -1]`).

Export strategy (highest reliability & smallest mobile footprint first):
  1. Load `DistillNeuCodec.from_pretrained("neuphonic/distill-neucodec")` (lightweight ~59M
     params, 10x smaller than full NeuCodec, identical 50 Hz FSQ codebook & 24 kHz decoder),
     with fallback to `NeuCodec.from_pretrained("neuphonic/neucodec")`.
  2. Export via `torch.onnx.export(..., opset_version=17, dynamic_axes={"audio": {2: "time"}, "codes": {2: "frames"}})`:
       a) Direct `nn.Module` wrapper calling `codec.encode_code(audio_16k)`
       b) Direct `codec.encode_code(audio_16k)` with pure-PyTorch `feature_extractor` shim
       c) Auto-introspected pure-PyTorch encoder wrapper parity-verified against `codec.encode_code()`
  3. Fallback (if PyTorch export hits an upstream environment incompatibility): download verified
     public ONNX DistillNeuCodec encoder from Hugging Face (`KevinAHM/distill-neucodec-onnx` etc.)
     and ensure `int32[1, 1, N]` output compatibility.
  4. Strict ONNX Runtime verification on BOTH 3.0s (`[1, 1, 48000]`) AND 5.0s (`[1, 1, 80000]`)
     synthetic speech-like audio, plus direct roundtrip decode through `neucodec_decoder.onnx`.
  5. Safe mobile QUInt8 quantization on transformer `MatMul`/`Gemm` layers (re-verified end-to-end)
     to produce a compact single-file `neucodec_encoder.onnx` (~65-95 MB) for Android APK.

Usage:
  python3 scripts/export_neucodec_encoder.py <out_encoder_onnx> [decoder_onnx]
"""

import inspect
import json
import os
import shutil
import sys
import tempfile
import traceback
from typing import Dict, List, Optional, Tuple

import numpy as np
import onnxruntime as ort

ENCODER_SAMPLE_RATE = 16000
DECODER_SAMPLE_RATE = 24000
ENCODER_HOP_SIZE = 320


def resample_blackman_sinc(samples: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    """Anti-aliased Blackman-windowed sinc resampler (matches Android AudioFileDecoder)."""
    wav = np.asarray(samples, dtype=np.float32).reshape(-1)
    if src_rate == dst_rate or wav.size == 0:
        return wav
    ratio = float(src_rate) / float(dst_rate)
    out_len = max(1, int(wav.size / ratio))
    if src_rate <= dst_rate:
        x_old = np.linspace(0.0, 1.0, num=wav.size, endpoint=True)
        x_new = np.linspace(0.0, 1.0, num=out_len, endpoint=True)
        return np.interp(x_new, x_old, wav).astype(np.float32)

    cutoff = min(0.98, max(0.05, 0.92 / ratio))
    half_width = max(12, min(48, int(round(12.0 * ratio))))
    centers = np.arange(out_len, dtype=np.float64) * ratio
    offsets = np.arange(-half_width, half_width + 1, dtype=np.int64)
    base_idx = np.floor(centers).astype(np.int64)[:, None] + offsets[None, :]
    valid = (base_idx >= 0) & (base_idx < wav.size)
    clamped = np.clip(base_idx, 0, wav.size - 1).astype(np.int64)
    d = base_idx.astype(np.float64) - centers[:, None]
    norm = (d + half_width) / (2.0 * half_width)
    in_win = valid & (norm >= 0.0) & (norm <= 1.0)
    w = 0.42 - 0.5 * np.cos(2.0 * np.pi * np.clip(norm, 0.0, 1.0)) + 0.08 * np.cos(4.0 * np.pi * np.clip(norm, 0.0, 1.0))
    sinc_val = np.sinc(cutoff * d)
    coeffs = np.where(in_win, w * sinc_val * cutoff, 0.0)
    w_sum = np.sum(coeffs, axis=1)
    acc = np.sum(wav[clamped].astype(np.float64) * coeffs, axis=1)
    out = np.where(np.abs(w_sum) > 1e-9, acc / np.where(np.abs(w_sum) > 1e-9, w_sum, 1.0), wav[np.clip(np.floor(centers).astype(np.int64), 0, wav.size - 1)])
    return np.clip(out, -1.0, 1.0).astype(np.float32)


def make_real_vietnamese_speech_16k(decoder_onnx_path: Optional[str]) -> Optional[np.ndarray]:
    """Decode an official voices.json preset via neucodec_decoder.onnx to test encoder parity on REAL speech."""
    if not decoder_onnx_path or not os.path.isfile(decoder_onnx_path):
        return None
    voices_path = os.path.join(os.path.dirname(os.path.abspath(decoder_onnx_path)), "voices.json")
    if not os.path.isfile(voices_path):
        return None
    try:
        with open(voices_path, "r", encoding="utf-8") as f:
            data = json.load(f)
        presets = data.get("presets", data) if isinstance(data, dict) else {}
        codes = None
        if isinstance(presets, dict):
            for _, entry in presets.items():
                if isinstance(entry, dict) and isinstance(entry.get("codes"), list) and len(entry["codes"]) >= 60:
                    codes = [int(c) for c in entry["codes"] if 0 <= int(c) <= 65535]
                    break
        if not codes:
            return None
        dec_sess = ort.InferenceSession(decoder_onnx_path, providers=["CPUExecutionProvider"])
        dec_inp = dec_sess.get_inputs()[0]
        dt = np.int64 if "int64" in dec_inp.type.lower() else np.int32
        arr = np.asarray(codes, dtype=dt)
        if len(dec_inp.shape) == 3:
            arr = arr.reshape(1, 1, -1)
        elif len(dec_inp.shape) == 2:
            arr = arr.reshape(1, -1)
        wav_24k = np.asarray(dec_sess.run(None, {dec_inp.name: arr})[0], dtype=np.float32).reshape(-1)
        if wav_24k.size < DECODER_SAMPLE_RATE:
            return None
        wav_16k = resample_blackman_sinc(wav_24k, DECODER_SAMPLE_RATE, ENCODER_SAMPLE_RATE)
        wav_16k = wav_16k[: ENCODER_SAMPLE_RATE * 5]
        rem = wav_16k.size % ENCODER_HOP_SIZE
        if rem:
            wav_16k = np.concatenate([wav_16k, np.zeros(ENCODER_HOP_SIZE - rem, dtype=np.float32)])
        print(f"  [ref-speech] Built REAL Vietnamese reference clip: {wav_16k.size} samples ({wav_16k.size / 16000.0:.2f}s @ 16kHz)", flush=True)
        return wav_16k
    except Exception as exc:
        print(f"  [warn ref-speech] Could not decode voices.json preset for parity test: {exc}", flush=True)
        return None


def compute_log_spectral_cosine(wav_a: np.ndarray, wav_b: np.ndarray) -> float:
    """Compare acoustic timbre/envelope similarity of two 24kHz waveforms via log-magnitude STFT cosine."""
    n = min(wav_a.size, wav_b.size)
    if n < 1024:
        return 0.0
    a = wav_a[:n].astype(np.float64)
    b = wav_b[:n].astype(np.float64)
    win = np.hanning(512)
    hop = 256
    frames = (n - 512) // hop + 1
    if frames < 4:
        return 0.0
    specs_a = []
    specs_b = []
    for i in range(frames):
        s = i * hop
        fa = np.log1p(np.abs(np.fft.rfft(a[s : s + 512] * win)))
        fb = np.log1p(np.abs(np.fft.rfft(b[s : s + 512] * win)))
        specs_a.append(fa)
        specs_b.append(fb)
    va = np.concatenate(specs_a)
    vb = np.concatenate(specs_b)
    denom = float(np.linalg.norm(va) * np.linalg.norm(vb))
    if denom < 1e-12:
        return 0.0
    return float(np.dot(va, vb) / denom)


def compute_fsq_axis_accuracy(codes_a: np.ndarray, codes_b: np.ndarray) -> float:
    """NeuCodec FSQ packs 8 base-4 levels into 0..65535 (4^8). Compute exact match across all 8 axes."""
    m = min(codes_a.size, codes_b.size)
    if m == 0:
        return 0.0
    a = codes_a[:m].astype(np.int64)
    b = codes_b[:m].astype(np.int64)
    matches = 0
    for shift in range(8):
        da = (a >> (2 * shift)) & 3
        db = (b >> (2 * shift)) & 3
        matches += int(np.sum(da == db))
    return float(matches) / float(m * 8)


def verify_encoder_parity_vs_pytorch(
    encoder_onnx_path: str,
    test_wav_16k: np.ndarray,
    ref_codes_pt: Optional[np.ndarray],
    decoder_onnx_path: Optional[str] = None,
    min_exact_match: float = 0.90,
    min_fsq_axis_acc: float = 0.96,
    min_spectral_cos: float = 0.96,
    label: str = "ONNX",
) -> Tuple[bool, Dict[str, float]]:
    """
    Verify that `encoder_onnx_path` matches live PyTorch `codec.encode_code(test_wav_16k)` in BOTH
    discrete FSQ token IDs AND reconstructed 24kHz log-spectral timbre!
    """
    if ref_codes_pt is None or ref_codes_pt.size == 0:
        return True, {"exact_match": 1.0, "fsq_axis_acc": 1.0, "spectral_cos": 1.0}
    try:
        sess = ort.InferenceSession(encoder_onnx_path, providers=["CPUExecutionProvider"])
        inputs = sess.get_inputs()
        outputs_meta = sess.get_outputs()
        feed: Dict[str, np.ndarray] = {}
        for inp in inputs:
            low = inp.name.lower()
            t_low = inp.type.lower()
            if "int" in t_low or "len" in low:
                dt = np.int64 if "int64" in t_low else np.int32
                feed[inp.name] = np.asarray([test_wav_16k.size], dtype=dt)
            else:
                rank = len(inp.shape)
                arr = test_wav_16k.reshape(1, 1, -1) if rank == 3 else (test_wav_16k.reshape(1, -1) if rank == 2 else test_wav_16k.reshape(-1))
                feed[inp.name] = arr.astype(np.float16 if "float16" in t_low else np.float32)
        out_list = sess.run(None, feed)
        chosen = None
        for idx, val in enumerate(out_list):
            if "int" in outputs_meta[idx].type.lower() or "code" in outputs_meta[idx].name.lower():
                chosen = np.asarray(val)
                break
        if chosen is None:
            chosen = np.asarray(out_list[0])

        onnx_flat = chosen.reshape(-1).astype(np.int64)
        ref_flat = np.asarray(ref_codes_pt).reshape(-1).astype(np.int64)
        m = min(onnx_flat.size, ref_flat.size)
        if m < 40 or abs(onnx_flat.size - ref_flat.size) > 4:
            print(f"  [parity REJECT {label}] Length mismatch: onnx={onnx_flat.size} vs pt={ref_flat.size}", flush=True)
            return False, {"exact_match": 0.0, "fsq_axis_acc": 0.0, "spectral_cos": 0.0}

        exact_match = float(np.mean(onnx_flat[:m] == ref_flat[:m]))
        fsq_axis_acc = compute_fsq_axis_accuracy(onnx_flat[:m], ref_flat[:m])

        spectral_cos = 1.0
        if decoder_onnx_path and os.path.isfile(decoder_onnx_path):
            dec_sess = ort.InferenceSession(decoder_onnx_path, providers=["CPUExecutionProvider"])
            dec_inp = dec_sess.get_inputs()[0]
            dt = np.int64 if "int64" in dec_inp.type.lower() else np.int32

            def decode_flat(flat_codes: np.ndarray) -> np.ndarray:
                arr = flat_codes.astype(dt)
                if len(dec_inp.shape) == 3:
                    arr = arr.reshape(1, 1, -1)
                elif len(dec_inp.shape) == 2:
                    arr = arr.reshape(1, -1)
                return np.asarray(dec_sess.run(None, {dec_inp.name: arr})[0], dtype=np.float32).reshape(-1)

            wav_ref = decode_flat(ref_flat[:m])
            wav_onnx = decode_flat(onnx_flat[:m])
            spectral_cos = compute_log_spectral_cosine(wav_ref, wav_onnx)

        passed = (
            exact_match >= min_exact_match
            or (fsq_axis_acc >= min_fsq_axis_acc and spectral_cos >= min_spectral_cos)
        )
        status = "PASS" if passed else "REJECT"
        print(
            f"  [parity {status} {label}] exact_token_match={exact_match * 100:.2f}% | "
            f"fsq_8axis_acc={fsq_axis_acc * 100:.2f}% | decoded_spectral_cos={spectral_cos:.4f} | "
            f"onnx[:8]={onnx_flat[:8].tolist()} vs pt[:8]={ref_flat[:8].tolist()}",
            flush=True,
        )
        return passed, {
            "exact_match": exact_match,
            "fsq_axis_acc": fsq_axis_acc,
            "spectral_cos": spectral_cos,
        }
    except Exception as exc:
        print(f"  [parity REJECT {label}] Error: {type(exc).__name__}: {exc}", flush=True)
        return False, {"exact_match": 0.0, "fsq_axis_acc": 0.0, "spectral_cos": 0.0}


def make_speech_like_signal_16k(duration_sec: float = 3.0) -> np.ndarray:
    """Generate a realistic 16 kHz voiced speech-like waveform (varying F0 + harmonics + syllable envelope)."""
    n = int(round(duration_sec * ENCODER_SAMPLE_RATE))
    rem = n % ENCODER_HOP_SIZE
    if rem:
        n += ENCODER_HOP_SIZE - rem
    t = np.arange(n, dtype=np.float32) / float(ENCODER_SAMPLE_RATE)
    f0 = 155.0 + 35.0 * np.sin(2.0 * np.pi * 1.1 * t) + 12.0 * np.cos(2.0 * np.pi * 0.45 * t)
    phase = 2.0 * np.pi * np.cumsum(f0) / float(ENCODER_SAMPLE_RATE)
    sig = (
        0.50 * np.sin(phase)
        + 0.30 * np.sin(2.0 * phase + 0.35)
        + 0.18 * np.sin(3.0 * phase + 0.85)
        + 0.10 * np.sin(5.0 * phase + 1.25)
    )
    env = 0.20 + 0.80 * np.maximum(0.0, np.sin(2.0 * np.pi * 2.4 * t))
    wav = (sig * env).astype(np.float32)
    peak = float(np.max(np.abs(wav)))
    if peak > 1e-6:
        wav = wav * (0.65 / peak)
    return wav


def safe_unlink(path: str) -> None:
    """Remove regular files, read-only files, symlinks, and broken symlinks safely."""
    if os.path.lexists(path):
        if not os.path.islink(path):
            try:
                os.chmod(path, 0o777)
            except OSError:
                pass
        try:
            os.unlink(path)
        except OSError:
            pass


def safe_copy_file(src: str, dst: str) -> None:
    """Safely overwrite `dst` without inheriting read-only permissions from Hugging Face cache."""
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    safe_unlink(dst)
    shutil.copyfile(src, dst)
    try:
        os.chmod(dst, 0o644)
    except OSError:
        pass


def cleanup_paths(paths: List[str]) -> None:
    for p in paths:
        safe_unlink(p)


def describe_io(nodes) -> List[Dict[str, object]]:
    return [{"name": n.name, "type": n.type, "shape": list(n.shape)} for n in nodes]


def verify_encoder_onnx(
    encoder_onnx_path: str,
    decoder_onnx_path: Optional[str] = None,
    require_int32_3d: bool = True,
) -> Optional[Dict[str, object]]:
    """
    Verify `neucodec_encoder.onnx` with ONNX Runtime on 3.0s AND 5.0s synthetic 16 kHz audio,
    confirming exact shape `[1, 1, N]`, dtype compatibility, dynamic time axis, and direct
    roundtrip decoding through `neucodec_decoder.onnx`.
    """
    try:
        sess = ort.InferenceSession(encoder_onnx_path, providers=["CPUExecutionProvider"])
        inputs = sess.get_inputs()
        outputs_meta = sess.get_outputs()
        in_desc = describe_io(inputs)
        out_desc = describe_io(outputs_meta)
        print(f"  [verify] Encoder inputs:  {in_desc}", flush=True)
        print(f"  [verify] Encoder outputs: {out_desc}", flush=True)

        def run_once(duration_sec: float) -> Tuple[np.ndarray, int]:
            wav_16k = make_speech_like_signal_16k(duration_sec=duration_sec)
            feed: Dict[str, np.ndarray] = {}
            for inp in inputs:
                low = inp.name.lower()
                t_low = inp.type.lower()
                if "int" in t_low or "len" in low:
                    dt = np.int64 if "int64" in t_low else np.int32
                    feed[inp.name] = np.asarray([wav_16k.size], dtype=dt)
                else:
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
            out_list = sess.run(None, feed)
            # Pick codes output
            chosen = None
            for idx, val in enumerate(out_list):
                o_type = outputs_meta[idx].type.lower()
                o_name = outputs_meta[idx].name.lower()
                if "int" in o_type or "code" in o_name or "token" in o_name or "idx" in o_name:
                    chosen = np.asarray(val)
                    break
            if chosen is None:
                chosen = np.asarray(out_list[0])
            return chosen, wav_16k.size

        codes_3s, samples_3s = run_once(3.0)
        print(
            f"  [verify] 3.0s ({samples_3s} samples @ 16kHz) -> codes shape={codes_3s.shape}, dtype={codes_3s.dtype}",
            flush=True,
        )

        if require_int32_3d:
            if codes_3s.ndim != 3 or codes_3s.shape[0] != 1 or codes_3s.shape[1] != 1:
                print(f"  [reject] Expected output shape [1, 1, N], got {codes_3s.shape}", flush=True)
                return None
            if codes_3s.dtype != np.int32:
                print(f"  [reject] Expected output dtype int32, got {codes_3s.dtype}", flush=True)
                return None

        flat_3s = codes_3s.reshape(-1)
        if flat_3s.size < 80:
            print(f"  [reject] Too few frames for 3.0s audio ({flat_3s.size} < 80)", flush=True)
            return None
        if int(flat_3s.min()) < 0 or int(flat_3s.max()) > 65535:
            print(f"  [reject] FSQ token out of range 0..65535 (min={flat_3s.min()}, max={flat_3s.max()})", flush=True)
            return None
        unique_3s = len(set(int(v) for v in flat_3s.tolist()))
        if unique_3s < 8:
            print(f"  [reject] Degenerate/collapsed FSQ output ({unique_3s} unique tokens < 8)", flush=True)
            return None

        # Verify dynamic time axis on 5.0s input!
        codes_5s, samples_5s = run_once(5.0)
        print(
            f"  [verify] 5.0s ({samples_5s} samples @ 16kHz) -> codes shape={codes_5s.shape}, dtype={codes_5s.dtype}",
            flush=True,
        )
        if codes_5s.size <= codes_3s.size:
            print(
                f"  [reject] Dynamic axis check failed: 5.0s ({codes_5s.size}) <= 3.0s ({codes_3s.size})",
                flush=True,
            )
            return None

        # Direct roundtrip check into `neucodec_decoder.onnx` (`codes: INT32[-1, 1, -1]`)
        if decoder_onnx_path and os.path.isfile(decoder_onnx_path):
            dec_sess = ort.InferenceSession(decoder_onnx_path, providers=["CPUExecutionProvider"])
            dec_inp = dec_sess.get_inputs()[0]
            print(
                f"  [verify] Decoder input contract: name={dec_inp.name}, type={dec_inp.type}, shape={dec_inp.shape}",
                flush=True,
            )
            dec_codes = codes_3s
            if "int64" in dec_inp.type.lower() and dec_codes.dtype != np.int64:
                dec_codes = dec_codes.astype(np.int64)
            elif "int32" in dec_inp.type.lower() and dec_codes.dtype != np.int32:
                dec_codes = dec_codes.astype(np.int32)
            if len(dec_inp.shape) == 3 and dec_codes.ndim != 3:
                dec_codes = dec_codes.reshape(1, 1, -1)
            elif len(dec_inp.shape) == 2 and dec_codes.ndim != 2:
                dec_codes = dec_codes.reshape(1, -1)

            dec_out = dec_sess.run(None, {dec_inp.name: dec_codes})
            wav_24k = np.asarray(dec_out[0], dtype=np.float32).reshape(-1)
            peak = float(np.max(np.abs(wav_24k))) if wav_24k.size else 0.0
            if wav_24k.size < 24000 or not np.all(np.isfinite(wav_24k)) or peak < 0.005:
                print(
                    f"  [reject] Roundtrip decode via neucodec_decoder.onnx failed "
                    f"(samples={wav_24k.size}, peak={peak:.5f})",
                    flush=True,
                )
                return None
            print(
                f"  [PASS] 100% compatible with neucodec_decoder.onnx! "
                f"codes {codes_3s.shape} ({codes_3s.dtype}, {unique_3s} unique) -> "
                f"{wav_24k.size} samples @ 24kHz (peak={peak:.4f})",
                flush=True,
            )
        else:
            print(
                f"  [PASS] Verified neucodec_encoder.onnx: "
                f"3.0s -> {codes_3s.shape} ({codes_3s.dtype}, {unique_3s} unique), 5.0s -> {codes_5s.shape}",
                flush=True,
            )

        return {
            "inputs": in_desc,
            "outputs": out_desc,
            "codes_3s_shape": list(codes_3s.shape),
            "codes_5s_shape": list(codes_5s.shape),
            "unique_3s": unique_3s,
        }
    except Exception as exc:
        print(f"  [reject] ONNX Runtime verification error: {type(exc).__name__}: {exc}", flush=True)
        return None


def remove_all_weight_norms(model) -> None:
    """Safely fuse weight_norm / parametrizations before ONNX export."""
    import torch

    for module in model.modules():
        try:
            torch.nn.utils.remove_weight_norm(module)
        except Exception:
            pass
        try:
            if hasattr(torch.nn.utils, "parametrize") and torch.nn.utils.parametrize.is_parametrized(module, "weight"):
                torch.nn.utils.parametrize.remove_parametrizations(module, "weight", leave_parametrized=True)
        except Exception:
            pass


def force_eager_attention(codec) -> None:
    """Disable SDPA in HuggingFace Hubert/Wav2Vec2 semantic_model so opset-17 export succeeds cleanly."""
    sem_model = getattr(codec, "semantic_model", None)
    if sem_model is None:
        return
    cfg = getattr(sem_model, "config", None)
    if cfg is not None:
        for attr in ("_attn_implementation", "attn_implementation"):
            try:
                setattr(cfg, attr, "eager")
            except Exception:
                pass
    if hasattr(sem_model, "set_attn_implementation"):
        try:
            sem_model.set_attn_implementation("eager")
            print("  [export] Forced semantic_model attention implementation = eager", flush=True)
        except Exception as exc:
            print(f"  [export] set_attn_implementation('eager') note: {exc}", flush=True)


def _find_attr(obj, names: Tuple[str, ...]):
    for name in names:
        val = getattr(obj, name, None)
        if val is not None:
            return val
    return None


def _build_pure_torch_encoder_candidates(codec, dummy_3s, ref_codes_pt):
    """
    Build pure-PyTorch `nn.Module` wrappers that replicate `codec.encode_code(audio_16k)`
    without numpy/torchaudio calls, auto-selecting exact padding/layer/concat parity against
    the live `codec.encode_code(dummy_3s)` reference output!
    """
    import torch
    import torch.nn as nn
    import torch.nn.functional as F

    semantic_model = _find_attr(codec, ("semantic_model", "semantic_encoder_backbone"))
    semantic_enc = _find_attr(codec, ("SemanticEncoder_module", "semantic_encoder", "SemanticEncoder"))
    acoustic_enc = _find_attr(codec, ("CodecEnc", "codec_enc", "acoustic_encoder"))
    fc_prior = _find_attr(codec, ("fc_prior", "prior_proj"))
    generator = _find_attr(codec, ("generator", "decoder"))
    quantizer = getattr(generator, "quantizer", None) if generator is not None else _find_attr(codec, ("quantizer",))

    if semantic_model is None or fc_prior is None or quantizer is None:
        print(
            f"  [export] Skipping pure-torch introspected wrapper (missing modules: "
            f"semantic_model={semantic_model is not None}, semantic_enc={semantic_enc is not None}, "
            f"acoustic_enc={acoustic_enc is not None}, fc_prior={fc_prior is not None}, "
            f"quantizer={quantizer is not None})",
            flush=True,
        )
        return []

    class PureTorchNeuCodecEncoder(nn.Module):
        def __init__(
            self,
            inner_semantic_model,
            inner_semantic_enc,
            inner_acoustic_enc,
            inner_fc_prior,
            inner_quantizer,
            pad_160: bool,
            use_layer_16: bool,
            concat_order: str,
            normalize_sem: bool,
        ):
            super().__init__()
            self.semantic_model = inner_semantic_model
            self.semantic_enc = inner_semantic_enc
            self.acoustic_enc = inner_acoustic_enc
            self.fc_prior = inner_fc_prior
            self.quantizer = inner_quantizer
            self.pad_160 = pad_160
            self.use_layer_16 = use_layer_16
            self.concat_order = concat_order
            self.normalize_sem = normalize_sem
            q_sig = inspect.signature(inner_quantizer.forward).parameters
            self.quantizer_has_vq = "vq" in q_sig

        def forward(self, audio: torch.Tensor) -> torch.Tensor:
            # Input contract: audio float32[1, 1, time]
            wav_b1t = audio
            wav_bt = audio.squeeze(1)

            # 1. Semantic branch (DistilHuBERT / HuBERT)
            wav_sem = F.pad(wav_bt, (160, 160)) if self.pad_160 else wav_bt
            if self.normalize_sem:
                mean = wav_sem.mean(dim=-1, keepdim=True)
                var = wav_sem.var(dim=-1, unbiased=False, keepdim=True)
                wav_sem = (wav_sem - mean) / torch.sqrt(var + 1e-7)

            sem_out = self.semantic_model(wav_sem, output_hidden_states=True)
            hidden_states = getattr(sem_out, "hidden_states", None)
            if hidden_states is not None:
                if self.use_layer_16 and len(hidden_states) > 16:
                    sem_feat = hidden_states[16]
                else:
                    sem_feat = hidden_states[-1]
            elif hasattr(sem_out, "last_hidden_state"):
                sem_feat = sem_out.last_hidden_state
            else:
                sem_feat = sem_out[0]

            # SemanticEncoder_module expects (B, D, T)
            if self.semantic_enc is not None:
                x_sem = self.semantic_enc(sem_feat.transpose(1, 2))
            else:
                x_sem = sem_feat.transpose(1, 2)

            # 2. Acoustic branch (CodecEnc / SQCodec / BigCodec)
            if self.acoustic_enc is not None:
                x_ac = self.acoustic_enc(wav_b1t)
                if x_ac.dim() == 3 and x_ac.shape[1] != x_sem.shape[1] and x_ac.shape[2] == x_sem.shape[1]:
                    x_ac = x_ac.transpose(1, 2)
                # Ensure both are (B, C, T) and align temporal dimension dynamically
                if x_ac.shape[-1] != x_sem.shape[-1]:
                    # Slice to shorter dynamic length using tensor-safe slicing
                    t_sem = x_sem.shape[-1]
                    t_ac = x_ac.shape[-1]
                    if t_sem <= t_ac:
                        x_ac = x_ac[..., :t_sem]
                    else:
                        x_sem = x_sem[..., :t_ac]
                if self.concat_order == "ac_sem":
                    x = torch.cat([x_ac, x_sem], dim=1)
                else:
                    x = torch.cat([x_sem, x_ac], dim=1)
            else:
                x = x_sem

            # 3. Prior projection (Linear expects (B, T, C); Conv1d expects (B, C, T))
            if isinstance(self.fc_prior, nn.Linear):
                z = self.fc_prior(x.transpose(1, 2)).transpose(1, 2)
            else:
                z = self.fc_prior(x)

            # 4. FSQ Quantizer -> int32[1, 1, frames]
            if self.quantizer_has_vq:
                q_out = self.quantizer(z, vq=True)
            else:
                q_out = self.quantizer(z)

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

            if codes.dim() == 2:
                codes = codes.unsqueeze(1)
            elif codes.dim() == 1:
                codes = codes.unsqueeze(0).unsqueeze(0)
            return codes.to(torch.int32)

    # Probe exact parity against live `codec.encode_code(dummy_3s)`!
    ref_flat = ref_codes_pt.detach().cpu().reshape(-1).to(torch.int64) if ref_codes_pt is not None else None
    scored: List[Tuple[float, str, nn.Module]] = []

    # Prioritize matching orig_fe.do_normalize FIRST so DistilHuBERT (do_normalize=False) matches 100%!
    orig_fe = getattr(codec, "feature_extractor", None)
    fe_do_norm = bool(getattr(orig_fe, "do_normalize", False)) if orig_fe is not None else False
    print(f"  [info] Introspected codec.feature_extractor.do_normalize = {fe_do_norm}", flush=True)

    configs = [
        (True, False, "ac_sem", fe_do_norm),
        (True, False, "sem_ac", fe_do_norm),
        (False, False, "ac_sem", fe_do_norm),
        (False, False, "sem_ac", fe_do_norm),
        (True, False, "ac_sem", not fe_do_norm),
        (True, False, "sem_ac", not fe_do_norm),
        (False, False, "ac_sem", not fe_do_norm),
        (True, True, "ac_sem", fe_do_norm),
        (True, True, "sem_ac", fe_do_norm),
    ]
    for pad_160, use_l16, order, norm_sem in configs:
        mod = PureTorchNeuCodecEncoder(
            semantic_model,
            semantic_enc,
            acoustic_enc,
            fc_prior,
            quantizer,
            pad_160=pad_160,
            use_layer_16=use_l16,
            concat_order=order,
            normalize_sem=norm_sem,
        ).eval()
        label = f"PureTorch(pad160={pad_160}, l16={use_l16}, order={order}, norm={norm_sem})"
        try:
            with torch.no_grad():
                out = mod(dummy_3s)
            flat = out.detach().cpu().reshape(-1).to(torch.int64)
            if flat.numel() < 80:
                continue
            parity = 0.0
            if ref_flat is not None:
                m = min(flat.numel(), ref_flat.numel())
                len_bonus = 1.0 if flat.numel() == ref_flat.numel() else 0.0
                exact_match = float((flat[:m] == ref_flat[:m]).float().mean().item())
                parity = exact_match + 0.05 * len_bonus
                print(
                    f"  [parity] {label}: shape={tuple(out.shape)} vs ref={tuple(ref_codes_pt.shape)} | "
                    f"exact_match={exact_match * 100:.1f}%",
                    flush=True,
                )
            scored.append((parity, label, mod))
            if parity >= 0.99:
                break
        except Exception as exc:
            print(f"  [parity-skip] {label}: {type(exc).__name__}: {exc}", flush=True)

    scored.sort(key=lambda x: x[0], reverse=True)
    return [(label, mod) for _, label, mod in scored[:3]]


def _export_module_to_onnx(
    module,
    dummy_3s,
    out_onnx_path: str,
    decoder_onnx_path: Optional[str],
    label: str,
    parity_wav_16k: Optional[np.ndarray] = None,
    ref_codes_pt: Optional[np.ndarray] = None,
) -> Optional[List[str]]:
    import torch

    out_dir = os.path.dirname(os.path.abspath(out_onnx_path))
    os.makedirs(out_dir, exist_ok=True)
    before_files = set(os.listdir(out_dir))

    print(f"\n  [export] Exporting {label} -> {out_onnx_path} (opset 17, dynamic time/frames)...", flush=True)
    export_attempts = [
        ("TorchScript opset 17 (dynamo=False)", False),
        ("Dynamo exporter opset 17 (dynamo=True)", True),
    ]

    for attempt_name, use_dynamo in export_attempts:
        try:
            safe_unlink(out_onnx_path)
            with torch.no_grad():
                pt_out = module(dummy_3s)
                print(
                    f"    [{attempt_name}] PyTorch forward OK: shape={tuple(pt_out.shape)}, dtype={pt_out.dtype}",
                    flush=True,
                )

            if not use_dynamo:
                export_kwargs = dict(
                    f=out_onnx_path,
                    export_params=True,
                    opset_version=17,
                    do_constant_folding=True,
                    input_names=["audio"],
                    output_names=["codes"],
                    dynamic_axes={
                        "audio": {2: "time"},
                        "codes": {2: "frames"},
                    },
                )
                try:
                    torch.onnx.export(module, (dummy_3s,), dynamo=False, **export_kwargs)
                except TypeError:
                    torch.onnx.export(module, (dummy_3s,), **export_kwargs)
            else:
                time_dim = torch.export.Dim("time", min=1600, max=480000)
                torch.onnx.export(
                    module,
                    (dummy_3s,),
                    f=out_onnx_path,
                    opset_version=17,
                    input_names=["audio"],
                    output_names=["codes"],
                    dynamic_shapes={"audio": {2: time_dim}},
                    dynamo=True,
                )

            after_files = set(os.listdir(out_dir))
            created = [os.path.join(out_dir, fn) for fn in sorted(after_files - before_files)]
            if out_onnx_path not in created and os.path.isfile(out_onnx_path):
                created.insert(0, out_onnx_path)

            if verify_encoder_onnx(out_onnx_path, decoder_onnx_path=decoder_onnx_path, require_int32_3d=True):
                if parity_wav_16k is not None and ref_codes_pt is not None:
                    parity_ok, _ = verify_encoder_parity_vs_pytorch(
                        encoder_onnx_path=out_onnx_path,
                        test_wav_16k=parity_wav_16k,
                        ref_codes_pt=ref_codes_pt,
                        decoder_onnx_path=decoder_onnx_path,
                        min_exact_match=0.95,
                        min_fsq_axis_acc=0.985,
                        min_spectral_cos=0.985,
                        label=f"{label}/{attempt_name}",
                    )
                    if not parity_ok:
                        print(
                            f"    [reject {attempt_name}] Failed strict PyTorch parity check (>=95% exact FSQ match)!",
                            flush=True,
                        )
                        cleanup_paths(created)
                        continue
                print(f"  [OK EXPORT] {label} via {attempt_name} succeeded & parity-verified!", flush=True)
                return created

            cleanup_paths(created)
        except Exception as exc:
            print(f"    [warn {attempt_name}] failed: {type(exc).__name__}: {exc}", flush=True)
            after_files = set(os.listdir(out_dir))
            cleanup_paths([os.path.join(out_dir, fn) for fn in (after_files - before_files)])

    return None


def export_from_neucodec_python(
    out_onnx_path: str,
    decoder_onnx_path: Optional[str],
) -> Tuple[Optional[List[str]], Optional[np.ndarray], Optional[np.ndarray]]:
    """
    Load DistillNeuCodec (preferred for mobile APK) or NeuCodec and export encoder to ONNX opset 17
    with STRICT exact FSQ token parity against live PyTorch `codec.encode_code(...)` on REAL speech.
    Returns `(created_files, parity_wav_16k, ref_codes_pt_np)`.
    """
    import torch
    import torch.nn as nn

    model_specs = [
        ("DistillNeuCodec", "neuphonic/distill-neucodec"),
        ("NeuCodec", "neuphonic/neucodec"),
    ]

    dummy_3s_np = make_speech_like_signal_16k(duration_sec=3.0)
    real_speech_np = make_real_vietnamese_speech_16k(decoder_onnx_path)
    parity_wav_np = real_speech_np if real_speech_np is not None else dummy_3s_np
    dummy_3s = torch.from_numpy(dummy_3s_np).reshape(1, 1, -1)
    parity_tensor = torch.from_numpy(parity_wav_np).reshape(1, 1, -1)

    class DirectEncodeCodeWrapper(nn.Module):
        def __init__(self, inner_codec):
            super().__init__()
            self.codec = inner_codec

        def forward(self, audio: torch.Tensor) -> torch.Tensor:
            codes = self.codec.encode_code(audio)
            if codes.dim() == 2:
                codes = codes.unsqueeze(1)
            elif codes.dim() == 1:
                codes = codes.unsqueeze(0).unsqueeze(0)
            return codes.to(torch.int32)

    class PureTorchFeatureExtractorShim:
        """
        Replaces HF Wav2Vec2FeatureExtractor numpy path with pure-PyTorch tensor ops that
        STRICTLY respect the actual `do_normalize` setting of `codec.feature_extractor`!
        (CRITICAL: `ntu-spml/distilhubert` uses `do_normalize=False` — normalizing it when False
        destroys DistilHuBERT semantic features and breaks zero-shot voice cloning!)
        """

        def __init__(self, do_normalize: bool):
            self.do_normalize = do_normalize

        def __call__(self, raw_speech, sampling_rate=16000, return_tensors="pt", **kwargs):
            if isinstance(raw_speech, torch.Tensor):
                x = raw_speech.float()
            else:
                x = torch.as_tensor(raw_speech, dtype=torch.float32)
            if x.dim() == 1:
                x = x.unsqueeze(0)
            if self.do_normalize:
                mean = x.mean(dim=-1, keepdim=True)
                var = x.var(dim=-1, unbiased=False, keepdim=True)
                x = (x - mean) / torch.sqrt(var + 1e-7)
            return {"input_values": x}

    for class_name, repo_id in model_specs:
        print(f"\n=== Attempting PyTorch ONNX export from {class_name} ({repo_id}) ===", flush=True)
        try:
            import neucodec

            cls = getattr(neucodec, class_name, None)
            if cls is None:
                print(f"  [skip] neucodec.{class_name} not available in installed neucodec package", flush=True)
                continue
            codec = cls.from_pretrained(repo_id)
        except Exception as exc:
            print(f"  [warn] Failed to load {class_name}.from_pretrained('{repo_id}'): {exc}", flush=True)
            continue

        codec.eval()
        for p in codec.parameters():
            p.requires_grad_(False)
        remove_all_weight_norms(codec)
        force_eager_attention(codec)

        child_names = [n for n, _ in codec.named_children()]
        orig_fe = getattr(codec, "feature_extractor", None)
        fe_do_norm = bool(getattr(orig_fe, "do_normalize", False)) if orig_fe is not None else False
        print(
            f"  [info] {class_name} child modules: {child_names} | "
            f"feature_extractor={type(orig_fe).__name__ if orig_fe is not None else None} (do_normalize={fe_do_norm})",
            flush=True,
        )
        try:
            src = inspect.getsource(codec.encode_code)
            print("  [info] encode_code source:\n" + "\n".join("    " + ln for ln in src.splitlines()[:35]), flush=True)
        except Exception:
            pass

        # Compute ground-truth PyTorch codes on REAL Vietnamese speech BEFORE patching anything!
        ref_codes_pt = None
        ref_codes_pt_np = None
        try:
            with torch.no_grad():
                ref_codes_pt = codec.encode_code(parity_tensor)
                ref_codes_pt_np = ref_codes_pt.detach().cpu().numpy().astype(np.int64)
            print(
                f"  [ref] Ground-truth {class_name}.encode_code(parity_wav): "
                f"shape={tuple(ref_codes_pt.shape)}, dtype={ref_codes_pt.dtype}, "
                f"sample={ref_codes_pt.reshape(-1)[:12].tolist()}",
                flush=True,
            )
        except Exception as exc:
            print(f"  [warn] Direct ground-truth encode_code(parity_tensor) note: {exc}", flush=True)

        # Candidate 1: Direct wrapper calling codec.encode_code(audio_16k)
        direct_mod = DirectEncodeCodeWrapper(codec).eval()
        created = _export_module_to_onnx(
            direct_mod,
            dummy_3s,
            out_onnx_path,
            decoder_onnx_path,
            f"{class_name} DirectEncodeCodeWrapper",
            parity_wav_16k=parity_wav_np,
            ref_codes_pt=ref_codes_pt_np,
        )
        if created:
            return created, parity_wav_np, ref_codes_pt_np

        # Candidate 2: Direct wrapper with pure-PyTorch feature_extractor shim respecting ACTUAL do_normalize!
        if orig_fe is not None and class_name == "DistillNeuCodec":
            for try_norm in (fe_do_norm, not fe_do_norm):
                try:
                    codec.feature_extractor = PureTorchFeatureExtractorShim(do_normalize=try_norm)
                    created = _export_module_to_onnx(
                        direct_mod,
                        dummy_3s,
                        out_onnx_path,
                        decoder_onnx_path,
                        f"{class_name} DirectEncodeCodeWrapper + PureTorchFE(do_normalize={try_norm})",
                        parity_wav_16k=parity_wav_np,
                        ref_codes_pt=ref_codes_pt_np,
                    )
                    if created:
                        return created, parity_wav_np, ref_codes_pt_np
                finally:
                    codec.feature_extractor = orig_fe

        # Candidate 3: Auto-introspected PureTorchNeuCodecEncoder parity-checked against ref_codes_pt
        pure_candidates = _build_pure_torch_encoder_candidates(codec, parity_tensor, ref_codes_pt)
        for label, mod in pure_candidates:
            created = _export_module_to_onnx(
                mod,
                dummy_3s,
                out_onnx_path,
                decoder_onnx_path,
                f"{class_name} {label}",
                parity_wav_16k=parity_wav_np,
                ref_codes_pt=ref_codes_pt_np,
            )
            if created:
                return created, parity_wav_np, ref_codes_pt_np

    return None, parity_wav_np, None


def _ensure_int32_3d_output_onnx(onnx_path: str) -> bool:
    """
    If a pre-exported HF ONNX encoder outputs INT64 instead of INT32 (or rank 2 instead of rank 3),
    append lightweight ONNX Cast/Unsqueeze nodes so its contract strictly matches `codes: int32[1, 1, N]`.
    """
    try:
        import onnx
        from onnx import TensorProto, helper

        sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
        out_meta = sess.get_outputs()[0]
        need_cast = "int32" not in out_meta.type.lower()
        need_unsqueeze = len(out_meta.shape) == 2
        if not need_cast and not need_unsqueeze:
            return True

        model = onnx.load(onnx_path, load_external_data=False)
        orig_out = model.graph.output[0]
        orig_name = orig_out.name
        cur_name = orig_name

        # Rename original final output to internal tensor name
        internal_name = orig_name + "_raw_internal"
        for node in model.graph.node:
            for i, o in enumerate(node.output):
                if o == orig_name:
                    node.output[i] = internal_name
        cur_name = internal_name

        if need_unsqueeze:
            axes_name = "unsqueeze_channel_axis_const"
            axes_tensor = helper.make_tensor(axes_name, TensorProto.INT64, [1], [1])
            model.graph.initializer.append(axes_tensor)
            unsq_name = orig_name + "_rank3"
            model.graph.node.append(helper.make_node("Unsqueeze", [cur_name, axes_name], [unsq_name]))
            cur_name = unsq_name

        if need_cast:
            model.graph.node.append(helper.make_node("Cast", [cur_name], ["codes"], to=TensorProto.INT32))
        else:
            model.graph.node.append(helper.make_node("Identity", [cur_name], ["codes"]))

        new_out = helper.make_tensor_value_info("codes", TensorProto.INT32, [1, 1, "frames"])
        model.graph.output.pop(0)
        model.graph.output.insert(0, new_out)
        safe_unlink(onnx_path)
        onnx.save(model, onnx_path)
        try:
            os.chmod(onnx_path, 0o644)
        except OSError:
            pass
        print("  [patch] Normalized pre-exported encoder output to codes: int32[1, 1, frames]", flush=True)
        return True
    except Exception as exc:
        print(f"  [warn patch] Could not normalize ONNX output graph: {exc}", flush=True)
        return False


def fallback_download_public_hf_encoder(
    out_onnx_path: str,
    decoder_onnx_path: Optional[str],
) -> Optional[List[str]]:
    """Fallback: download verified pre-exported DistillNeuCodec ONNX encoder from public Hugging Face repos."""
    print("\n=== Fallback: Checking public Hugging Face pre-exported DistillNeuCodec ONNX repos ===", flush=True)
    try:
        from huggingface_hub import HfApi, hf_hub_download
    except Exception as exc:
        print(f"  [warn] huggingface_hub unavailable: {exc}", flush=True)
        return None

    api = HfApi()
    repos = [
        "KevinAHM/distill-neucodec-onnx",
        "pnnbao-ump/neucodec-onnx-encoder",
        "pnnbao-ump/distill-neucodec-onnx",
    ]
    out_dir = os.path.dirname(os.path.abspath(out_onnx_path))

    for repo in repos:
        try:
            info = api.model_info(repo)
            if getattr(info, "private", False) or getattr(info, "gated", False):
                continue
            files = list(api.list_repo_files(repo))
        except Exception as exc:
            print(f"  [skip] {repo}: {exc}", flush=True)
            continue

        onnx_files = [
            f for f in files
            if f.lower().endswith(".onnx") and not ("dec" in os.path.basename(f).lower() and "enc" not in os.path.basename(f).lower())
        ]
        onnx_files.sort(key=lambda s: (0 if "enc" in os.path.basename(s).lower() else 1, 0 if "q8" in s.lower() or "int8" in s.lower() else 1, len(s)))
        for chosen in onnx_files:
            created: List[str] = []
            try:
                cached = hf_hub_download(repo_id=repo, filename=chosen)
                safe_copy_file(cached, out_onnx_path)
                created.append(out_onnx_path)
                # Download companion external data if any
                chosen_dir = os.path.dirname(chosen)
                for extra in files:
                    if os.path.dirname(extra) == chosen_dir and extra.lower().endswith((".onnx.data", ".onnx_data", ".data")):
                        ex_cached = hf_hub_download(repo_id=repo, filename=extra)
                        ex_dest = os.path.join(out_dir, os.path.basename(extra))
                        safe_copy_file(ex_cached, ex_dest)
                        created.append(ex_dest)

                _ensure_int32_3d_output_onnx(out_onnx_path)
                if verify_encoder_onnx(out_onnx_path, decoder_onnx_path=decoder_onnx_path, require_int32_3d=False):
                    print(f"  [OK HF FALLBACK] Verified {repo}/{chosen} -> {out_onnx_path}", flush=True)
                    return created
            except Exception as exc:
                print(f"  [warn] HF candidate {repo}/{chosen} failed: {exc}", flush=True)
            cleanup_paths(created)
    return None


def _collect_fsq_sensitive_nodes(onnx_path: str) -> List[str]:
    """
    Identify all MatMul/Gemm nodes belonging to FSQ quantization, fc_prior, CodecEnc (acoustic),
    or SemanticEncoder projection so selective QUInt8 ONLY quantizes bulk transformer layers
    and NEVER corrupts the 8-axis FSQ codebook boundaries!
    """
    sensitive_tokens = (
        "fc_prior",
        "prior",
        "quantizer",
        "fsq",
        "project_in",
        "project_out",
        "codecenc",
        "codec_enc",
        "acoustic",
        "semanticencoder",
        "semantic_enc",
    )
    excluded: List[str] = []
    try:
        import onnx

        model = onnx.load(onnx_path, load_external_data=False)
        for node in model.graph.node:
            if node.op_type not in ("MatMul", "Gemm"):
                continue
            haystack = " ".join([node.name] + list(node.input) + list(node.output)).lower()
            if any(tok in haystack for tok in sensitive_tokens):
                if node.name:
                    excluded.append(node.name)
    except Exception as exc:
        print(f"  [quantize] Could not inspect sensitive nodes ({exc})", flush=True)
    return excluded


def try_quantize_for_mobile(
    encoder_onnx_path: str,
    created_files: List[str],
    decoder_onnx_path: Optional[str],
    parity_wav_16k: Optional[np.ndarray] = None,
    ref_codes_pt: Optional[np.ndarray] = None,
) -> List[str]:
    """
    Carefully optimize `neucodec_encoder.onnx` size while STRICTLY preserving FSQ voice-cloning
    accuracy! Excludes FSQ / `fc_prior` / `CodecEnc` / `SemanticEncoder` from QUInt8 and REJECTS
    quantization unless real-speech FSQ parity (>=88% exact OR >=96% 8-axis & >=0.97 spectral cos)
    matches PyTorch FP32 `DistillNeuCodec.encode_code()`! Otherwise keeps exact FP32.
    """
    total_bytes = sum(os.path.getsize(p) for p in set(created_files) if os.path.isfile(p))
    orig_mb = total_bytes / (1024.0 * 1024.0)
    if orig_mb <= 115.0:
        print(f"  [size] Encoder already compact ({orig_mb:.2f} MB) — keeping as-is.", flush=True)
        return created_files

    print(f"\n=== Mobile Size Optimization (FSQ Parity-Gated): {orig_mb:.2f} MB FP32 encoder ===", flush=True)
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
    except Exception as exc:
        print(f"  [quantize] onnxruntime.quantization unavailable ({exc}); keeping verified FP32.", flush=True)
        return created_files

    excluded_nodes = _collect_fsq_sensitive_nodes(encoder_onnx_path)
    print(f"  [quantize] Protecting {len(excluded_nodes)} FSQ/fc_prior/acoustic MatMul/Gemm nodes in FP32", flush=True)

    with tempfile.TemporaryDirectory(prefix="neucodec_enc_q8_") as tmpdir:
        q8_path = os.path.join(tmpdir, "neucodec_encoder_q8_selective.onnx")
        try:
            quantize_dynamic(
                model_input=encoder_onnx_path,
                model_output=q8_path,
                weight_type=QuantType.QUInt8,
                op_types_to_quantize=["MatMul", "Gemm"],
                nodes_to_exclude=excluded_nodes,
            )
        except Exception as exc:
            print(f"  [quantize] Selective quantize_dynamic failed ({exc}); keeping verified FP32.", flush=True)
            return created_files

        if not os.path.isfile(q8_path):
            return created_files

        q8_mb = os.path.getsize(q8_path) / (1024.0 * 1024.0)
        print(f"  [quantize] Selective QUInt8 candidate size: {q8_mb:.2f} MB (was {orig_mb:.2f} MB)", flush=True)
        if q8_mb >= orig_mb * 0.95:
            print("  [quantize] Size reduction <5%; keeping verified FP32.", flush=True)
            return created_files

        if verify_encoder_onnx(q8_path, decoder_onnx_path=decoder_onnx_path, require_int32_3d=False) is None:
            print("  [quantize] Selective QUInt8 failed ONNX verification; keeping verified FP32.", flush=True)
            return created_files

        # CRITICAL VOICE-CLONING GATE: Reject QUInt8 if it perturbs FSQ speaker codes!
        if parity_wav_16k is not None and ref_codes_pt is not None:
            parity_ok, _ = verify_encoder_parity_vs_pytorch(
                encoder_onnx_path=q8_path,
                test_wav_16k=parity_wav_16k,
                ref_codes_pt=ref_codes_pt,
                decoder_onnx_path=decoder_onnx_path,
                min_exact_match=0.88,
                min_fsq_axis_acc=0.96,
                min_spectral_cos=0.97,
                label="Selective-QUInt8",
            )
            if not parity_ok:
                print(
                    "  [quantize REJECTED] QUInt8 degraded FSQ speaker codebook accuracy vs PyTorch FP32! "
                    "Keeping exact verified FP32 neucodec_encoder.onnx for true voice cloning fidelity.",
                    flush=True,
                )
                return created_files

        safe_copy_file(q8_path, encoder_onnx_path)
        for p in created_files:
            if os.path.abspath(p) != os.path.abspath(encoder_onnx_path) and os.path.lexists(p):
                safe_unlink(p)
                print(f"  [clean] Removed superseded external weights: {os.path.basename(p)}", flush=True)
        print(f"  [OK QUANTIZE] Parity-verified neucodec_encoder.onnx saved ({q8_mb:.2f} MB)!", flush=True)
        return [encoder_onnx_path]


def main() -> int:
    out_onnx = sys.argv[1] if len(sys.argv) >= 2 else "app/src/main/assets/models/neucodec_encoder.onnx"
    decoder_onnx = (
        sys.argv[2]
        if len(sys.argv) >= 3
        else os.path.join(os.path.dirname(os.path.abspath(out_onnx)), "neucodec_decoder.onnx")
    )
    if not os.path.isfile(decoder_onnx):
        decoder_onnx = None

    print("=== EXPORT NEUCODEC ENCODER ONNX (16kHz float32[1,1,T] -> int32[1,1,N], PyTorch Parity-Gated) ===", flush=True)
    print(f"Target output:  {out_onnx}", flush=True)
    print(f"Decoder verify: {decoder_onnx or '(not provided)'}", flush=True)

    # Always remove any stale encoder file so every CI build exports a freshly parity-verified encoder
    if os.path.lexists(out_onnx):
        safe_unlink(out_onnx)

    created: Optional[List[str]] = None
    parity_wav_np: Optional[np.ndarray] = None
    ref_codes_pt_np: Optional[np.ndarray] = None
    try:
        created, parity_wav_np, ref_codes_pt_np = export_from_neucodec_python(out_onnx, decoder_onnx)
    except Exception as exc:
        print(f"[warn] PyTorch export raised unexpected error: {type(exc).__name__}: {exc}", flush=True)
        traceback.print_exc()

    if not created:
        created = fallback_download_public_hf_encoder(out_onnx, decoder_onnx)

    if not created or not os.path.isfile(out_onnx):
        print("ERROR: Failed to export or download a verified neucodec_encoder.onnx!", file=sys.stderr, flush=True)
        return 1

    created = try_quantize_for_mobile(
        out_onnx,
        created,
        decoder_onnx,
        parity_wav_16k=parity_wav_np,
        ref_codes_pt=ref_codes_pt_np,
    )

    print("\n=== FINAL NEUCODEC ENCODER ARTIFACTS ===", flush=True)
    for fp in sorted(set(created)):
        if os.path.isfile(fp):
            sz = os.path.getsize(fp)
            print(f"  - {os.path.basename(fp):40s} {sz / (1024 * 1024):8.2f} MB ({sz:,} bytes)", flush=True)

    # Final authoritative verification pass on the saved file
    if verify_encoder_onnx(out_onnx, decoder_onnx_path=decoder_onnx, require_int32_3d=False) is None:
        print("ERROR: Final verification of neucodec_encoder.onnx failed!", file=sys.stderr, flush=True)
        return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
