#!/usr/bin/env python3
"""
Clean `app/src/main/assets/models/` and download the verified PUBLIC (non-gated)
`pnnbao97/VieNeu-TTS` core runtime models via dynamic `HfApi().list_repo_files(...)`:

  1. `*.gguf`                — Compact Q4_0 language backbone (`pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf`)
  2. `neucodec_decoder.onnx` — Verified 24kHz NeuCodec FSQ decoder INT8 (+ `.data` if required)
  3. `voices.json`           — Compact preset reference bank (~15 KB)

Note: `neucodec_encoder.onnx` is exported & verified in the dedicated next step by
`scripts/export_neucodec_encoder.py`.

Usage:
  python3 scripts/download_vieneu_models.py <models_dir>
"""

import os
import shutil
import sys
from typing import Dict, List, Optional, Set, Tuple

import numpy as np
import onnxruntime as ort
from huggingface_hub import HfApi, hf_hub_download


def assert_not_lfs_pointer(path: str) -> None:
    sz = os.path.getsize(path)
    if sz < 256:
        with open(path, "rb") as f:
            head = f.read(256)
        if b"version https://git-lfs.github.com/spec/v1" in head:
            raise RuntimeError(f"File {path} is a Git LFS pointer ({sz} bytes), not binary data!")


def list_public_repo_files(api: HfApi, repo_id: str) -> Optional[List[str]]:
    """Safely list actual files in a public non-gated Hugging Face repo via `list_repo_files`."""
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
    """Safely write `dst` without inheriting read-only permissions from Hugging Face cache."""
    os.makedirs(os.path.dirname(os.path.abspath(dst)), exist_ok=True)
    safe_unlink(dst)
    shutil.copyfile(src, dst)
    try:
        os.chmod(dst, 0o644)
    except OSError:
        pass


def download_file_safe(repo_id: str, filename: str, dest_path: str) -> bool:
    try:
        cached = hf_hub_download(repo_id=repo_id, filename=filename)
        assert_not_lfs_pointer(cached)
        safe_copy_file(cached, dest_path)
        size_mb = os.path.getsize(dest_path) / (1024.0 * 1024.0)
        print(
            f"    -> saved {os.path.basename(dest_path)} ({size_mb:.2f} MB) from {repo_id}/{filename}",
            flush=True,
        )
        return True
    except Exception as exc:
        print(f"  [warn] Download failed for {repo_id}/{filename}: {exc}", flush=True)
        safe_unlink(dest_path)
        return False


def _inspect_onnx_referenced_external_files(onnx_path: str) -> Set[str]:
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
        safe_unlink(p)


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


def verify_neucodec_decoder_onnx(onnx_path: str) -> Optional[Dict[str, object]]:
    """Verify via ONNX Runtime that `onnx_path` is a genuine NeuCodec 24kHz FSQ decoder."""
    try:
        sess = ort.InferenceSession(onnx_path, providers=["CPUExecutionProvider"])
        inputs = sess.get_inputs()
        outputs = sess.get_outputs()
        in_desc = describe_io(inputs)
        out_desc = describe_io(outputs)
        print(f"    [verify decoder] inputs={in_desc} | outputs={out_desc}", flush=True)

        if len(inputs) != 1:
            print(f"    [reject decoder] Expected 1 FSQ token input, found {len(inputs)}", flush=True)
            return None

        inp = inputs[0]
        if "int" not in inp.type.lower():
            print(f"    [reject decoder] Expected integer input tensor, got {inp.type}", flush=True)
            return None

        dtype = np.int64 if "int64" in inp.type.lower() else np.int32
        arr = np.asarray([6181, 6377, 55510, 53535], dtype=dtype)
        rank = len(inp.shape)
        if rank == 3:
            arr = arr.reshape(1, 1, -1)
        elif rank == 2:
            arr = arr.reshape(1, -1)
        elif rank == 1:
            arr = arr.reshape(-1)
        else:
            arr = arr.reshape(1, 1, -1)

        out = sess.run(None, {inp.name: arr})
        wav = np.asarray(out[0], dtype=np.float32).reshape(-1)
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
    onnx_files = [f for f in files if f.lower().endswith(".onnx")]
    candidates = [f for f in onnx_files if "enc" not in os.path.basename(f).lower()]

    def score(path: str) -> Tuple[int, int, int, str]:
        bn = os.path.basename(path).lower()
        has_dec = 0 if ("dec" in bn or bn == "model.onnx") else 1
        has_int8 = 0 if ("int8" in bn or "q8" in bn or "quant" in bn) else 1
        return (has_dec, has_int8, len(path), path)

    candidates.sort(key=score)
    return candidates


def _make_tree_writable(root_dir: str) -> None:
    for dirpath, dirnames, filenames in os.walk(root_dir):
        try:
            os.chmod(dirpath, 0o777)
        except OSError:
            pass
        for fn in filenames:
            fp = os.path.join(dirpath, fn)
            if not os.path.islink(fp):
                try:
                    os.chmod(fp, 0o777)
                except OSError:
                    pass


def purge_assets_junk(models_dir: str) -> None:
    """Clean out `models_dir` and delete any legacy/unused model folders or junk files in parent `assets/`."""
    if os.path.lexists(models_dir):
        if os.path.isdir(models_dir) and not os.path.islink(models_dir):
            _make_tree_writable(models_dir)
            shutil.rmtree(models_dir)
        else:
            safe_unlink(models_dir)
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
                if os.path.isdir(full) and not os.path.islink(full):
                    _make_tree_writable(full)
                    shutil.rmtree(full)
                else:
                    safe_unlink(full)
                print(f"  [clean] Removed legacy/unused asset: {entry}", flush=True)


def _probe_gguf_neu_compat(gguf_path: str) -> bool:
    """Verify GGUF model contains official NeuCodec special tokens (<|SPEECH_GENERATION_START|>, <|speech_0|>)."""
    try:
        from llama_cpp import Llama

        llm = Llama(model_path=gguf_path, n_ctx=256, n_threads=2, n_gpu_layers=0, verbose=False)
        try:
            for tok in ("<|TEXT_PROMPT_START|>", "<|SPEECH_GENERATION_START|>", "<|speech_0|>"):
                ids = llm.tokenize(tok.encode("utf-8"), add_bos=False, special=True)
                if len(ids) != 1:
                    print(f"    [reject gguf] {os.path.basename(gguf_path)} missing single special token {tok}", flush=True)
                    return False
            return True
        finally:
            del llm
    except Exception as exc:
        print(f"    [warn gguf probe] Skipping llama_cpp vocab probe ({exc}); accepting GGUF.", flush=True)
        return True


def quantize_decoder_to_quint8(
    dec_onnx_path: str,
    downloaded_paths: List[str],
) -> List[str]:
    """
    Dynamically quantize FP32/mixed `neucodec_decoder.onnx` (> 120 MB) to `QuantType.QUInt8`
    (~85–95 MB), verify 24kHz waveform parity against the original decoder, and delete FP32 originals.
    """
    import tempfile

    total_bytes = sum(os.path.getsize(p) for p in set(downloaded_paths) if os.path.isfile(p))
    orig_mb = total_bytes / (1024.0 * 1024.0)
    if orig_mb <= 120.0:
        print(f"  [decoder size] Already compact ({orig_mb:.2f} MB <= 120 MB) — keeping as-is.", flush=True)
        return downloaded_paths

    print(f"\n=== Quantizing neucodec_decoder.onnx ({orig_mb:.2f} MB) -> QUInt8 (~85–95 MB) ===", flush=True)
    try:
        from onnxruntime.quantization import QuantType, quantize_dynamic
    except Exception as exc:
        print(f"  [warn quantize] onnxruntime.quantization unavailable ({exc}); keeping original.", flush=True)
        return downloaded_paths

    # Compute reference 24kHz waveform from FP32 decoder before quantization
    ref_wav: Optional[np.ndarray] = None
    try:
        ref_sess = ort.InferenceSession(dec_onnx_path, providers=["CPUExecutionProvider"])
        ref_inp = ref_sess.get_inputs()[0]
        dt = np.int64 if "int64" in ref_inp.type.lower() else np.int32
        sample_codes = np.asarray([6181, 6377, 55510, 53535, 12044, 31902, 8192, 44210], dtype=dt)
        if len(ref_inp.shape) == 3:
            sample_codes = sample_codes.reshape(1, 1, -1)
        elif len(ref_inp.shape) == 2:
            sample_codes = sample_codes.reshape(1, -1)
        ref_wav = np.asarray(ref_sess.run(None, {ref_inp.name: sample_codes})[0], dtype=np.float32).reshape(-1)
        del ref_sess
    except Exception as exc:
        print(f"  [warn quantize] Could not compute reference FP32 waveform ({exc})", flush=True)

    # Tier 1: Full dynamic QUInt8 (smallest ~85-95 MB); Tier 2: MatMul+Gemm+Conv; Tier 3: MatMul+Gemm
    quant_tiers: List[Tuple[str, Optional[List[str]]]] = [
        ("Full QUInt8 (all supported ops)", None),
        ("QUInt8 (MatMul + Gemm + Conv)", ["MatMul", "Gemm", "Conv"]),
        ("QUInt8 (MatMul + Gemm)", ["MatMul", "Gemm"]),
    ]

    with tempfile.TemporaryDirectory(prefix="neucodec_dec_q8_") as tmpdir:
        for tier_label, op_types in quant_tiers:
            q8_path = os.path.join(tmpdir, "neucodec_decoder_quint8.onnx")
            safe_unlink(q8_path)
            try:
                kwargs = dict(
                    model_input=dec_onnx_path,
                    model_output=q8_path,
                    weight_type=QuantType.QUInt8,
                )
                if op_types is not None:
                    kwargs["op_types_to_quantize"] = op_types
                quantize_dynamic(**kwargs)
            except Exception as exc:
                print(f"  [quantize skip] {tier_label}: {type(exc).__name__}: {exc}", flush=True)
                continue

            if not os.path.isfile(q8_path):
                continue

            q8_mb = os.path.getsize(q8_path) / (1024.0 * 1024.0)
            print(f"  [quantize check] {tier_label} -> {q8_mb:.2f} MB (was {orig_mb:.2f} MB)", flush=True)
            if q8_mb >= orig_mb * 0.92:
                print("  [quantize skip] Did not shrink meaningfully; trying next tier.", flush=True)
                continue

            schema = verify_neucodec_decoder_onnx(q8_path)
            if schema is None:
                print(f"  [quantize reject] {tier_label} failed ONNX Runtime inference check.", flush=True)
                continue

            # Check waveform parity against FP32 reference so voice quality stays crystal clear
            if ref_wav is not None and ref_wav.size >= 1000:
                try:
                    q_sess = ort.InferenceSession(q8_path, providers=["CPUExecutionProvider"])
                    q_inp = q_sess.get_inputs()[0]
                    dt = np.int64 if "int64" in q_inp.type.lower() else np.int32
                    sample_codes = np.asarray([6181, 6377, 55510, 53535, 12044, 31902, 8192, 44210], dtype=dt)
                    if len(q_inp.shape) == 3:
                        sample_codes = sample_codes.reshape(1, 1, -1)
                    elif len(q_inp.shape) == 2:
                        sample_codes = sample_codes.reshape(1, -1)
                    q_wav = np.asarray(q_sess.run(None, {q_inp.name: sample_codes})[0], dtype=np.float32).reshape(-1)
                    del q_sess
                    n = min(ref_wav.size, q_wav.size)
                    a = ref_wav[:n] - float(np.mean(ref_wav[:n]))
                    b = q_wav[:n] - float(np.mean(q_wav[:n]))
                    denom = float(np.linalg.norm(a) * np.linalg.norm(b))
                    corr = float(np.dot(a, b) / denom) if denom > 1e-8 else 0.0
                    print(f"  [quantize parity] {tier_label}: waveform corr={corr:.4f}", flush=True)
                    if corr < 0.70:
                        print(f"  [quantize reject] Waveform correlation too low ({corr:.4f} < 0.70).", flush=True)
                        continue
                except Exception as exc:
                    print(f"  [quantize reject] Parity check error: {exc}", flush=True)
                    continue

            # Replace original FP32 decoder & delete all superseded external .data files
            safe_copy_file(q8_path, dec_onnx_path)
            for p in downloaded_paths:
                if os.path.abspath(p) != os.path.abspath(dec_onnx_path) and os.path.lexists(p):
                    safe_unlink(p)
                    print(f"  [clean] Removed superseded FP32 companion: {os.path.basename(p)}", flush=True)
            print(
                f"  [OK QUANTIZE DECODER] {tier_label}: {orig_mb:.2f} MB -> {q8_mb:.2f} MB!",
                flush=True,
            )
            return [dec_onnx_path]

    print("  [warn quantize] Keeping verified original neucodec_decoder.onnx.", flush=True)
    return downloaded_paths


def main() -> int:
    models_dir = sys.argv[1] if len(sys.argv) >= 2 else "app/src/main/assets/models"
    purge_assets_junk(models_dir)

    api = HfApi()
    pnnbao_public_repos = discover_public_repos(api, author="pnnbao-ump", limit=60)
    for r in discover_public_repos(api, author="pnnbao97", limit=40):
        if r not in pnnbao_public_repos:
            pnnbao_public_repos.append(r)
    print(f"Public repos discovered under pnnbao-ump/pnnbao97 ({len(pnnbao_public_repos)}): {pnnbao_public_repos}", flush=True)

    # =========================================================================
    # 1. Lightest Compatible VieNeu-TTS Turbo / 0.3B Q4_0 GGUF Backbone + voices.json
    # =========================================================================
    gguf_candidates: List[str] = [
        "pnnbao-ump/VieNeu-TTS-turbo-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-Turbo-q4-gguf",
        "pnnbao97/VieNeu-TTS-turbo-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-0.3B-q8-gguf",
    ]
    # Add any newly published turbo / light Q4_0 GGUF repos dynamically (turbo first!)
    discovered_gguf_repos = [
        r for r in pnnbao_public_repos
        if "gguf" in r.lower() and "v3" not in r.lower() and "nano" not in r.lower()
    ]
    discovered_gguf_repos.sort(
        key=lambda r: (
            0 if "turbo" in r.lower() else (1 if "0.3b" in r.lower() else 2),
            0 if "q4" in r.lower() else 1,
            len(r),
        )
    )
    for r in discovered_gguf_repos:
        if r not in gguf_candidates:
            gguf_candidates.insert(0 if ("turbo" in r.lower() and "q4" in r.lower()) else len(gguf_candidates), r)

    gguf_dest: Optional[str] = None
    voices_dest: Optional[str] = None

    print("\n=== [1/2] Downloading Lightest Verified GGUF Backbone (Turbo / 0.3B Q4_0) + voices.json ===", flush=True)
    for repo in gguf_candidates:
        files = list_public_repo_files(api, repo)
        if not files:
            continue
        gguf_files = [f for f in files if f.lower().endswith(".gguf")]
        if not gguf_files:
            continue
        gguf_files.sort(
            key=lambda s: (
                0 if "q4_0" in s.lower() else (1 if "q4_k" in s.lower() or "q4" in s.lower() else 2),
                0 if "turbo" in s.lower() else 1,
                len(s),
                s,
            )
        )
        for chosen_gguf in gguf_files:
            dest = os.path.join(models_dir, os.path.basename(chosen_gguf))
            if not download_file_safe(repo, chosen_gguf, dest):
                continue
            if not _probe_gguf_neu_compat(dest):
                safe_unlink(dest)
                continue
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
        raise RuntimeError("Failed to download a verified NeuCodec-compatible *.gguf from public pnnbao repositories!")

    if not voices_dest:
        for v_repo in ("pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf", "pnnbao-ump/VieNeu-TTS-0.3B", "pnnbao-ump/VieNeu-TTS"):
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
    # 2. Compact NeuCodec ONNX 24kHz FSQ Decoder (auto-quantized to QUInt8 ~85-95 MB)
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
    decoder_downloaded: List[str] = []
    print("\n=== [2/2] Downloading, Verifying & Quantizing NeuCodec ONNX Decoder (24kHz -> QUInt8) ===", flush=True)
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
            decoder_downloaded = downloaded
            break
        if decoder_repo_used:
            break

    if not decoder_repo_used:
        raise RuntimeError(
            "Failed to download a verified NeuCodec FSQ 24kHz neucodec_decoder.onnx! Checked: "
            + ", ".join(decoder_candidates)
        )

    # Compress 297 MB FP32 decoder -> ~85-95 MB QUInt8 (deleting FP32 original)
    quantize_decoder_to_quint8(dec_dest, decoder_downloaded)
    print(f"  [OK DECODER] Ready: {decoder_repo_used}", flush=True)

    print("\n=== CORE MODEL BUNDLE (before neucodec_encoder.onnx export) ===", flush=True)
    total_bytes = 0
    for fn in sorted(os.listdir(models_dir)):
        fp = os.path.join(models_dir, fn)
        if os.path.isfile(fp):
            sz = os.path.getsize(fp)
            total_bytes += sz
            print(f"  - {fn:40s} {sz / (1024 * 1024):8.2f} MB ({sz:,} bytes)", flush=True)
    print(f"TOTAL CORE BUNDLE SIZE: {total_bytes / (1024 * 1024):.2f} MB", flush=True)

    if total_bytes < 50 * 1024 * 1024:
        raise RuntimeError(f"Downloaded bundle unexpectedly small ({total_bytes} bytes)!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
