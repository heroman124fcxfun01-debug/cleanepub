#!/usr/bin/env python3
"""
Automated End-to-End TTS Audio Verification for Official `pnnbao97/VieNeu-TTS`
(GGUF backbone via `llama-cpp-python` + NeuCodec ONNX 24kHz decoder & 16kHz encoder).

Runs on GitHub Actions BEFORE Gradle builds the APK so audio quality is verified
automatically and `test_voice.wav` + `tts_test_report.txt` are published to Artifacts & Releases.

Usage:
  python3 scripts/test_tts_gguf.py <models_dir> <out_wav> <out_report>
"""

import json
import math
import os
import re
import shutil
import struct
import subprocess
import sys
import time
from typing import Dict, List, Optional, Tuple

import numpy as np
import onnxruntime as ort
from llama_cpp import Llama

TEST_SENTENCE = "Xin chào, đây là giọng đọc thử nghiệm tiếng Việt trên ứng dụng đọc sách."
DECODER_SAMPLE_RATE = 24000
ENCODER_SAMPLE_RATE = 16000
ENCODER_HOP_SIZE = 320


def find_file(models_dir: str, predicate) -> Optional[str]:
    for root, _, files in os.walk(models_dir):
        for fname in sorted(files):
            full = os.path.join(root, fname)
            if predicate(fname.lower(), full):
                return full
    return None


def load_tsv_dict(tsv_path: Optional[str]) -> Dict[str, str]:
    mapping: Dict[str, str] = {}
    if not tsv_path or not os.path.isfile(tsv_path):
        return mapping
    with open(tsv_path, "r", encoding="utf-8") as f:
        for line in f:
            if not line or line.startswith("#") or "\t" not in line:
                continue
            k, v = line.rstrip("\r\n").split("\t", 1)
            k = k.strip().lower()
            v = v.strip()
            if k and v:
                mapping[k] = v
    return mapping


def phonemize_with_tsv(text: str, tsv_dict: Dict[str, str]) -> Tuple[str, List[str]]:
    """Replicates Android `VietnameseEspeakG2p.kt` word-by-word TSV lookup."""
    token_re = re.compile(r"([^\W_]+)|([.,!?;:()\"'\-–—…]+)", re.UNICODE)
    out_parts: List[str] = []
    missing: List[str] = []

    for m in token_re.finditer(text.strip()):
        word, punct = m.group(1), m.group(2)
        if word:
            lower = word.lower()
            ipa = tsv_dict.get(lower)
            if not ipa:
                missing.append(lower)
                ipa = lower
            if out_parts and not out_parts[-1].endswith(" "):
                out_parts.append(" ")
            out_parts.append(ipa)
        elif punct:
            mapped = ""
            for ch in punct:
                if ch in (",", "–", "—"):
                    mapped += ","
                elif ch in (".", "…"):
                    mapped += "."
                elif ch in ("!", "?", ";", ":"):
                    mapped += ch
            if mapped:
                out_parts.append(mapped)

    res = "".join(out_parts)
    res = re.sub(r"\s+([.,!?;:])", r"\1", res)
    res = re.sub(r"\s+", " ", res).strip()
    return res, missing


def phonemize_with_espeak_cli(text: str) -> str:
    espeak_bin = shutil.which("espeak-ng") or shutil.which("espeak")
    if not espeak_bin:
        return ""
    proc = subprocess.run(
        [espeak_bin, "-v", "vi", "--ipa", "-q", text],
        text=True,
        encoding="utf-8",
        capture_output=True,
        check=True,
    )
    cleaned = proc.stdout.replace("\ufeff", "").replace("\u200d", "").replace("\u200c", "")
    return re.sub(r"\s+", " ", cleaned).strip()


def _flatten_float_list(raw) -> Optional[List[float]]:
    if not isinstance(raw, list) or not raw:
        return None
    out: List[float] = []

    def walk(v):
        if isinstance(v, (int, float)):
            out.append(float(v))
        elif isinstance(v, list):
            for elem in v:
                walk(elem)

    walk(raw)
    return out if out else None


def load_preset_voice(voices_json_path: Optional[str]) -> Tuple[str, str, List[int], Optional[np.ndarray]]:
    if not voices_json_path or not os.path.isfile(voices_json_path):
        raise FileNotFoundError(f"Missing voices.json in models dir ({voices_json_path})")
    with open(voices_json_path, "r", encoding="utf-8") as f:
        data = json.load(f)

    presets: Dict[str, dict] = {}
    default_id = ""
    if isinstance(data, dict):
        default_id = str(data.get("default_voice") or "")
        raw_presets = data.get("presets")
        if isinstance(raw_presets, dict):
            presets = raw_presets
        else:
            for k, v in data.items():
                if isinstance(v, dict) and ("codes" in v or "ref_codes" in v):
                    presets[k] = v

    if not presets:
        raise ValueError("voices.json contains no valid voice presets with 'codes'")

    ordered_keys = []
    if default_id and default_id in presets:
        ordered_keys.append(default_id)
    ordered_keys.extend(k for k in presets.keys() if k != default_id)

    for key in ordered_keys:
        entry = presets[key]
        raw_codes = entry.get("codes") or entry.get("ref_codes") or []
        codes = [int(c) for c in raw_codes if 0 <= int(c) <= 65535]
        ref_text = str(entry.get("text") or entry.get("ref_text") or "").strip()
        raw_emb = (
            entry.get("voice_embedding")
            or entry.get("voice")
            or entry.get("speaker_embedding")
            or entry.get("embedding")
        )
        emb_list = _flatten_float_list(raw_emb)
        voice_emb = np.asarray(emb_list, dtype=np.float32) if emb_list else None
        if len(codes) >= 8 and ref_text:
            return key, ref_text, codes, voice_emb

    raise ValueError("No preset in voices.json has >= 8 NeuCodec codes and non-empty reference text")


def write_pcm16_mono_wav(path: str, samples: np.ndarray, sample_rate: int) -> None:
    clipped = np.clip(samples.astype(np.float32), -1.0, 1.0)
    pcm16 = np.round(clipped * 32767.0).astype("<i2")
    data_bytes = pcm16.tobytes()
    header = struct.pack(
        "<4sI4s4sIHHIIHH4sI",
        b"RIFF",
        36 + len(data_bytes),
        b"WAVE",
        b"fmt ",
        16,
        1,  # PCM
        1,  # Mono
        sample_rate,
        sample_rate * 2,
        2,
        16,
        b"data",
        len(data_bytes),
    )
    with open(path, "wb") as f:
        f.write(header)
        f.write(data_bytes)


def _resolve_embedding_dim(shape, fallback_dim: int = 256) -> int:
    if shape:
        for dim in reversed(shape):
            if isinstance(dim, int) and dim > 1:
                return int(dim)
    return fallback_dim


def _build_voice_embedding_tensor(
    shape,
    ort_type: str,
    voice_embedding: Optional[np.ndarray],
) -> np.ndarray:
    dim = _resolve_embedding_dim(shape, fallback_dim=int(voice_embedding.size) if voice_embedding is not None and voice_embedding.size > 0 else 256)
    vec = np.zeros(dim, dtype=np.float32)
    if voice_embedding is not None and voice_embedding.size > 0:
        src = voice_embedding.astype(np.float32).reshape(-1)
        if src.size == dim:
            vec[:] = src
        else:
            x_old = np.linspace(0.0, 1.0, num=src.size, endpoint=True)
            x_new = np.linspace(0.0, 1.0, num=dim, endpoint=True)
            vec[:] = np.interp(x_new, x_old, src).astype(np.float32)
    else:
        # Neutral unit-norm fallback if voices.json does not supply an embedding vector
        vec.fill(1.0 / math.sqrt(float(dim)))

    norm = float(np.linalg.norm(vec))
    if norm > 1e-6:
        vec = vec / norm

    rank = len(shape) if shape else 2
    if rank == 3:
        arr = vec.reshape(1, 1, dim)
    elif rank == 2:
        arr = vec.reshape(1, dim)
    elif rank == 1:
        arr = vec.reshape(dim)
    else:
        arr = vec.reshape(1, dim)

    if "float16" in ort_type.lower():
        return arr.astype(np.float16)
    return arr.astype(np.float32)


def decode_neucodec(
    decoder_path: str,
    codes: List[int],
    voice_embedding: Optional[np.ndarray] = None,
    log_fn=None,
) -> np.ndarray:
    sess = ort.InferenceSession(decoder_path, providers=["CPUExecutionProvider"])
    inputs = sess.get_inputs()
    outputs_meta = sess.get_outputs()

    if log_fn is not None:
        in_summary = [(i.name, i.type, list(i.shape)) for i in inputs]
        out_summary = [(o.name, o.type, list(o.shape)) for o in outputs_meta]
        log_fn(f"Decoder ONNX inputs:  {in_summary}")
        log_fn(f"Decoder ONNX outputs: {out_summary}")

    feed: Dict[str, np.ndarray] = {}
    has_int_input = any("int" in i.type.lower() for i in inputs)

    for inp in inputs:
        name_low = inp.name.lower()
        type_low = inp.type.lower()
        rank = len(inp.shape)

        is_voice_emb = (
            "voice" in name_low
            or "embed" in name_low
            or "spk" in name_low
            or "speaker" in name_low
            or ("float" in type_low and has_int_input)
        )

        if is_voice_emb:
            emb_tensor = _build_voice_embedding_tensor(inp.shape, inp.type, voice_embedding)
            feed[inp.name] = emb_tensor
            if log_fn is not None:
                src_label = "voices.json" if (voice_embedding is not None and voice_embedding.size > 0) else "unit-norm fallback"
                log_fn(f"  feed[{inp.name}]: shape={emb_tensor.shape}, dtype={emb_tensor.dtype} ({src_label})")
        else:
            dtype = np.int64 if "int64" in type_low else np.int32
            arr = np.asarray(codes, dtype=dtype)
            if rank == 3:
                arr = arr.reshape(1, 1, -1)
            elif rank == 2:
                arr = arr.reshape(1, -1)
            elif rank == 1:
                arr = arr.reshape(-1)
            else:
                arr = arr.reshape(1, 1, -1)
            feed[inp.name] = arr
            if log_fn is not None:
                log_fn(f"  feed[{inp.name}]: shape={arr.shape}, dtype={arr.dtype} (range={int(arr.min())}..{int(arr.max())})")

    outputs = sess.run(None, feed)
    wav = np.asarray(outputs[0], dtype=np.float32).reshape(-1)
    wav = np.nan_to_num(wav, nan=0.0, posinf=0.0, neginf=0.0)
    peak = float(np.max(np.abs(wav))) if wav.size else 0.0
    if peak > 0.98:
        wav = wav * (0.95 / peak)
    return wav


def resample_linear(samples: np.ndarray, src_rate: int, dst_rate: int) -> np.ndarray:
    if src_rate == dst_rate or samples.size == 0:
        return samples.astype(np.float32)
    dst_len = max(1, int(round(samples.size * (dst_rate / float(src_rate)))))
    x_old = np.linspace(0.0, 1.0, num=samples.size, endpoint=True)
    x_new = np.linspace(0.0, 1.0, num=dst_len, endpoint=True)
    return np.interp(x_new, x_old, samples).astype(np.float32)


def smoke_test_neucodec_encoder(
    encoder_path: str,
    wav_24k: np.ndarray,
    log_fn=None,
) -> List[int]:
    sess = ort.InferenceSession(encoder_path, providers=["CPUExecutionProvider"])
    inputs = sess.get_inputs()
    outputs_meta = sess.get_outputs()
    if log_fn is not None:
        log_fn(f"Encoder ONNX inputs:  {[(i.name, i.type, list(i.shape)) for i in inputs]}")
        log_fn(f"Encoder ONNX outputs: {[(o.name, o.type, list(o.shape)) for o in outputs_meta]}")

    wav_16k = resample_linear(wav_24k, DECODER_SAMPLE_RATE, ENCODER_SAMPLE_RATE)
    # Trim to 5-8 seconds window for realistic zero-shot voice cloning smoke test and pad to multiple of 320
    wav_16k = wav_16k[: ENCODER_SAMPLE_RATE * 6]
    rem = wav_16k.size % ENCODER_HOP_SIZE
    pad = (ENCODER_HOP_SIZE - rem) if rem != 0 else 0
    if pad:
        wav_16k = np.concatenate([wav_16k, np.zeros(pad, dtype=np.float32)])

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
                feed[inp.name] = wav_16k.reshape(1, 1, -1).astype(np.float32)
            elif rank == 2:
                feed[inp.name] = wav_16k.reshape(1, -1).astype(np.float32)
            elif rank == 1:
                feed[inp.name] = wav_16k.reshape(-1).astype(np.float32)
            else:
                feed[inp.name] = wav_16k.reshape(1, 1, -1).astype(np.float32)

    outputs = sess.run(None, feed)
    raw_codes = None
    for idx, out_val in enumerate(outputs):
        o_type = outputs_meta[idx].type.lower()
        o_name = outputs_meta[idx].name.lower()
        if "int" in o_type or "code" in o_name or "token" in o_name or "idx" in o_name:
            raw_codes = np.asarray(out_val).reshape(-1)
            break
    if raw_codes is None:
        raw_codes = np.asarray(outputs[0]).reshape(-1)
    return [int(round(float(c))) for c in raw_codes.tolist() if 0 <= int(round(float(c))) <= 65535]


def analyze_waveform(wav: np.ndarray, sample_rate: int) -> Dict[str, float]:
    n = int(wav.size)
    duration_sec = n / float(sample_rate) if n > 0 else 0.0
    if n == 0:
        return {
            "samples": 0,
            "duration_sec": 0.0,
            "rms": 0.0,
            "peak": 0.0,
            "clip_ratio": 1.0,
            "zcr": 0.0,
            "env_cv": 0.0,
        }
    rms = float(np.sqrt(np.mean(wav * wav)))
    peak = float(np.max(np.abs(wav)))
    clip_ratio = float(np.mean(np.abs(wav) >= 0.995))
    signs = wav >= 0.0
    zcr = float(np.mean(signs[1:] != signs[:-1])) if n > 1 else 0.0

    # 20ms frame RMS envelope modulation (coefficient of variation)
    frame_len = max(1, int(sample_rate * 0.02))
    num_frames = n // frame_len
    if num_frames >= 4:
        frames = wav[: num_frames * frame_len].reshape(num_frames, frame_len)
        frame_rms = np.sqrt(np.mean(frames * frames, axis=1))
        mean_fr = float(np.mean(frame_rms))
        env_cv = float(np.std(frame_rms) / (mean_fr + 1e-8))
    else:
        env_cv = 0.0

    return {
        "samples": float(n),
        "duration_sec": duration_sec,
        "rms": rms,
        "peak": peak,
        "clip_ratio": clip_ratio,
        "zcr": zcr,
        "env_cv": env_cv,
    }


def main() -> int:
    if len(sys.argv) < 4:
        print("Usage: test_tts_gguf.py <models_dir> <out_wav> <out_report>", file=sys.stderr)
        return 2

    models_dir, out_wav, out_report = sys.argv[1], sys.argv[2], sys.argv[3]
    lines: List[str] = []

    def log(msg: str) -> None:
        print(msg, flush=True)
        lines.append(msg)

    t0 = time.time()
    failures: List[str] = []

    try:
        log("=== AUTOMATED TTS AUDIO TEST (Official pnnbao97/VieNeu-TTS GGUF + NeuCodec 24kHz) ===")
        log(f"Models directory: {models_dir}")
        log(f"Test sentence: {TEST_SENTENCE}")

        gguf_path = find_file(models_dir, lambda n, _: n.endswith(".gguf"))
        decoder_path = find_file(models_dir, lambda n, _: n.endswith(".onnx") and "dec" in n)
        encoder_path = find_file(models_dir, lambda n, _: n.endswith(".onnx") and "enc" in n)
        voices_path = find_file(models_dir, lambda n, _: n == "voices.json")
        tsv_path = find_file(models_dir, lambda n, _: n.endswith(".tsv") and "espeak" in n)

        log(f"GGUF backbone:    {gguf_path}")
        log(f"NeuCodec decoder: {decoder_path}")
        log(f"NeuCodec encoder: {encoder_path or '(optional / not bundled — using voices.json presets)'}")
        log(f"voices.json:      {voices_path}")
        log(f"vi_espeak_ipa:    {tsv_path}")

        if not gguf_path:
            raise FileNotFoundError("Missing *.gguf backbone in assets/models/")
        if not decoder_path:
            raise FileNotFoundError("Missing neucodec_decoder.onnx in assets/models/")

        preset_id, ref_text, ref_codes, voice_emb = load_preset_voice(voices_path)
        emb_label = f", voice_embedding={voice_emb.shape}" if voice_emb is not None else ""
        log(f"Preset voice:     {preset_id} ({len(ref_codes)} NeuCodec codes{emb_label})")
        log(f"Preset ref_text:  {ref_text}")

        tsv_dict = load_tsv_dict(tsv_path)
        log(f"TSV dictionary:   {len(tsv_dict)} Vietnamese entries")
        if len(tsv_dict) < 4000:
            failures.append(f"vi_espeak_ipa.tsv has too few entries ({len(tsv_dict)})")

        ref_phones, ref_missing = phonemize_with_tsv(ref_text, tsv_dict)
        target_phones, target_missing = phonemize_with_tsv(TEST_SENTENCE, tsv_dict)
        cli_target_phones = phonemize_with_espeak_cli(TEST_SENTENCE)

        log(f"Ref IPA (TSV):    {ref_phones}")
        log(f"Target IPA (TSV): {target_phones}")
        log(f"Target IPA (CLI): {cli_target_phones}")
        if ref_missing:
            log(f"Ref OOV words:    {ref_missing}")
        if target_missing:
            log(f"Target OOV words: {target_missing}")

        ref_codes_str = "".join(f"<|speech_{c}|>" for c in ref_codes)
        prompt = (
            f"user: Convert the text to speech:<|TEXT_PROMPT_START|>{ref_phones} {target_phones}<|TEXT_PROMPT_END|>\n"
            f"assistant:<|SPEECH_GENERATION_START|>{ref_codes_str}"
        )

        # Load GGUF model via llama-cpp-python (same llama.cpp core as Android llamacpp-kotlin)
        load_t0 = time.time()
        n_threads = max(2, min(8, os.cpu_count() or 4))
        llm = Llama(
            model_path=gguf_path,
            n_ctx=2048,
            n_threads=n_threads,
            n_gpu_layers=0,
            verbose=False,
        )
        log(f"GGUF loaded in {time.time() - load_t0:.2f}s (n_threads={n_threads})")

        # Verify special tokens exist as single tokens in GGUF vocab
        for tok in ("<|TEXT_PROMPT_START|>", "<|TEXT_PROMPT_END|>", "<|SPEECH_GENERATION_START|>", "<|SPEECH_GENERATION_END|>", "<|speech_0|>"):
            ids = llm.tokenize(tok.encode("utf-8"), add_bos=False, special=True)
            log(f"  vocab probe {tok} -> {ids}")
            if len(ids) != 1:
                failures.append(f"GGUF special token {tok} tokenized into {len(ids)} tokens instead of 1")

        # Generate speech tokens
        gen_t0 = time.time()
        max_new_tokens = min(1024, max(256, len(target_phones) * 4 + 120))
        out = llm(
            prompt,
            max_tokens=max_new_tokens,
            temperature=0.8,
            top_k=50,
            top_p=0.95,
            repeat_penalty=1.1,
            stop=["<|SPEECH_GENERATION_END|>"],
            echo=False,
        )
        gen_elapsed = time.time() - gen_t0
        raw_text = out["choices"][0]["text"]
        finish_reason = out["choices"][0].get("finish_reason", "unknown")
        speech_codes = [int(m) for m in re.findall(r"<\|speech_(\d+)\|>", raw_text) if 0 <= int(m) <= 65535]
        unique_codes = len(set(speech_codes))

        log(
            f"GGUF generated {len(speech_codes)} speech tokens ({unique_codes} unique) "
            f"in {gen_elapsed:.2f}s (finish_reason={finish_reason})"
        )
        log(f"First 16 codes:   {speech_codes[:16]}")

        if len(speech_codes) < 35:
            failures.append(f"Too few generated speech tokens ({len(speech_codes)} < 35)")
        if unique_codes < 10:
            failures.append(f"Collapsed/repetitive token output ({unique_codes} unique tokens < 10)")

        # Decode with official NeuCodec ONNX decoder (24 kHz)
        dec_t0 = time.time()
        wav = (
            decode_neucodec(decoder_path, speech_codes, voice_embedding=voice_emb, log_fn=log)
            if speech_codes
            else np.zeros(0, dtype=np.float32)
        )
        dec_elapsed = time.time() - dec_t0
        write_pcm16_mono_wav(out_wav, wav, DECODER_SAMPLE_RATE)

        stats = analyze_waveform(wav, DECODER_SAMPLE_RATE)
        log(
            f"NeuCodec decoded {int(stats['samples'])} samples @ {DECODER_SAMPLE_RATE}Hz "
            f"({stats['duration_sec']:.2f}s) in {dec_elapsed:.2f}s -> {out_wav}"
        )
        log(
            f"Audio Metrics: duration={stats['duration_sec']:.2f}s | rms={stats['rms']:.4f} | "
            f"peak={stats['peak']:.4f} | clip={stats['clip_ratio']:.4f} | "
            f"zcr={stats['zcr']:.4f} | env_cv={stats['env_cv']:.4f}"
        )

        # Quality gates
        if stats["duration_sec"] < 1.5:
            failures.append(f"Audio too short ({stats['duration_sec']:.2f}s < 1.50s)")
        if stats["duration_sec"] > 30.0:
            failures.append(f"Audio suspiciously long ({stats['duration_sec']:.2f}s > 30.0s)")
        if stats["rms"] < 0.008:
            failures.append(f"Audio RMS too low / near-silence ({stats['rms']:.5f} < 0.008)")
        if stats["rms"] > 0.85:
            failures.append(f"Audio RMS too high / saturated ({stats['rms']:.4f} > 0.85)")
        if stats["peak"] < 0.04:
            failures.append(f"Audio peak too low ({stats['peak']:.4f} < 0.04)")
        if stats["clip_ratio"] >= 0.15:
            failures.append(f"Audio heavily clipped (clip_ratio={stats['clip_ratio']:.3f} >= 0.15)")
        if stats["zcr"] > 0.42:
            failures.append(f"Audio looks like white noise / static (ZCR={stats['zcr']:.4f} > 0.42)")
        if stats["env_cv"] < 0.12:
            failures.append(f"Audio lacks speech syllable modulation (env_cv={stats['env_cv']:.4f} < 0.12)")

        # Smoke test for NeuCodec Encoder whenever bundled (non-fatal warning if CI skipped encoder export)
        if not encoder_path:
            log("NeuCodec Encoder smoke test: [WARN] neucodec_encoder.onnx not present — skipped (GGUF + Decoder verified OK)")
        elif wav.size >= DECODER_SAMPLE_RATE:
            enc_t0 = time.time()
            enc_codes = smoke_test_neucodec_encoder(encoder_path, wav, log_fn=log)
            enc_elapsed = time.time() - enc_t0
            log(
                f"NeuCodec Encoder smoke test: {len(enc_codes)} FSQ codes "
                f"({len(set(enc_codes))} unique) in {enc_elapsed:.2f}s | sample={enc_codes[:12]}"
            )
            if len(enc_codes) < 15 or len(set(enc_codes)) < 5:
                failures.append(
                    f"NeuCodec encoder smoke test produced invalid codes (len={len(enc_codes)}, unique={len(set(enc_codes))})"
                )

        total_elapsed = time.time() - t0
        if failures:
            log("\nRESULT: FAILED")
            for err in failures:
                log(f"  [FAIL] {err}")
        else:
            log(f"\nRESULT: PASS (total {total_elapsed:.2f}s)")

    except Exception as exc:
        failures.append(f"Unhandled exception: {type(exc).__name__}: {exc}")
        log(f"\nRESULT: FAILED ({type(exc).__name__}: {exc})")
        # Ensure wav file exists (empty valid WAV) so upload-artifact doesn't break if desired
        if not os.path.exists(out_wav):
            write_pcm16_mono_wav(out_wav, np.zeros(DECODER_SAMPLE_RATE // 10, dtype=np.float32), DECODER_SAMPLE_RATE)

    with open(out_report, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")

    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
