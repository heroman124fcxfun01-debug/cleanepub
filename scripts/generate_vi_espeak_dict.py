#!/usr/bin/env python3
"""
Generate `vi_espeak_ipa.tsv` using real `espeak-ng -v vi --ipa -q` so Android offline
Vietnamese G2P matches `pnnbao97/VieNeu-TTS` (EspeakBackend(language="vi")) exactly.

Usage:
  python3 scripts/generate_vi_espeak_dict.py <output_tsv_path> [voices_json_path]
"""

import json
import os
import re
import shutil
import subprocess
import sys
import unicodedata
from typing import Iterable, List, Set

# Tone tables: index 0=ngang, 1=huyền, 2=hỏi, 3=ngã, 4=sắc, 5=nặng
TONE_VOWELS = {
    "a": ["a", "à", "ả", "ã", "á", "ạ"],
    "ă": ["ă", "ằ", "ẳ", "ẵ", "ắ", "ặ"],
    "â": ["â", "ầ", "ẩ", "ẫ", "ấ", "ậ"],
    "e": ["e", "è", "ẻ", "ẽ", "é", "ẹ"],
    "ê": ["ê", "ề", "ể", "ễ", "ế", "ệ"],
    "i": ["i", "ì", "ỉ", "ĩ", "í", "ị"],
    "o": ["o", "ò", "ỏ", "õ", "ó", "ọ"],
    "ô": ["ô", "ồ", "ổ", "ỗ", "ố", "ộ"],
    "ơ": ["ơ", "ờ", "ở", "ỡ", "ớ", "ợ"],
    "u": ["u", "ù", "ủ", "ũ", "ú", "ụ"],
    "ư": ["ư", "ừ", "ử", "ữ", "ứ", "ự"],
    "y": ["y", "ỳ", "ỷ", "ỹ", "ý", "ỵ"],
}

ONSETS = [
    "", "b", "c", "ch", "d", "đ", "g", "gh", "gi", "h", "k", "kh", "l",
    "m", "n", "ng", "ngh", "nh", "p", "ph", "qu", "r", "s", "t", "th", "tr", "v", "x",
]

# Format: (rhyme_str, tone_char_index, stop_coda_only_sac_nang)
RHYMES = [
    # a / ă / â
    ("a", 0, False), ("ai", 0, False), ("ao", 0, False), ("au", 0, False), ("ay", 0, False),
    ("am", 0, False), ("an", 0, False), ("ang", 0, False), ("anh", 0, False),
    ("ap", 0, True), ("at", 0, True), ("ac", 0, True), ("ach", 0, True),
    ("ăm", 0, False), ("ăn", 0, False), ("ăng", 0, False),
    ("ăp", 0, True), ("ăt", 0, True), ("ăc", 0, True),
    ("âu", 0, False), ("ây", 0, False),
    ("âm", 0, False), ("ân", 0, False), ("âng", 0, False),
    ("âp", 0, True), ("ât", 0, True), ("âc", 0, True),
    # e / ê
    ("e", 0, False), ("eo", 0, False), ("em", 0, False), ("en", 0, False), ("eng", 0, False),
    ("ep", 0, True), ("et", 0, True), ("ec", 0, True),
    ("ê", 0, False), ("êu", 0, False), ("êm", 0, False), ("ên", 0, False), ("ênh", 0, False),
    ("êp", 0, True), ("êt", 0, True), ("êch", 0, True),
    # i / ia / iê / y / yê
    ("i", 0, False), ("ia", 0, False), ("iu", 0, False),
    ("im", 0, False), ("in", 0, False), ("inh", 0, False),
    ("ip", 0, True), ("it", 0, True), ("ich", 0, True),
    ("iêm", 1, False), ("iên", 1, False), ("iêng", 1, False), ("iêu", 1, False),
    ("iêp", 1, True), ("iêt", 1, True), ("iêc", 1, True),
    ("y", 0, False), ("ya", 0, False), ("yêm", 1, False), ("yên", 1, False),
    ("yêng", 1, False), ("yêu", 1, False), ("yêt", 1, True),
    # o / ô / ơ
    ("o", 0, False), ("oi", 0, False), ("om", 0, False), ("on", 0, False), ("ong", 0, False),
    ("oong", 1, False), ("ooc", 1, True),
    ("op", 0, True), ("ot", 0, True), ("oc", 0, True),
    ("ô", 0, False), ("ôi", 0, False), ("ôm", 0, False), ("ôn", 0, False), ("ông", 0, False),
    ("ôp", 0, True), ("ôt", 0, True), ("ôc", 0, True),
    ("ơ", 0, False), ("ơi", 0, False), ("ơm", 0, False), ("ơn", 0, False),
    ("ơp", 0, True), ("ơt", 0, True),
    # u / ua / uô
    ("u", 0, False), ("ua", 0, False), ("ui", 0, False),
    ("um", 0, False), ("un", 0, False), ("ung", 0, False),
    ("up", 0, True), ("ut", 0, True), ("uc", 0, True),
    ("uôi", 1, False), ("uôm", 1, False), ("uôn", 1, False), ("uông", 1, False),
    ("uôt", 1, True), ("uôc", 1, True),
    # ư / ưa / ươ
    ("ư", 0, False), ("ưa", 0, False), ("ưi", 0, False), ("ưu", 0, False),
    ("ưm", 0, False), ("ưn", 0, False), ("ưng", 0, False),
    ("ưt", 0, True), ("ưc", 0, True),
    ("ươi", 1, False), ("ươu", 1, False), ("ươm", 1, False), ("ươn", 1, False), ("ương", 1, False),
    ("ươp", 1, True), ("ươt", 1, True), ("ươc", 1, True),
    # Rounded glide oa / oă / oe / uâ / uê / uy / uơ
    ("oa", 1, False), ("oai", 1, False), ("oao", 1, False), ("oay", 1, False),
    ("oam", 1, False), ("oan", 1, False), ("oang", 1, False), ("oanh", 1, False),
    ("oap", 1, True), ("oat", 1, True), ("oac", 1, True), ("oach", 1, True),
    ("oăm", 1, False), ("oăn", 1, False), ("oăng", 1, False),
    ("oăt", 1, True), ("oăc", 1, True),
    ("oe", 1, False), ("oeo", 1, False), ("oen", 1, False), ("oet", 1, True),
    ("uân", 1, False), ("uâng", 1, False), ("uât", 1, True), ("uây", 1, False),
    ("uê", 1, False), ("uên", 1, False), ("uênh", 1, False), ("uêt", 1, True), ("uêch", 1, True),
    ("uy", 1, False), ("uya", 1, False), ("uyu", 1, False), ("uyn", 1, False),
    ("uynh", 1, False), ("uyêt", 2, True), ("uyên", 2, False), ("uyt", 1, True), ("uych", 1, True),
    ("uơ", 1, False),
]

# Also include traditional tone placement variants (hòa/hoà, thủy/thuỷ, quý/quý, etc.)
ALT_TONE_INDEX_RHYMES = {
    "oa": 0, "oe": 0, "uy": 0, "ia": 0, "ua": 0, "ưa": 0,
}

EXTRA_WORDS = [
    # Common loanwords, tech terms, abbreviations & book-reading terms
    "ai", "app", "audio", "book", "cleanbook", "epub", "pdf", "tts", "voice", "studio",
    "wifi", "internet", "online", "offline", "ok", "email", "file", "video", "radio",
    "cafe", "cà", "phê", "tivi", "taxi", "metro", "bus", "karaoke", "sofa", "pizza",
    "sài", "gòn", "hà", "nội", "đà", "nẵng", "huế", "việt", "nam",
    "chương", "tập", "quyển", "phần", "mục", "lục", "lời", "nói", "đầu", "kết", "thúc",
    "không", "một", "hai", "ba", "bốn", "năm", "sáu", "bảy", "tám", "chín", "mười",
    "trăm", "nghìn", "ngàn", "triệu", "tỷ", "lẻ", "linh", "mươi", "mốt", "tư", "lăm",
    "a", "b", "c", "d", "đ", "e", "f", "g", "h", "i", "j", "k", "l", "m", "n",
    "o", "p", "q", "r", "s", "t", "u", "v", "w", "x", "y", "z",
]


def apply_tone(rhyme: str, idx: int, tone: int) -> str:
    ch = rhyme[idx]
    variants = TONE_VOWELS.get(ch)
    if not variants:
        return rhyme
    return rhyme[:idx] + variants[tone] + rhyme[idx + 1 :]


def is_valid_orthography(onset: str, rhyme: str) -> bool:
    front = rhyme.startswith(("i", "e", "ê", "y"))
    rounded = rhyme.startswith(("o", "u"))
    if onset == "k" and not front:
        return False
    if onset == "c" and front:
        return False
    if onset in ("gh", "ngh") and not front:
        return False
    if onset in ("g", "ng") and front:
        return False
    if onset == "qu" and (rhyme.startswith("u") or rhyme.startswith("o")):
        return False
    if onset == "gi" and rhyme.startswith("i"):
        # Avoid double 'gii', keep 'gi' + 'a/e/ê/o/ô/ơ/u/ư...' and standalone 'gì/gi'
        return False
    if onset == "" and rhyme.startswith("y"):
        return True
    if onset != "" and rhyme.startswith("y") and onset not in ("h", "k", "l", "m", "q", "s", "t", "th", "v", "qu"):
        return False
    if onset in ("r", "gi") and rounded and len(rhyme) > 2:
        return False
    return True


def generate_vietnamese_syllables(extra_texts: Iterable[str] = ()) -> List[str]:
    words: Set[str] = set()

    for onset in ONSETS:
        for rhyme, tone_idx, is_stop in RHYMES:
            if not is_valid_orthography(onset, rhyme):
                continue
            tones = (4, 5) if is_stop else (0, 1, 2, 3, 4, 5)
            for tone in tones:
                syl = unicodedata.normalize("NFC", onset + apply_tone(rhyme, tone_idx, tone))
                words.add(syl)
                # Traditional tone placement variant (e.g., hòa vs hoà, thủy vs thuỷ)
                alt_idx = ALT_TONE_INDEX_RHYMES.get(rhyme)
                if alt_idx is not None and alt_idx != tone_idx and tone != 0:
                    alt_syl = unicodedata.normalize("NFC", onset + apply_tone(rhyme, alt_idx, tone))
                    words.add(alt_syl)

    # Special valid gi- + i-elided syllables: gi, gì, gỉ, gĩ, gí, gị, giêng, giết, giếng, giữ, giữa, ...
    for special in [
        "gi", "gì", "gỉ", "gĩ", "gí", "gị",
        "giêng", "giềng", "giếng", "giết", "giệt", "giếc", "giến", "giếm",
        "quốc", "quý", "quỳ", "quỷ", "quỹ", "quỵ", "quýnh", "quỳnh", "quyền", "quyển", "quyết", "quyện",
        "quên", "quền", "quển", "quến", "quện", "quết", "quệch",
        "quân", "quần", "quẩn", "quẫn", "quấn", "quận", "quất", "quật", "quây", "quầy", "quẩy", "quẫy", "quấy", "quậy",
        "quơ", "quờ", "quở", "quỡ", "quớ", "quợ",
        "của", "cũng", "được", "người", "những", "không", "tiếng", "việt",
    ]:
        words.add(unicodedata.normalize("NFC", special))

    for w in EXTRA_WORDS:
        words.add(unicodedata.normalize("NFC", w.lower()))

    # Harvest every token from voices.json reference texts so presets have 100% exact lookup
    word_re = re.compile(r"[^\W\d_]+|\d+", re.UNICODE)
    for txt in extra_texts:
        for tok in word_re.findall(txt.lower()):
            words.add(unicodedata.normalize("NFC", tok))

    return sorted(words)


def espeak_batch(words: List[str], espeak_bin: str) -> List[str]:
    """Phonemize a batch of words via stdin (1 word per line -> 1 IPA line per output line)."""
    payload = "\n".join(words) + "\n"
    proc = subprocess.run(
        [espeak_bin, "-v", "vi", "--ipa", "-q"],
        input=payload,
        text=True,
        encoding="utf-8",
        capture_output=True,
        check=True,
    )
    lines = [
        line.replace("\ufeff", "").replace("\u200d", "").replace("\u200c", "").strip()
        for line in proc.stdout.splitlines()
    ]
    # Remove trailing empty lines if any
    while len(lines) > len(words) and lines[-1] == "":
        lines.pop()

    if len(lines) == len(words):
        return [re.sub(r"\s+", " ", ln) for ln in lines]

    # Fallback: single-word mode for this chunk if line counts diverge
    out: List[str] = []
    for w in words:
        p = subprocess.run(
            [espeak_bin, "-v", "vi", "--ipa", "-q", w],
            text=True,
            encoding="utf-8",
            capture_output=True,
            check=True,
        )
        ipa = " ".join(
            p.stdout.replace("\ufeff", "").replace("\u200d", "").replace("\u200c", "").split()
        )
        out.append(ipa)
    return out


def extract_texts_from_voices_json(voices_json_path: str) -> List[str]:
    if not voices_json_path or not os.path.isfile(voices_json_path):
        return []
    texts: List[str] = []
    try:
        with open(voices_json_path, "r", encoding="utf-8") as f:
            data = json.load(f)

        def walk(obj):
            if isinstance(obj, dict):
                for k, v in obj.items():
                    if k in ("text", "ref_text", "transcript") and isinstance(v, str):
                        texts.append(v)
                    else:
                        walk(v)
            elif isinstance(obj, list):
                for item in obj:
                    walk(item)

        walk(data)
    except Exception as exc:
        print(f"Warning: could not parse {voices_json_path}: {exc}", file=sys.stderr)
    return texts


def main() -> int:
    if len(sys.argv) < 2:
        print("Usage: generate_vi_espeak_dict.py <output_tsv> [voices_json]", file=sys.stderr)
        return 2

    out_path = sys.argv[1]
    voices_json = sys.argv[2] if len(sys.argv) >= 3 else ""

    espeak_bin = shutil.which("espeak-ng") or shutil.which("espeak")
    if not espeak_bin:
        print("ERROR: espeak-ng is not installed in PATH!", file=sys.stderr)
        return 1

    extra_texts = extract_texts_from_voices_json(voices_json)
    extra_texts.append("Xin chào, đây là giọng đọc thử nghiệm tiếng Việt trên ứng dụng đọc sách.")
    extra_texts.append("Nó vẫn hiển thị rất nhiều, vì thế tôi đã bay ra ngoài hà nội để cảm nhận sắc xuân ở đây thật là tươi đẹp.")

    words = generate_vietnamese_syllables(extra_texts)
    print(f"Generating Vietnamese eSpeak-NG IPA dictionary for {len(words)} entries using {espeak_bin}...")

    os.makedirs(os.path.dirname(os.path.abspath(out_path)), exist_ok=True)
    batch_size = 250
    entries = {}

    for start in range(0, len(words), batch_size):
        chunk = words[start : start + batch_size]
        ipas = espeak_batch(chunk, espeak_bin)
        for w, ipa in zip(chunk, ipas):
            clean_ipa = ipa.strip()
            if clean_ipa:
                entries[w] = clean_ipa

    with open(out_path, "w", encoding="utf-8") as f:
        f.write("# Official eSpeak-NG Vietnamese (-v vi --ipa) dictionary for pnnbao97/VieNeu-TTS\n")
        for w in sorted(entries.keys()):
            f.write(f"{w}\t{entries[w]}\n")

    size_kb = os.path.getsize(out_path) / 1024.0
    print(f"Wrote {len(entries)} entries to {out_path} ({size_kb:.1f} KB)")

    # Spot-check critical Vietnamese words
    for sample in ("xin", "chào", "tiếng", "việt", "sách", "hà", "nội", "một", "ngày", "mưa"):
        print(f"  spot-check: {sample!r} -> {entries.get(sample)!r}")

    if len(entries) < 4000:
        print(f"ERROR: Dictionary too small ({len(entries)} entries)!", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
