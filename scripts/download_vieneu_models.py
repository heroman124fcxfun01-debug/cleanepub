#!/usr/bin/env python3
"""
Download official PUBLIC (non-gated) `pnnbao97/VieNeu-TTS` models into `app/src/main/assets/models/`:
1. GGUF backbone (`pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf`, fallback `pnnbao-ump/VieNeu-TTS-q4-gguf`) + `voices.json`
2. Public NeuCodec ONNX 24kHz Decoder (`pnnbao-ump/neucodec-onnx-decoder-int8`, fallback `pnnbao-ump/neucodec-onnx-decoder`,
   `pnnbao-ump/VieNeu-Codec`, or dynamically discovered public non-gated HF mirrors)
3. Public NeuCodec ONNX 16kHz Encoder (`pnnbao-ump/neucodec-onnx-encoder`, `pnnbao-ump/distill-neucodec-onnx`,
   `pnnbao-ump/VieNeu-Codec`, `pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf`, `KevinAHM/distill-neucodec-onnx`,
   or dynamically discovered public non-gated HF repos)

Never relies on gated `neuphonic/*` repos that require Hugging Face login.

Usage:
  python3 scripts/download_vieneu_models.py <models_dir>
"""

import json
import os
import shutil
import sys
from typing import List, Optional, Tuple

from huggingface_hub import HfApi, hf_hub_download


def assert_not_lfs_pointer(path: str) -> None:
    sz = os.path.getsize(path)
    if sz < 256:
        with open(path, "rb") as f:
            head = f.read(256)
        if b"version https://git-lfs.github.com/spec/v1" in head:
            raise RuntimeError(f"File {path} is a Git LFS pointer ({sz} bytes), not binary data!")


def inspect_public_repo_files(api: HfApi, repo_id: str) -> Optional[List[str]]:
    """
    Check Hugging Face API for `repo_id`.
    Immediately skips private or gated repos (`gated != False`) so CI never fails with 401 GatedRepoError.
    Returns the exact list of file paths inside the repo if publicly accessible.
    """
    try:
        info = api.model_info(repo_id)
    except Exception as exc:
        print(f"  [skip] Cannot inspect {repo_id}: {exc}", flush=True)
        return None

    if getattr(info, "private", False):
        print(f"  [skip] Repo {repo_id} is private", flush=True)
        return None
    if getattr(info, "gated", False):
        print(f"  [skip] Repo {repo_id} is gated (requires HF login)", flush=True)
        return None

    siblings = getattr(info, "siblings", None) or []
    files = [s.rfilename for s in siblings if getattr(s, "rfilename", None)]
    if not files:
        try:
            files = api.list_repo_files(repo_id)
        except Exception as exc:
            print(f"  [skip] Cannot list files in {repo_id}: {exc}", flush=True)
            return None

    print(f"  [public] {repo_id}: {len(files)} files -> {files}", flush=True)
    return files


def download_file_safe(repo_id: str, filename: str, dest_path: str) -> bool:
    try:
        cached = hf_hub_download(repo_id=repo_id, filename=filename)
        assert_not_lfs_pointer(cached)
        os.makedirs(os.path.dirname(os.path.abspath(dest_path)), exist_ok=True)
        shutil.copy2(cached, dest_path)
        size_mb = os.path.getsize(dest_path) / (1024.0 * 1024.0)
        print(f"    -> saved {os.path.basename(dest_path)} ({size_mb:.2f} MB) from {repo_id}/{filename}", flush=True)
        return True
    except Exception as exc:
        print(f"  [warn] Download failed for {repo_id}/{filename}: {exc}", flush=True)
        if os.path.exists(dest_path):
            try:
                os.remove(dest_path)
            except OSError:
                pass
        return False


def find_external_data_files(chosen_onnx: str, all_files: List[str]) -> List[str]:
    """
    Find companion ONNX external data files (`.data`, `.onnx_data`, `.onnx.data`)
    living alongside `chosen_onnx` in the repository.
    """
    chosen_dir = os.path.dirname(chosen_onnx)
    chosen_base = os.path.basename(chosen_onnx)
    chosen_stem = os.path.splitext(chosen_base)[0]
    same_dir_onnx = [
        f for f in all_files
        if os.path.dirname(f) == chosen_dir and f.lower().endswith(".onnx")
    ]

    companions: List[str] = []
    for f in all_files:
        if os.path.dirname(f) != chosen_dir:
            continue
        bn = os.path.basename(f)
        low = bn.lower()
        is_ext_data = (
            low.endswith(".onnx.data")
            or low.endswith(".onnx_data")
            or low.endswith(".data")
        )
        if not is_ext_data:
            continue
        # Match if filename starts with the chosen ONNX stem/name OR it is the only ONNX in this folder
        if bn.startswith(chosen_base) or bn.startswith(chosen_stem) or len(same_dir_onnx) == 1:
            companions.append(f)
    return companions


def download_onnx_with_companions(
    repo_id: str,
    chosen_onnx: str,
    all_files: List[str],
    dest_onnx_path: str,
    models_dir: str,
) -> bool:
    """Download an ONNX graph plus any companion external `.data` / `.onnx_data` weight files."""
    if not download_file_safe(repo_id, chosen_onnx, dest_onnx_path):
        return False

    companions = find_external_data_files(chosen_onnx, all_files)
    downloaded_companions: List[str] = []
    for extra in companions:
        extra_dest = os.path.join(models_dir, os.path.basename(extra))
        if not download_file_safe(repo_id, extra, extra_dest):
            # Clean up partial files so next candidate repo can be tried cleanly
            for p in [dest_onnx_path] + downloaded_companions:
                if os.path.exists(p):
                    try:
                        os.remove(p)
                    except OSError:
                        pass
            return False
        downloaded_companions.append(extra_dest)
    return True


def discover_public_repos(api: HfApi, author: Optional[str] = None, search: Optional[str] = None, limit: int = 25) -> List[str]:
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


def pick_best_decoder_onnx(files: List[str]) -> Optional[str]:
    onnx_files = [f for f in files if f.lower().endswith(".onnx")]
    if not onnx_files:
        return None
    # Exclude explicit encoder-only files
    candidates = [f for f in onnx_files if "enc" not in os.path.basename(f).lower()] or []
    if not candidates:
        return None
    # Prefer int8 quantized decoder (smaller & faster on Android), then any decoder, then model.onnx
    def score(path: str) -> Tuple[int, int, int, str]:
        bn = os.path.basename(path).lower()
        has_dec = 0 if ("dec" in bn or bn == "model.onnx") else 1
        has_int8 = 0 if ("int8" in bn or "q8" in bn or "quant" in bn) else 1
        return (has_dec, has_int8, len(path), path)

    candidates.sort(key=score)
    return candidates[0]


def pick_best_encoder_onnx(files: List[str], require_enc_keyword: bool) -> Optional[str]:
    onnx_files = [f for f in files if f.lower().endswith(".onnx")]
    if not onnx_files:
        return None
    enc_files = [
        f for f in onnx_files
        if "enc" in os.path.basename(f).lower() and "dec" not in os.path.basename(f).lower()
    ]
    if enc_files:
        enc_files.sort(
            key=lambda s: (
                0 if ("int8" in s.lower() or "quant" in s.lower() or "q8" in s.lower()) else 1,
                len(s),
                s,
            )
        )
        return enc_files[0]
    if require_enc_keyword:
        return None
    # Only allow generic `model.onnx` when repo itself is explicitly an encoder repo
    non_dec = [f for f in onnx_files if "dec" not in os.path.basename(f).lower()]
    return non_dec[0] if non_dec else None


def main() -> int:
    models_dir = sys.argv[1] if len(sys.argv) >= 2 else "app/src/main/assets/models"
    if os.path.exists(models_dir):
        shutil.rmtree(models_dir)
    os.makedirs(models_dir, exist_ok=True)

    api = HfApi()
    pnnbao_public_repos = discover_public_repos(api, author="pnnbao-ump", limit=50)
    print(f"Public repos discovered under pnnbao-ump ({len(pnnbao_public_repos)}): {pnnbao_public_repos}", flush=True)

    # =========================================================================
    # 1. Official VieNeu-TTS GGUF Backbone + voices.json (Public `pnnbao-ump`)
    # =========================================================================
    gguf_candidates: List[str] = [
        "pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-0.3B-q8-gguf",
    ]
    for r in pnnbao_public_repos:
        if "gguf" in r.lower() and r not in gguf_candidates:
            gguf_candidates.append(r)

    gguf_repo_used: Optional[str] = None
    gguf_dest: Optional[str] = None
    voices_dest: Optional[str] = None

    print("\n=== [1/3] Downloading GGUF Backbone + voices.json ===", flush=True)
    for repo in gguf_candidates:
        files = inspect_public_repo_files(api, repo)
        if not files:
            continue
        gguf_files = [f for f in files if f.lower().endswith(".gguf")]
        if not gguf_files:
            continue
        gguf_files.sort(key=lambda s: (0 if "q4" in s.lower() else 1, len(s), s))
        chosen_gguf = gguf_files[0]
        dest = os.path.join(models_dir, os.path.basename(chosen_gguf))
        if not download_file_safe(repo, chosen_gguf, dest):
            continue
        gguf_dest = dest
        gguf_repo_used = repo

        voices_files = [f for f in files if os.path.basename(f).lower() == "voices.json"]
        if voices_files:
            v_dest = os.path.join(models_dir, "voices.json")
            if download_file_safe(repo, voices_files[0], v_dest):
                voices_dest = v_dest
        break

    if not gguf_dest:
        raise RuntimeError("Failed to download *.gguf from public pnnbao-ump repositories!")

    if not voices_dest:
        voice_fallback_repos = [
            "pnnbao-ump/VieNeu-TTS-0.3B",
            "pnnbao-ump/VieNeu-TTS",
            "pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf",
        ]
        for v_repo in voice_fallback_repos:
            files = inspect_public_repo_files(api, v_repo) or []
            v_files = [f for f in files if os.path.basename(f).lower() == "voices.json"]
            if v_files:
                v_dest = os.path.join(models_dir, "voices.json")
                if download_file_safe(v_repo, v_files[0], v_dest):
                    voices_dest = v_dest
                    break

    # =========================================================================
    # 2. Public NeuCodec ONNX 24kHz Decoder (NO gated neuphonic repos!)
    # =========================================================================
    decoder_candidates: List[str] = [
        "pnnbao-ump/neucodec-onnx-decoder-int8",
        "pnnbao-ump/neucodec-onnx-decoder",
        "pnnbao-ump/VieNeu-Codec",
    ]
    for r in pnnbao_public_repos:
        low = r.lower()
        if ("neucodec" in low or "codec" in low or "decoder" in low) and r not in decoder_candidates:
            decoder_candidates.append(r)
    for r in discover_public_repos(api, search="neucodec-onnx-decoder", limit=20):
        if not r.lower().startswith("neuphonic/") and r not in decoder_candidates:
            decoder_candidates.append(r)

    decoder_repo_used: Optional[str] = None
    decoder_file_used: Optional[str] = None
    print("\n=== [2/3] Downloading Public NeuCodec ONNX Decoder (24kHz) ===", flush=True)
    for repo in decoder_candidates:
        files = inspect_public_repo_files(api, repo)
        if not files:
            continue
        chosen_dec = pick_best_decoder_onnx(files)
        if not chosen_dec:
            continue
        dec_dest = os.path.join(models_dir, "neucodec_decoder.onnx")
        if download_onnx_with_companions(repo, chosen_dec, files, dec_dest, models_dir):
            decoder_repo_used = repo
            decoder_file_used = chosen_dec
            break

    if not decoder_repo_used:
        raise RuntimeError(
            "Failed to download public neucodec_decoder.onnx! Checked candidates: "
            + ", ".join(decoder_candidates)
        )

    # =========================================================================
    # 3. Public NeuCodec ONNX 16kHz Encoder (for Offline Voice Cloning)
    # =========================================================================
    encoder_candidates: List[Tuple[str, bool]] = [
        # (repo_id, require_enc_keyword_in_filename)
        ("pnnbao-ump/neucodec-onnx-encoder", False),
        ("pnnbao-ump/distill-neucodec-onnx", False),
        ("pnnbao-ump/VieNeu-Codec", True),
        ("pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf", True),
        ("KevinAHM/distill-neucodec-onnx", False),
    ]
    seen_enc = {r for r, _ in encoder_candidates}
    for r in pnnbao_public_repos:
        low = r.lower()
        if ("neucodec" in low or "codec" in low or "encoder" in low) and r not in seen_enc:
            encoder_candidates.append((r, "encoder" not in low))
            seen_enc.add(r)
    for r in discover_public_repos(api, search="distill-neucodec-onnx", limit=15):
        if not r.lower().startswith("neuphonic/") and r not in seen_enc:
            encoder_candidates.append((r, False))
            seen_enc.add(r)
    for r in discover_public_repos(api, search="neucodec-onnx-encoder", limit=15):
        if not r.lower().startswith("neuphonic/") and r not in seen_enc:
            encoder_candidates.append((r, False))
            seen_enc.add(r)

    encoder_repo_used: Optional[str] = None
    encoder_file_used: Optional[str] = None
    print("\n=== [3/3] Downloading Public NeuCodec ONNX Encoder (16kHz Voice Cloning) ===", flush=True)
    for repo, req_kw in encoder_candidates:
        files = inspect_public_repo_files(api, repo)
        if not files:
            continue
        chosen_enc = pick_best_encoder_onnx(files, require_enc_keyword=req_kw)
        if not chosen_enc:
            continue
        enc_dest = os.path.join(models_dir, "neucodec_encoder.onnx")
        if download_onnx_with_companions(repo, chosen_enc, files, enc_dest, models_dir):
            encoder_repo_used = repo
            encoder_file_used = chosen_enc
            break

    if not encoder_repo_used:
        raise RuntimeError(
            "Failed to download public neucodec_encoder.onnx! Checked candidates: "
            + ", ".join(r for r, _ in encoder_candidates)
        )

    # =========================================================================
    # 4. Write manifest.json & summary
    # =========================================================================
    manifest = {
        "version": "vieneu-gguf-neucodec-2.1",
        "architecture": "pnnbao97/VieNeu-TTS",
        "sampleRate": 24000,
        "encoderSampleRate": 16000,
        "sources": {
            "gguf": gguf_repo_used,
            "decoder": f"{decoder_repo_used}/{decoder_file_used}",
            "encoder": f"{encoder_repo_used}/{encoder_file_used}",
        },
        "files": sorted(set(os.listdir(models_dir) + ["vi_espeak_ipa.tsv", "manifest.json"])),
    }
    with open(os.path.join(models_dir, "manifest.json"), "w", encoding="utf-8") as mf:
        json.dump(manifest, mf, indent=2, ensure_ascii=False)

    print("\n=== FINAL DOWNLOADED MODEL BUNDLE ===", flush=True)
    total_bytes = 0
    for fn in sorted(os.listdir(models_dir)):
        fp = os.path.join(models_dir, fn)
        if os.path.isfile(fp):
            sz = os.path.getsize(fp)
            total_bytes += sz
            print(f"  - {fn} ({sz / (1024 * 1024):.2f} MB)", flush=True)
    print(f"TOTAL MODEL SIZE: {total_bytes / (1024 * 1024):.2f} MB", flush=True)

    if total_bytes < 50 * 1024 * 1024:
        raise RuntimeError(f"Downloaded bundle too small ({total_bytes} bytes)!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
