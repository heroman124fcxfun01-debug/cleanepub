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


def main() -> int:
    models_dir = sys.argv[1] if len(sys.argv) >= 2 else "app/src/main/assets/models"
    purge_assets_junk(models_dir)

    api = HfApi()
    pnnbao_public_repos = discover_public_repos(api, author="pnnbao-ump", limit=60)
    print(f"Public repos discovered under pnnbao-ump ({len(pnnbao_public_repos)}): {pnnbao_public_repos}", flush=True)

    # =========================================================================
    # 1. Official VieNeu-TTS-0.3B Q4_0 GGUF Backbone + voices.json
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

    print("\n=== [1/2] Downloading Compact GGUF Backbone (Q4_0) + voices.json ===", flush=True)
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
    # 2. Compact NeuCodec ONNX 24kHz FSQ Decoder
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
    print("\n=== [2/2] Downloading & Verifying Compact NeuCodec ONNX Decoder (24kHz) ===", flush=True)
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
