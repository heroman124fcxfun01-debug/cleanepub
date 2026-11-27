#!/usr/bin/env python3
"""
Download official `pnnbao97/VieNeu-TTS` models into `app/src/main/assets/models/`:
1. GGUF backbone (`pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf`, fallback `pnnbao-ump/VieNeu-TTS-q4-gguf`) + `voices.json`
2. NeuCodec ONNX 24kHz Decoder (`neuphonic/neucodec-onnx-decoder-int8`, fallback `neuphonic/neucodec-onnx-decoder`)
3. Distill-NeuCodec ONNX 16kHz Encoder (`KevinAHM/distill-neucodec-onnx`, fallback `neuphonic/*`)

Usage:
  python3 scripts/download_vieneu_models.py <models_dir>
"""

import json
import os
import shutil
import sys
from typing import List, Optional

from huggingface_hub import HfApi, hf_hub_download


def assert_not_lfs_pointer(path: str) -> None:
    sz = os.path.getsize(path)
    if sz < 256:
        with open(path, "rb") as f:
            head = f.read(256)
        if b"version https://git-lfs.github.com/spec/v1" in head:
            raise RuntimeError(f"File {path} is a Git LFS pointer ({sz} bytes), not binary data!")


def list_repo_files_safe(api: HfApi, repo_id: str) -> Optional[List[str]]:
    try:
        return api.list_repo_files(repo_id)
    except Exception as exc:
        print(f"  [skip] Cannot access {repo_id}: {exc}", flush=True)
        return None


def main() -> int:
    models_dir = sys.argv[1] if len(sys.argv) >= 2 else "app/src/main/assets/models"
    if os.path.exists(models_dir):
        shutil.rmtree(models_dir)
    os.makedirs(models_dir, exist_ok=True)

    api = HfApi()

    # 1. Official VieNeu-TTS GGUF Backbone + voices.json
    gguf_candidates = [
        "pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-q4-gguf",
        "pnnbao-ump/VieNeu-TTS-0.3B-q8-gguf",
    ]
    gguf_repo_used = None
    gguf_dest = None
    voices_dest = None

    for repo in gguf_candidates:
        files = list_repo_files_safe(api, repo)
        if not files:
            continue
        gguf_files = [f for f in files if f.lower().endswith(".gguf")]
        if not gguf_files:
            continue
        gguf_files.sort(key=lambda s: (0 if "q4" in s.lower() else 1, len(s), s))
        chosen_gguf = gguf_files[0]
        print(f"Downloading GGUF backbone: {repo}/{chosen_gguf}", flush=True)
        cached = hf_hub_download(repo_id=repo, filename=chosen_gguf)
        assert_not_lfs_pointer(cached)
        gguf_dest = os.path.join(models_dir, os.path.basename(chosen_gguf))
        shutil.copy2(cached, gguf_dest)
        gguf_repo_used = repo

        voices_files = [f for f in files if os.path.basename(f).lower() == "voices.json"]
        if voices_files:
            v_cached = hf_hub_download(repo_id=repo, filename=voices_files[0])
            assert_not_lfs_pointer(v_cached)
            voices_dest = os.path.join(models_dir, "voices.json")
            shutil.copy2(v_cached, voices_dest)
            print(f"Downloaded voices.json from {repo}/{voices_files[0]}", flush=True)
        break

    if not gguf_dest:
        raise RuntimeError("Failed to download *.gguf from pnnbao-ump repositories!")

    if not voices_dest:
        for v_repo in (
            "pnnbao-ump/VieNeu-TTS-0.3B",
            "pnnbao-ump/VieNeu-TTS",
            "pnnbao-ump/VieNeu-TTS-0.3B-q4-gguf",
        ):
            files = list_repo_files_safe(api, v_repo) or []
            v_files = [f for f in files if os.path.basename(f).lower() == "voices.json"]
            if v_files:
                v_cached = hf_hub_download(repo_id=v_repo, filename=v_files[0])
                assert_not_lfs_pointer(v_cached)
                voices_dest = os.path.join(models_dir, "voices.json")
                shutil.copy2(v_cached, voices_dest)
                print(f"Downloaded fallback voices.json from {v_repo}/{v_files[0]}", flush=True)
                break

    # 2. Official NeuCodec ONNX 24kHz Decoder
    decoder_candidates = [
        "neuphonic/neucodec-onnx-decoder-int8",
        "neuphonic/neucodec-onnx-decoder",
    ]
    decoder_repo_used = None
    for repo in decoder_candidates:
        files = list_repo_files_safe(api, repo)
        if not files:
            continue
        onnx_files = [f for f in files if f.lower().endswith(".onnx")]
        if not onnx_files:
            continue
        chosen_dec = onnx_files[0]
        print(f"Downloading NeuCodec Decoder: {repo}/{chosen_dec}", flush=True)
        cached = hf_hub_download(repo_id=repo, filename=chosen_dec)
        assert_not_lfs_pointer(cached)
        shutil.copy2(cached, os.path.join(models_dir, "neucodec_decoder.onnx"))
        for extra in files:
            if extra.endswith(".onnx_data") or extra.endswith(".data"):
                ex_cached = hf_hub_download(repo_id=repo, filename=extra)
                assert_not_lfs_pointer(ex_cached)
                shutil.copy2(ex_cached, os.path.join(models_dir, os.path.basename(extra)))
        decoder_repo_used = repo
        break

    if not decoder_repo_used:
        raise RuntimeError("Failed to download neucodec_decoder.onnx!")

    # 3. NeuCodec ONNX 16kHz Encoder (for instant offline voice cloning)
    encoder_candidates = [
        "KevinAHM/distill-neucodec-onnx",
        "neuphonic/distill-neucodec-onnx",
        "neuphonic/neucodec-onnx-encoder",
    ]
    encoder_repo_used = None
    for repo in encoder_candidates:
        files = list_repo_files_safe(api, repo)
        if not files:
            continue
        onnx_files = [f for f in files if f.lower().endswith(".onnx")]
        if not onnx_files:
            continue
        enc_files = [f for f in onnx_files if "enc" in f.lower()] or onnx_files
        enc_files.sort(
            key=lambda s: (
                0 if ("int8" in s.lower() or "quant" in s.lower() or "q8" in s.lower()) else 1,
                len(s),
                s,
            )
        )
        chosen_enc = enc_files[0]
        print(f"Downloading NeuCodec Encoder: {repo}/{chosen_enc}", flush=True)
        cached = hf_hub_download(repo_id=repo, filename=chosen_enc)
        assert_not_lfs_pointer(cached)
        shutil.copy2(cached, os.path.join(models_dir, "neucodec_encoder.onnx"))
        base_no_ext = os.path.splitext(os.path.basename(chosen_enc))[0]
        for extra in files:
            eb = os.path.basename(extra)
            if (eb.endswith(".onnx_data") or eb.endswith(".data")) and (
                base_no_ext in eb or len(onnx_files) == 1
            ):
                ex_cached = hf_hub_download(repo_id=repo, filename=extra)
                assert_not_lfs_pointer(ex_cached)
                shutil.copy2(ex_cached, os.path.join(models_dir, eb))
        encoder_repo_used = repo
        break

    if not encoder_repo_used:
        raise RuntimeError("Failed to download neucodec_encoder.onnx!")

    # 4. Write manifest.json
    manifest = {
        "version": "vieneu-gguf-neucodec-2.0",
        "architecture": "pnnbao97/VieNeu-TTS",
        "sampleRate": 24000,
        "encoderSampleRate": 16000,
        "sources": {
            "gguf": gguf_repo_used,
            "decoder": decoder_repo_used,
            "encoder": encoder_repo_used,
        },
        "files": sorted(os.listdir(models_dir) + ["vi_espeak_ipa.tsv"]),
    }
    with open(os.path.join(models_dir, "manifest.json"), "w", encoding="utf-8") as mf:
        json.dump(manifest, mf, indent=2, ensure_ascii=False)

    total_bytes = sum(
        os.path.getsize(os.path.join(models_dir, fn))
        for fn in os.listdir(models_dir)
        if os.path.isfile(os.path.join(models_dir, fn))
    )
    print(f"Downloaded official VieNeu-TTS bundle: {total_bytes / (1024 * 1024):.2f} MB", flush=True)
    if total_bytes < 50 * 1024 * 1024:
        raise RuntimeError(f"Downloaded bundle too small ({total_bytes} bytes)!")
    return 0


if __name__ == "__main__":
    sys.exit(main())
