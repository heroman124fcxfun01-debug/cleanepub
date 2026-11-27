#!/usr/bin/env node
/**
 * Automated Offline TTS Audio Test for VieNeu-TTS v3 Turbo + MOSS-Audio-Tokenizer-Nano (48 kHz)
 *
 * Runs on GitHub Actions (Node.js 20 + onnxruntime-node) BEFORE Gradle builds the 300MB APK:
 * 1. Inspects `config.json`, `manifest.json`, `codec_browser_onnx_meta.json`, `heads.json`,
 *    `tokenizer.json`, `voices.json`, and `sea_g2p.bin` (`SEAP` v2 binary dictionary).
 * 2. Converts the Vietnamese test sentence into exact IPA + GPT-2 ByteLevel BPE token IDs
 *    (mirroring Android Kotlin `SeaG2pBinaryDict` + `VietnameseIpaPhonemizer`).
 * 3. Runs ground-truth self-calibration against real `voices.json` `referenceCodes` and
 *    `text_head_gather.onnx` next-token grammar (mirroring Android Kotlin `VieNeuV3Calibration`).
 * 4. Synthesizes:
 *      "Xin chào, đây là giọng đọc thử nghiệm tiếng Việt trên ứng dụng đọc sách."
 *    and writes standard 16-bit signed PCM Mono WAV to `test_voice.wav`.
 * 5. Verifies audio duration (>= 1.5s), RMS amplitude, zero-crossing rate (anti white-noise),
 *    syllabic envelope modulation, and codebook diversity. Fails build (exit 1) with full
 *    token + calibration + ONNX logs if audio is invalid.
 */

import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";

const require = createRequire(import.meta.url);
let ort;
try {
  ort = require("onnxruntime-node");
} catch (firstErr) {
  const nodePaths = (process.env.NODE_PATH || "").split(path.delimiter).filter(Boolean);
  for (const dir of nodePaths) {
    const candidate = path.join(dir, "onnxruntime-node");
    if (fs.existsSync(candidate)) {
      ort = require(candidate);
      break;
    }
  }
  if (!ort) {
    console.error("ERROR: Cannot load 'onnxruntime-node'. Set NODE_PATH or install onnxruntime-node.");
    console.error(firstErr);
    process.exit(1);
  }
}

const TEST_SENTENCE = "Xin chào, đây là giọng đọc thử nghiệm tiếng Việt trên ứng dụng đọc sách.";
const MODELS_DIR = path.resolve(process.argv[2] || "app/src/main/assets/models");
const OUTPUT_WAV = path.resolve(process.argv[3] || "test_voice.wav");
const REPORT_FILE = path.resolve(process.argv[4] || "tts_test_report.txt");

const reportLines = [];
function log(msg = "") {
  console.log(msg);
  reportLines.push(msg);
}
function saveReport() {
  try {
    fs.writeFileSync(REPORT_FILE, reportLines.join("\n") + "\n", "utf8");
  } catch (e) {
    console.error("Failed to write report:", e.message);
  }
}

// ============================================================================
// 1. Exact GPT-2 ByteLevel Bijection + BPE Encoder
// ============================================================================

const BYTE_TO_UNICODE = new Array(256);
const UNICODE_TO_BYTE = new Map();
{
  const direct = new Uint8Array(256);
  for (let b = 33; b <= 126; b++) direct[b] = 1;
  for (let b = 161; b <= 172; b++) direct[b] = 1;
  for (let b = 174; b <= 255; b++) direct[b] = 1;
  let extra = 256;
  for (let b = 0; b < 256; b++) {
    const ch = String.fromCharCode(direct[b] ? b : extra++);
    BYTE_TO_UNICODE[b] = ch;
    UNICODE_TO_BYTE.set(ch, b);
  }
}

function encodeUtf8ToByteLevel(str) {
  const bytes = Buffer.from(str, "utf8");
  let out = "";
  for (let i = 0; i < bytes.length; i++) out += BYTE_TO_UNICODE[bytes[i]];
  return out;
}

function decodeByteLevelToUtf8(tok) {
  const bytes = [];
  for (const ch of tok) {
    const b = UNICODE_TO_BYTE.get(ch);
    if (b === undefined) return tok;
    bytes.push(b);
  }
  return Buffer.from(bytes).toString("utf8");
}

function parseTokenizer(tokenizerPath) {
  const raw = JSON.parse(fs.readFileSync(tokenizerPath, "utf8"));
  const tokenToId = new Map();
  const idToToken = new Map();
  const vocab = raw?.model?.vocab || {};
  for (const [tok, id] of Object.entries(vocab)) {
    tokenToId.set(tok, Number(id));
    idToToken.set(Number(id), tok);
  }
  for (const item of raw?.added_tokens || []) {
    if (item?.content && typeof item.id === "number") {
      tokenToId.set(item.content, item.id);
      idToToken.set(item.id, item.content);
    }
  }
  const merges = [];
  for (const entry of raw?.model?.merges || []) {
    if (typeof entry === "string") {
      const sp = entry.indexOf(" ");
      if (sp > 0 && sp < entry.length - 1) merges.push([entry.slice(0, sp), entry.slice(sp + 1)]);
    } else if (Array.isArray(entry) && entry.length >= 2) {
      merges.push([String(entry[0]), String(entry[1])]);
    }
  }

  let addPrefixSpace = false;
  let splitPattern = null;
  let splitBehavior = "";
  let preSummary = "";
  const pt = raw?.pre_tokenizer;
  if (pt) {
    const extractSplit = (o) => {
      if (String(o.type || "").toLowerCase() === "split") {
        const p = o.pattern?.Regex || o.pattern?.String || (typeof o.pattern === "string" ? o.pattern : null);
        if (p) splitPattern = p;
        splitBehavior = o.behavior || "";
      }
    };
    addPrefixSpace = Boolean(pt.add_prefix_space);
    extractSplit(pt);
    if (Array.isArray(pt.pretokenizers)) {
      const types = [];
      for (const sub of pt.pretokenizers) {
        types.push(sub.type);
        extractSplit(sub);
        if (sub.add_prefix_space) addPrefixSpace = true;
      }
      preSummary = `${pt.type}[${types.join("+")}](prefixSpace=${addPrefixSpace}${splitPattern ? `, split='${splitPattern}'(${splitBehavior})` : ""})`;
    } else {
      preSummary = `${pt.type}(prefixSpace=${addPrefixSpace}${splitPattern ? `, split='${splitPattern}'(${splitBehavior})` : ""})`;
    }
  }
  const isByteLevelBpe = tokenToId.has("Ā") && tokenToId.has("Ġ");
  const mergeRanks = new Map();
  merges.forEach(([a, b], idx) => mergeRanks.set(`${a}\u0001${b}`, idx));

  const decodedMerges = new Set();
  for (const [id, tok] of idToToken.entries()) {
    if (tok.startsWith("<|")) continue;
    if (isByteLevelBpe && (id >= 300 || tok.length > 1)) {
      decodedMerges.add(decodeByteLevelToUtf8(tok));
    }
  }

  return {
    tokenToId,
    idToToken,
    merges,
    mergeRanks,
    isByteLevelBpe,
    addPrefixSpace,
    splitPattern,
    splitBehavior,
    preSummary,
    decodedMerges
  };
}

function splitByteLevelChunks(text, splitPattern, splitBehavior) {
  if (splitPattern && String(splitBehavior).toLowerCase() === "isolated") {
    try {
      const re = new RegExp(splitPattern, "gu");
      const out = [];
      let cursor = 0;
      for (const m of text.matchAll(re)) {
        if (m.index > cursor) out.push(text.slice(cursor, m.index));
        if (m[0]) out.push(m[0]);
        cursor = m.index + m[0].length;
      }
      if (cursor < text.length) out.push(text.slice(cursor));
      if (out.length > 0) return out;
    } catch {
      // fallback below
    }
  }
  const out = [];
  let buf = "";
  const punct = new Set([".", ",", "!", "?", ";", ":", "…", "(", ")", '"', "\n"]);
  for (const ch of text) {
    if (ch === " ") {
      if (buf) {
        out.push(buf);
        buf = "";
      }
      buf += ch;
    } else if (punct.has(ch)) {
      if (buf && buf !== " ") {
        out.push(buf);
        buf = "";
      }
      buf += ch;
      out.push(buf);
      buf = "";
    } else {
      buf += ch;
    }
  }
  if (buf) out.push(buf);
  return out;
}

function encodeByteLevelBpe(text, tok) {
  const trimmed = text.trim();
  if (!trimmed) return [];
  const input = tok.addPrefixSpace && !trimmed.startsWith(" ") ? ` ${trimmed}` : trimmed;
  const chunks = splitByteLevelChunks(input, tok.splitPattern, tok.splitBehavior);
  const unkId = tok.tokenToId.get("<|unk|>") ?? 43;
  const out = [];

  for (const chunk of chunks) {
    const byteEncoded = encodeUtf8ToByteLevel(chunk);
    if (!byteEncoded) continue;
    const symbols = Array.from(byteEncoded);
    if (tok.mergeRanks.size > 0 && symbols.length > 1) {
      while (symbols.length > 1) {
        let bestRank = Infinity;
        let bestPos = -1;
        for (let i = 0; i < symbols.length - 1; i++) {
          const r = tok.mergeRanks.get(`${symbols[i]}\u0001${symbols[i + 1]}`);
          if (r !== undefined && r < bestRank) {
            bestRank = r;
            bestPos = i;
          }
        }
        if (bestPos < 0) break;
        symbols[bestPos] = symbols[bestPos] + symbols[bestPos + 1];
        symbols.splice(bestPos + 1, 1);
      }
    }
    for (const sym of symbols) {
      const id = tok.tokenToId.get(sym);
      if (id !== undefined) {
        out.push(id);
      } else {
        for (const c of sym) out.push(tok.tokenToId.get(c) ?? unkId);
      }
    }
  }
  return out;
}

// ============================================================================
// 2. Zero-Heap Positioned Reader for `sea_g2p.bin` (`SEAP` v2)
// ============================================================================

class SeaG2pReader {
  constructor(filePath) {
    this.fd = fs.openSync(filePath, "r");
    this.fileSize = fs.fstatSync(this.fd).size;
    this.valid = false;
    this.detectedPrefix = "";
    this.summary = "";

    const hdr = Buffer.alloc(32);
    fs.readSync(this.fd, hdr, 0, 32, 0);
    if (hdr.toString("ascii", 0, 4) !== "SEAP") {
      this.summary = `not SEAP (magic=${hdr.toString("ascii", 0, 4)})`;
      return;
    }
    this.version = hdr.readUInt32LE(4);
    this.nStrings = hdr.readUInt32LE(8);
    this.nEntries = hdr.readUInt32LE(12);
    this.nExtra = hdr.readUInt32LE(16);
    this.strOffsetsPos = hdr.readUInt32LE(20);
    this.entriesPos = hdr.readUInt32LE(24);
    this.extraPos = hdr.readUInt32LE(28);

    const off0 = this.readU32(this.strOffsetsPos);
    const off1 = this.nStrings > 1 ? this.readU32(this.strOffsetsPos + 4) : this.strOffsetsPos;
    const off2 = this.nStrings > 2 ? this.readU32(this.strOffsetsPos + 8) : this.strOffsetsPos;
    this.poolBase = off0 < 32 ? 32 : 0;
    this.poolEnd = this.strOffsetsPos;
    this.framing = this.detectFraming(off0, off1 - off0, off1, off2 - off1);

    const sampleIndices = [0, 1, Math.floor(this.nEntries / 4), Math.floor(this.nEntries / 2), Math.floor((this.nEntries * 3) / 4), this.nEntries - 1]
      .filter((i) => i >= 0 && i < this.nEntries);
    const samples = sampleIndices.map((idx) => {
      const [kId, vId] = this.readEntryPair(idx);
      const ks = (this.readStringById(kId) || "?").replace(/\0/g, "\\0").slice(0, 24);
      const vs = (this.readStringById(vId) || "?").replace(/\0/g, "\\0").slice(0, 24);
      return `[${idx}]${ks}=>${vs}`;
    });

    const prefixes = ["", "vi:", "vi\0", "vi|", "vi/", "vi\t", "vi "];
    const testWords = ["xin", "chào", "một", "ngày", "mưa", "việt", "sách"];
    let bestPfx = "";
    let bestHits = [];
    for (const pfx of prefixes) {
      const hits = [];
      for (const w of testWords) {
        const res = this.binarySearchExact(pfx + w);
        if (res) hits.push(`${w}->${res}`);
      }
      if (hits.length > bestHits.length) {
        bestHits = hits;
        bestPfx = pfx;
      }
    }
    this.detectedPrefix = bestPfx;
    this.valid = bestHits.length >= 3;
    this.summary = `SEAP v${this.version} | str=${this.nStrings}, ent=${this.nEntries}, extra=${this.nExtra} | ` +
      `poolBase=${this.poolBase}, framing=${this.framing}, viPrefix='${bestPfx.replace(/\0/g, "\\0")}', valid=${this.valid} | ` +
      `test=[${bestHits.join(", ") || "0 hits"}] | samples=[${samples.join(" ; ")}]`;
  }

  readU32(pos) {
    const b = Buffer.allocUnsafe(4);
    fs.readSync(this.fd, b, 0, 4, pos);
    return b.readUInt32LE(0);
  }
  readU16(pos) {
    const b = Buffer.allocUnsafe(2);
    fs.readSync(this.fd, b, 0, 2, pos);
    return b.readUInt16LE(0);
  }
  readU8(pos) {
    const b = Buffer.allocUnsafe(1);
    fs.readSync(this.fd, b, 0, 1, pos);
    return b[0];
  }
  detectFraming(off0, len0, off1, len1) {
    const s0 = this.poolBase + off0;
    const s1 = this.poolBase + off1;
    if (len0 >= 3 && len1 >= 3 && this.readU16(s0) === len0 - 2 && this.readU16(s1) === len1 - 2) return "LEN_U16";
    if (len0 >= 2 && len1 >= 2 && this.readU8(s0) === len0 - 1 && this.readU8(s1) === len1 - 1) return "LEN_U8";
    if (len0 >= 2 && this.readU8(s0 + len0 - 1) === 0) return "NUL_TERM";
    return "RAW_SLICE";
  }
  readStringById(id) {
    if (id < 0 || id >= this.nStrings) return null;
    const rawOff = this.readU32(this.strOffsetsPos + id * 4);
    const start = this.poolBase + rawOff;
    if (start < 0 || start >= this.poolEnd) return null;
    const nextOff = id + 1 < this.nStrings ? this.readU32(this.strOffsetsPos + (id + 1) * 4) : this.poolEnd - this.poolBase;
    const sliceEnd = Math.min(Math.max(this.poolBase + nextOff, start), this.poolEnd);

    let dataStart = start;
    let dataEnd = sliceEnd;
    if (this.framing === "LEN_U8") {
      const len = this.readU8(start);
      dataStart = start + 1;
      dataEnd = Math.min(dataStart + len, this.poolEnd);
    } else if (this.framing === "LEN_U16") {
      const len = this.readU16(start);
      dataStart = start + 2;
      dataEnd = Math.min(dataStart + len, this.poolEnd);
    } else {
      while (dataEnd > dataStart && this.readU8(dataEnd - 1) === 0) dataEnd--;
    }
    const len = Math.min(Math.max(dataEnd - dataStart, 0), 512);
    if (len <= 0) return "";
    const buf = Buffer.allocUnsafe(len);
    fs.readSync(this.fd, buf, 0, len, dataStart);
    return buf.toString("utf8");
  }
  readEntryPair(index) {
    const b = Buffer.allocUnsafe(8);
    fs.readSync(this.fd, b, 0, 8, this.entriesPos + index * 8);
    return [b.readUInt32LE(0), b.readUInt32LE(4)];
  }
  binarySearchExact(key) {
    const target = Buffer.from(key, "utf8");
    let lo = 0;
    let hi = this.nEntries - 1;
    while (lo <= hi) {
      const mid = (lo + hi) >>> 1;
      const [kId, vId] = this.readEntryPair(mid);
      const midStr = this.readStringById(kId);
      if (midStr === null) return null;
      const cmp = Buffer.compare(Buffer.from(midStr, "utf8"), target);
      if (cmp === 0) return this.readStringById(vId);
      if (cmp < 0) lo = mid + 1;
      else hi = mid - 1;
    }
    return null;
  }
  lookupVietnamese(wordLower) {
    if (!this.valid || !wordLower) return null;
    if (this.detectedPrefix) {
      const hit = this.binarySearchExact(this.detectedPrefix + wordLower);
      if (hit) return hit;
    }
    return this.binarySearchExact(wordLower);
  }
  close() {
    try {
      fs.closeSync(this.fd);
    } catch {
      // ignore
    }
  }
}

// ============================================================================
// 3. Vietnamese IPA Phonemizer (matches Kotlin `VietnameseIpaPhonemizer.kt`)
// ============================================================================

const TONE_MAP = {
  à: ["a", 2], á: ["a", 3], ả: ["a", 4], ã: ["a", 5], ạ: ["a", 6],
  ằ: ["ă", 2], ắ: ["ă", 3], ẳ: ["ă", 4], ẵ: ["ă", 5], ặ: ["ă", 6],
  ầ: ["â", 2], ấ: ["â", 3], ẩ: ["â", 4], ẫ: ["â", 5], ậ: ["â", 6],
  è: ["e", 2], é: ["e", 3], ẻ: ["e", 4], ẽ: ["e", 5], ẹ: ["e", 6],
  ề: ["ê", 2], ế: ["ê", 3], ể: ["ê", 4], ễ: ["ê", 5], ệ: ["ê", 6],
  ì: ["i", 2], í: ["i", 3], ỉ: ["i", 4], ĩ: ["i", 5], ị: ["i", 6],
  ò: ["o", 2], ó: ["o", 3], ỏ: ["o", 4], õ: ["o", 5], ọ: ["o", 6],
  ồ: ["ô", 2], ố: ["ô", 3], ổ: ["ô", 4], ỗ: ["ô", 5], ộ: ["ô", 6],
  ờ: ["ơ", 2], ớ: ["ơ", 3], ở: ["ơ", 4], ỡ: ["ơ", 5], ợ: ["ơ", 6],
  ù: ["u", 2], ú: ["u", 3], ủ: ["u", 4], ũ: ["u", 5], ụ: ["u", 6],
  ừ: ["ư", 2], ứ: ["ư", 3], ử: ["ư", 4], ữ: ["ư", 5], ự: ["ư", 6],
  ỳ: ["y", 2], ý: ["y", 3], ỷ: ["y", 4], ỹ: ["y", 5], ỵ: ["y", 6]
};

function extractVietnameseTone(word) {
  let tone = 1;
  let base = "";
  for (const ch of word.normalize("NFC")) {
    if (TONE_MAP[ch]) {
      base += TONE_MAP[ch][0];
      if (TONE_MAP[ch][1] !== 1) tone = TONE_MAP[ch][1];
    } else {
      base += ch;
    }
  }
  return [base, tone];
}

function phonemizeWordRule(rawWord, opts) {
  if (!rawWord) return "";
  const [baseWord, toneIdx] = extractVietnameseTone(rawWord);
  let rest = baseWord;
  const tSym = opts.useDentalT ? "t\u032A" : "t";
  const thSym = opts.hasAspirationH ? "tʰ" : `${tSym}h`;
  let onset = "";
  if (rest.startsWith("ngh")) { onset = "ŋ"; rest = rest.slice(3); }
  else if (rest.startsWith("ng")) { onset = "ŋ"; rest = rest.slice(2); }
  else if (rest.startsWith("nh")) { onset = "ɲ"; rest = rest.slice(2); }
  else if (rest.startsWith("ch") || rest.startsWith("tr")) { onset = opts.chAffricate; rest = rest.slice(2); }
  else if (rest.startsWith("th")) { onset = thSym; rest = rest.slice(2); }
  else if (rest.startsWith("ph")) { onset = "f"; rest = rest.slice(2); }
  else if (rest.startsWith("kh")) { onset = "x"; rest = rest.slice(2); }
  else if (rest.startsWith("gh")) { onset = "ɣ"; rest = rest.slice(2); }
  else if (rest.startsWith("gi") && rest.length > 2) { onset = "z"; rest = rest.slice(2); }
  else if (rest.startsWith("gi")) { onset = "z"; rest = "i"; }
  else if (rest.startsWith("qu") && rest.length > 2) { onset = "kw"; rest = rest.slice(2); }
  else if (rest.startsWith("đ")) { onset = opts.useImplosiveD ? "ɗ" : "d"; rest = rest.slice(1); }
  else if (rest.startsWith("b")) { onset = opts.useImplosiveB ? "ɓ" : "b"; rest = rest.slice(1); }
  else if (rest.startsWith("c") || rest.startsWith("k")) { onset = "k"; rest = rest.slice(1); }
  else if (rest.startsWith("d") || rest.startsWith("r")) { onset = "z"; rest = rest.slice(1); }
  else if (rest.startsWith("g")) { onset = "ɣ"; rest = rest.slice(1); }
  else if (rest.startsWith("h")) { onset = "h"; rest = rest.slice(1); }
  else if (rest.startsWith("l")) { onset = "l"; rest = rest.slice(1); }
  else if (rest.startsWith("m")) { onset = "m"; rest = rest.slice(1); }
  else if (rest.startsWith("n")) { onset = "n"; rest = rest.slice(1); }
  else if (rest.startsWith("p")) { onset = "p"; rest = rest.slice(1); }
  else if (rest.startsWith("s") || rest.startsWith("x")) { onset = "s"; rest = rest.slice(1); }
  else if (rest.startsWith("t")) { onset = tSym; rest = rest.slice(1); }
  else if (rest.startsWith("v")) { onset = "v"; rest = rest.slice(1); }

  let medial = "";
  const nonMedial = new Set(["ua", "uô", "uo", "ui", "uôi", "uôn", "uông", "uôt", "uôc", "uôm"]);
  if (rest.length >= 2 && ((rest[0] === "o" && "aăe".includes(rest[1])) || (rest[0] === "u" && "âêyơai".includes(rest[1])))) {
    if (!nonMedial.has(rest)) {
      medial = "w";
      rest = rest.slice(1);
    }
  }

  let coda = "";
  if (rest.endsWith("ng") && rest.length > 2) { coda = "ŋ"; rest = rest.slice(0, -2); }
  else if (rest.endsWith("nh") && rest.length > 2) { coda = "ɲ"; rest = rest.slice(0, -2); }
  else if (rest.endsWith("ch") && rest.length > 2) { coda = "k"; rest = rest.slice(0, -2); }
  else if (rest.endsWith("m") && rest.length > 1) { coda = "m"; rest = rest.slice(0, -1); }
  else if (rest.endsWith("n") && rest.length > 1) { coda = "n"; rest = rest.slice(0, -1); }
  else if (rest.endsWith("p") && rest.length > 1) { coda = "p"; rest = rest.slice(0, -1); }
  else if (rest.endsWith("t") && rest.length > 1) { coda = tSym; rest = rest.slice(0, -1); }
  else if (rest.endsWith("c") && rest.length > 1) { coda = "k"; rest = rest.slice(0, -1); }
  else if (rest.endsWith("i") && rest.length > 1 && !["gi", "uôi", "ươi"].includes(rest)) { coda = "j"; rest = rest.slice(0, -1); }
  else if (rest.endsWith("y") && rest.length > 1) { coda = "j"; rest = rest.slice(0, -1); }
  else if (rest.endsWith("u") && rest.length > 1 && !["ưu", "iu", "ươu", "iêu", "yêu"].includes(rest)) { coda = "w"; rest = rest.slice(0, -1); }
  else if (rest.endsWith("o") && rest.length > 1 && (rest.endsWith("ao") || rest.endsWith("eo"))) { coda = "w"; rest = rest.slice(0, -1); }

  const longMark = opts.useLengthColon ? "ː" : "";
  const uBar = opts.uBarSymbol;
  let nucleus;
  switch (rest) {
    case "iê": case "yê": case "ia": case "ya": nucleus = "iɛ"; break;
    case "uô": case "ua": nucleus = "uə"; break;
    case "ươ": case "ưa": nucleus = `${uBar}ə`; break;
    case "i": case "y": nucleus = "i"; break;
    case "ê": nucleus = "e"; break;
    case "e": nucleus = "ɛ"; break;
    case "ư": nucleus = uBar; break;
    case "ơ": nucleus = `ə${longMark}`; break;
    case "â": nucleus = "ə"; break;
    case "a": nucleus = `a${longMark}`; break;
    case "ă": nucleus = "a"; break;
    case "u": nucleus = "u"; break;
    case "ô": nucleus = "o"; break;
    case "o": nucleus = "ɔ"; break;
    case "ưu": coda = "w"; nucleus = uBar; break;
    case "iu": coda = "w"; nucleus = "i"; break;
    case "uôi": coda = "j"; nucleus = "uə"; break;
    case "ươi": coda = "j"; nucleus = `${uBar}ə`; break;
    case "ươu": coda = "w"; nucleus = `${uBar}ə`; break;
    case "iêu": case "yêu": coda = "w"; nucleus = "iɛ"; break;
    default:
      nucleus = Array.from(rest).map((c) => ({ ă: "a", â: "ə", ê: "e", e: "ɛ", ô: "o", o: "ɔ", ơ: `ə${longMark}`, ư: uBar, y: "i" }[c] || c)).join("");
  }
  const toneStr = ["", "", "2", opts.sacSymbol, "4", opts.ngaSymbol, "6"][toneIdx] || "";
  return `${onset}${medial}${opts.useStress ? "ˈ" : ""}${nucleus}${toneStr}${coda}`;
}

function phonemizeVietnamese(sentence, tok, seaG2p) {
  const blob = Array.from(tok.decodedMerges).join(" ");
  const opts = {
    useStress: blob.includes("ˈ") || tok.decodedMerges.size === 0,
    useDentalT: blob.includes("\u032A") || blob.includes("t\u032A"),
    useLengthColon: blob.includes("ː") || tok.decodedMerges.size === 0,
    useImplosiveD: blob.includes("ɗ") || tok.decodedMerges.size === 0,
    useImplosiveB: blob.includes("ɓ"),
    hasAspirationH: blob.includes("ʰ"),
    uBarSymbol: blob.includes("ɯ") ? "ɯ" : "ᵻ",
    chAffricate: blob.includes("ɕ") ? "tɕ" : "tʃ",
    sacSymbol: blob.includes("ɜ") ? "ɜ" : "5",
    ngaSymbol: blob.includes("ɜ") ? "5" : "3"
  };
  const pieces = sentence.match(/[\p{L}\p{M}\d]+|[.,!?;:…—–-]/gu) || [];
  const out = [];
  let dictHits = 0;
  let ruleHits = 0;
  for (let i = 0; i < pieces.length; i++) {
    const p = pieces[i];
    if (p.length === 1 && !/[\p{L}\d]/u.test(p)) {
      const punct = p === "…" ? "." : "—–-".includes(p) ? "," : p;
      if (out.length > 0 && ".,!?;:".includes(punct)) out[out.length - 1] += punct;
      continue;
    }
    const lower = p.toLowerCase();
    if (seaG2p?.valid) {
      if (i + 1 < pieces.length && /^[\p{L}\p{M}\d]+$/u.test(pieces[i + 1])) {
        const bi = seaG2p.lookupVietnamese(`${lower} ${pieces[i + 1].toLowerCase()}`);
        if (bi) {
          out.push(bi.trim());
          dictHits++;
          i++;
          continue;
        }
      }
      const uni = seaG2p.lookupVietnamese(lower);
      if (uni) {
        out.push(uni.trim());
        dictHits++;
        continue;
      }
    }
    out.push(phonemizeWordRule(lower, opts));
    ruleHits++;
  }
  return { ipa: out.join(" "), dictHits, ruleHits };
}

// ============================================================================
// 4. Binary Weight Store (`heads.json` + `heads.bin`) & Math Helpers
// ============================================================================

function loadHeadsStore(headsJsonPath, headsBinPath) {
  const metaJson = JSON.parse(fs.readFileSync(headsJsonPath, "utf8"));
  const bin = fs.readFileSync(headsBinPath);
  const entries = {};
  let runningOffset = 0;
  const container = metaJson.tensors || metaJson.weights || metaJson.entries || metaJson;
  const addEntry = (name, obj) => {
    const shape = obj.shape || obj.dims || [];
    const dtype = String(obj.dtype || obj.type || "float32").toLowerCase();
    const bpe = dtype.includes("16") ? 2 : dtype.includes("8") ? 1 : 4;
    const elems = shape.reduce((a, b) => a * b, 1);
    const byteLen = obj.nbytes || obj.byteLength || obj.byte_length || elems * bpe;
    const offset = obj.offset ?? obj.byteOffset ?? obj.byte_offset ?? runningOffset;
    const floats = new Float32Array(elems);
    for (let i = 0; i < elems; i++) floats[i] = bin.readFloatLE(offset + i * 4);
    entries[name] = { shape, dtype, offset, floats };
    runningOffset = Math.max(runningOffset, offset + byteLen);
  };
  if (Array.isArray(container)) {
    container.forEach((o, i) => addEntry(o.name || o.key || `t_${i}`, o));
  } else {
    for (const [k, v] of Object.entries(container)) {
      if (v && typeof v === "object") addEntry(k, v);
    }
  }
  return entries;
}

function vectorNorm(v) {
  let sq = 0;
  for (let i = 0; i < v.length; i++) sq += v[i] * v[i];
  return Math.sqrt(sq);
}

function projectSpeakerEmbedding(spk, heads) {
  const wE = heads.xvec_w;
  const bE = heads.xvec_b;
  const lnW = heads.xvec_ln_w?.floats;
  const lnB = heads.xvec_ln_b?.floats;
  if (!wE) return null;
  const [rows, cols] = wE.shape;
  const out = new Float32Array(rows);
  for (let r = 0; r < rows; r++) {
    let acc = bE ? bE.floats[r] : 0;
    const off = r * cols;
    for (let c = 0; c < Math.min(cols, spk.length); c++) acc += wE.floats[off + c] * spk[c];
    out[r] = acc;
  }
  if (lnW || lnB) {
    let mean = 0;
    for (let i = 0; i < out.length; i++) mean += out[i];
    mean /= out.length;
    let variance = 0;
    for (let i = 0; i < out.length; i++) {
      const d = out[i] - mean;
      variance += d * d;
    }
    const invStd = 1 / Math.sqrt(variance / out.length + 1e-5);
    for (let i = 0; i < out.length; i++) {
      out[i] = (out[i] - mean) * invStd * (lnW ? lnW[i] : 1) + (lnB ? lnB[i] : 0);
    }
  }
  return out;
}

function softmaxStats(logits) {
  let maxV = -Infinity;
  for (let i = 0; i < logits.length; i++) if (Number.isFinite(logits[i]) && logits[i] > maxV) maxV = logits[i];
  let sumExp = 0;
  for (let i = 0; i < logits.length; i++) if (Number.isFinite(logits[i])) sumExp += Math.exp(logits[i] - maxV);
  let ent = 0;
  let maxP = 0;
  for (let i = 0; i < logits.length; i++) {
    if (!Number.isFinite(logits[i])) continue;
    const p = Math.exp(logits[i] - maxV) / sumExp;
    if (p > maxP) maxP = p;
    if (p > 1e-12) ent -= p * Math.log(p);
  }
  return { maxV, sumExp, ent, maxP };
}

function negativeLogProb(logits, targetIdx) {
  if (!logits || targetIdx < 0 || targetIdx >= logits.length) return 6.9315;
  const { maxV, sumExp } = softmaxStats(logits);
  if (!(sumExp > 0) || !Number.isFinite(logits[targetIdx])) return 6.9315;
  return Math.min(20, Math.max(0, Math.log(sumExp) - (logits[targetIdx] - maxV)));
}

function makeSeededRng(seed) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

function sampleLogits(logits, { temperature = 0.72, topK = 40, topP = 0.88, maxValid = 1023, recent = null, repPen = 1.0, rng }) {
  const limit = Math.min(maxValid + 1, logits.length);
  const adj = new Float32Array(limit);
  for (let i = 0; i < limit; i++) adj[i] = Number.isFinite(logits[i]) ? logits[i] : -1e9;
  if (recent && repPen > 1.0) {
    for (const t of recent) {
      if (t >= 0 && t < limit) adj[t] = adj[t] > 0 ? adj[t] / repPen : adj[t] * repPen;
    }
  }
  const invT = 1 / Math.max(temperature, 0.05);
  let maxL = -Infinity;
  for (let i = 0; i < limit; i++) {
    adj[i] *= invT;
    if (adj[i] > maxL) maxL = adj[i];
  }
  const idxs = Array.from({ length: limit }, (_, i) => i).sort((a, b) => adj[b] - adj[a]).slice(0, Math.min(topK, limit));
  const probs = idxs.map((i) => Math.exp(adj[i] - maxL));
  const sum = probs.reduce((a, b) => a + b, 0);
  if (!(sum > 0)) return idxs[0];
  let cum = 0;
  let kept = probs.length;
  for (let i = 0; i < probs.length; i++) {
    probs[i] /= sum;
    cum += probs[i];
    if (cum >= topP) {
      kept = i + 1;
      break;
    }
  }
  const keptSum = probs.slice(0, kept).reduce((a, b) => a + b, 0);
  let r = rng() * keptSum;
  for (let i = 0; i < kept; i++) {
    r -= probs[i];
    if (r <= 0) return idxs[i];
  }
  return idxs[0];
}

// ============================================================================
// 5. WAV Writer & Automated Audio Quality Analyzer
// ============================================================================

function writePcm16MonoWav(filePath, floatSamples, sampleRate) {
  let peak = 0;
  for (let i = 0; i < floatSamples.length; i++) {
    const a = Math.abs(floatSamples[i]);
    if (Number.isFinite(a) && a > peak) peak = a;
  }
  const gain = peak > 0.01 ? Math.min(0.90 / peak, 3.0) : 1.0;
  const fade = Math.min(Math.floor(sampleRate * 0.005), Math.floor(floatSamples.length / 8));

  const pcmBytes = floatSamples.length * 2;
  const buf = Buffer.alloc(44 + pcmBytes);
  buf.write("RIFF", 0, "ascii");
  buf.writeUInt32LE(36 + pcmBytes, 4);
  buf.write("WAVE", 8, "ascii");
  buf.write("fmt ", 12, "ascii");
  buf.writeUInt32LE(16, 16);
  buf.writeUInt16LE(1, 20); // PCM
  buf.writeUInt16LE(1, 22); // Mono
  buf.writeUInt32LE(sampleRate, 24);
  buf.writeUInt32LE(sampleRate * 2, 28);
  buf.writeUInt16LE(2, 32);
  buf.writeUInt16LE(16, 34);
  buf.write("data", 36, "ascii");
  buf.writeUInt32LE(pcmBytes, 40);

  for (let i = 0; i < floatSamples.length; i++) {
    let env = 1.0;
    if (fade > 0 && i < fade) env = i / fade;
    else if (fade > 0 && i >= floatSamples.length - fade) env = (floatSamples.length - 1 - i) / fade;
    const raw = Number.isFinite(floatSamples[i]) ? floatSamples[i] * gain * env : 0;
    const clamped = Math.max(-1, Math.min(1, raw));
    buf.writeInt16LE(Math.round(clamped * 32767), 44 + i * 2);
  }
  fs.writeFileSync(filePath, buf);
  return { fileSize: buf.length, peakRaw: peak, gainApplied: gain };
}

function analyzeAudioQuality(samples, sampleRate) {
  const n = samples.length;
  const durationSec = n / sampleRate;
  let sqSum = 0;
  let peak = 0;
  let nanCount = 0;
  let zeroCrossings = 0;
  for (let i = 0; i < n; i++) {
    const v = samples[i];
    if (!Number.isFinite(v)) {
      nanCount++;
      continue;
    }
    sqSum += v * v;
    const a = Math.abs(v);
    if (a > peak) peak = a;
    if (i > 0 && ((samples[i - 1] >= 0 && v < 0) || (samples[i - 1] < 0 && v >= 0))) zeroCrossings++;
  }
  const rms = n > 0 ? Math.sqrt(sqSum / n) : 0;
  const zcr = n > 1 ? zeroCrossings / (n - 1) : 0;

  // Compute 50ms frame RMS envelope modulation (distinguishes speech syllables from steady white noise / tone)
  const frameLen = Math.max(1, Math.floor(sampleRate * 0.05));
  const frameRms = [];
  for (let off = 0; off + frameLen <= n; off += frameLen) {
    let fs2 = 0;
    for (let j = 0; j < frameLen; j++) {
      const s = samples[off + j];
      if (Number.isFinite(s)) fs2 += s * s;
    }
    frameRms.push(Math.sqrt(fs2 / frameLen));
  }
  const meanFrameRms = frameRms.length > 0 ? frameRms.reduce((a, b) => a + b, 0) / frameRms.length : 0;
  const varFrameRms = frameRms.length > 0
    ? frameRms.reduce((a, b) => a + (b - meanFrameRms) ** 2, 0) / frameRms.length
    : 0;
  const envelopeModulation = meanFrameRms > 1e-6 ? Math.sqrt(varFrameRms) / meanFrameRms : 0;

  return { durationSec, rms, peak, nanCount, zcr, envelopeModulation };
}

// ============================================================================
// 6. Main ONNX Execution + Self-Calibration + Audio Synthesis
// ============================================================================

async function main() {
  log("=== AUTOMATED VIENEU-TTS V3 TURBO ONNX AUDIO TEST ===");
  log(`Models dir : ${MODELS_DIR}`);
  log(`Output WAV : ${OUTPUT_WAV}`);
  log(`Test text  : "${TEST_SENTENCE}"`);
  log("");

  if (!fs.existsSync(MODELS_DIR)) {
    throw new Error(`Models directory does not exist: ${MODELS_DIR}`);
  }

  const configPath = path.join(MODELS_DIR, "config.json");
  const tokenizerPath = path.join(MODELS_DIR, "tokenizer.json");
  const voicesPath = path.join(MODELS_DIR, "voices.json");
  const headsJsonPath = path.join(MODELS_DIR, "heads.json");
  const headsBinPath = path.join(MODELS_DIR, "heads.bin");
  const seaG2pPath = path.join(MODELS_DIR, "sea_g2p.bin");
  const manifestPath = path.join(MODELS_DIR, "manifest.json");
  const codecMetaPath = path.join(MODELS_DIR, "codec_browser_onnx_meta.json");

  const config = JSON.parse(fs.readFileSync(configPath, "utf8"));
  const tok = parseTokenizer(tokenizerPath);
  const heads = loadHeadsStore(headsJsonPath, headsBinPath);
  const voicesRaw = JSON.parse(fs.readFileSync(voicesPath, "utf8"));
  const seaG2p = fs.existsSync(seaG2pPath) ? new SeaG2pReader(seaG2pPath) : null;

  log("--- 1. METADATA INSPECTION ---");
  log(`config.json      : ${JSON.stringify(config)}`);
  if (fs.existsSync(codecMetaPath)) log(`codec_meta.json  : ${fs.readFileSync(codecMetaPath, "utf8").replace(/\s+/g, " ").slice(0, 500)}`);
  if (fs.existsSync(manifestPath)) log(`manifest.json    : ${fs.readFileSync(manifestPath, "utf8").replace(/\s+/g, " ").slice(0, 500)}`);
  log(`heads.json       : ${Object.entries(heads).map(([k, v]) => `${k}:${v.dtype}[${v.shape.join("x")}]`).join(", ")}`);
  log(`tokenizer        : size=${tok.tokenToId.size}, byteBpe=${tok.isByteLevelBpe}, merges=${tok.merges.length}, pre=${tok.preSummary}`);
  log(`sea_g2p.bin      : ${seaG2p ? seaG2p.summary : "(missing)"}`);

  // Extract first preset voice with speakerEmbedding + referenceCodes (+ optional refText)
  const voiceList = Array.isArray(voicesRaw.voices)
    ? voicesRaw.voices
    : Object.entries(voicesRaw.presets || voicesRaw.voices || voicesRaw).map(([id, v]) => ({ id, ...v }));
  const preset = voiceList.find((v) => Array.isArray(v.speakerEmbedding) && Array.isArray(v.referenceCodes)) || voiceList[0];
  if (!preset) throw new Error("No preset voice found in voices.json");
  const presetName = preset.name || preset.id || "preset_0";
  const spkEmb = Float32Array.from(preset.speakerEmbedding || []);
  const refCodes = preset.referenceCodes || [];
  const refText = preset.text || preset.ref_text || preset.refText || "";
  log(`selected voice   : "${presetName}" | spkEmb[${spkEmb.length}, norm=${vectorNorm(spkEmb).toFixed(2)}] | refCodes[${refCodes.length}x${refCodes[0]?.length || 0}] | refText="${refText}"`);

  // Phonemize & tokenize
  const fullSentence = refText ? `${refText.trim()} ${TEST_SENTENCE}` : TEST_SENTENCE;
  const { ipa, dictHits, ruleHits } = phonemizeVietnamese(fullSentence, tok, seaG2p);
  const bosId = config.bos_token_id ?? tok.tokenToId.get("<|bos|>") ?? 1;
  const eosId = config.eos_token_id ?? tok.tokenToId.get("<|eos|>") ?? 2;
  const textStartId = tok.tokenToId.get("<|TEXT_PROMPT_START|>") ?? 3;
  const textEndId = tok.tokenToId.get("<|TEXT_PROMPT_END|>") ?? 4;
  const speechStartId = tok.tokenToId.get("<|SPEECH_GENERATION_START|>") ?? 5;
  const speechEndId = tok.tokenToId.get("<|SPEECH_GENERATION_END|>") ?? 6;
  const audioRefSlotId = config.audio_ref_slot_token_id ?? tok.tokenToId.get("<|audio_ref_slot|>") ?? 7;
  const defaultEmotionId = tok.tokenToId.get("<|emotion_0|>") ?? 8;
  const defaultStyleId = config.default_style_token_id ?? tok.tokenToId.get("<|style_0|>") ?? 16;
  const hiddenSize = config.hidden_size || 768;
  const sampleRate = config.audio_sample_rate || 48000;
  const codebookSize = config.audio_vocab_size || 1024;
  const numCodebooks = 16;

  const contentIds = encodeByteLevelBpe(ipa, tok).filter((id) => ![bosId, eosId, speechStartId, speechEndId].includes(id));
  log("");
  log("--- 2. G2P & BYTELEVEL BPE TOKENIZATION ---");
  log(`G2P hits         : sea_g2p.bin=${dictHits}, rules=${ruleHits}`);
  log(`IPA string       : "${ipa}"`);
  log(`Token IDs (${contentIds.length})   : [${contentIds.join(", ")}]`);

  // Load ONNX sessions by absolute file path (resolves sibling .data external weights automatically)
  log("");
  log("--- 3. LOADING ONNX SESSIONS ---");
  const sessOpts = { executionProviders: ["cpu"], intraOpNumThreads: 4, graphOptimizationLevel: "basic" };
  const loadSess = async (fname) => {
    const full = path.join(MODELS_DIR, fname);
    if (!fs.existsSync(full)) throw new Error(`Missing model file: ${full}`);
    return ort.InferenceSession.create(full, sessOpts);
  };

  const prefillSess = await loadSess("vieneu_prefill.onnx");
  const decodeStepSess = await loadSess("vieneu_decode_step.onnx");
  const acousticSess = await loadSess("vieneu_acoustic_cached.onnx");
  const textHeadSess = await loadSess("text_head_gather.onnx");
  const audioHeadSesses = [];
  for (let q = 0; q < numCodebooks; q++) audioHeadSesses.push(await loadSess(`audio_head_${q}.onnx`));
  const mossDecodeSess = await loadSess("moss_audio_tokenizer_decode_full.onnx");
  log(`Loaded all ${4 + audioHeadSesses.length + 1} ONNX sessions OK.`);

  const zeroVec = new Float32Array(hiddenSize);
  const textEmbTable = heads.text_emb?.floats;
  const lookupTextEmb = (id) => {
    const out = new Float32Array(hiddenSize);
    if (textEmbTable) {
      const off = id * hiddenSize;
      out.set(textEmbTable.subarray(off, off + hiddenSize));
    }
    return out;
  };
  const xvec768 = projectSpeakerEmbedding(spkEmb, heads);

  const runHeadGather = async (sess, vec, codes) => {
    const feeds = {};
    for (const name of sess.inputNames) {
      const lower = name.toLowerCase();
      if (lower.includes("code") || lower.includes("id")) {
        feeds[name] = new ort.Tensor("int32", Int32Array.from(codes), [codes.length]);
      } else {
        feeds[name] = new ort.Tensor("float32", vec, [1, hiddenSize]);
      }
    }
    const res = await sess.run(feeds);
    const outNames = sess.outputNames;
    return {
      logits: res[outNames[0]]?.data || new Float32Array(0),
      embT: outNames.length >= 2 ? res[outNames[1]]?.data : null
    };
  };

  const embedRefFrames = async (codesMatrix, scale) => {
    const T = Math.min(codesMatrix.length, 96);
    if (T <= 0) return [];
    const frames = Array.from({ length: T }, () => new Float32Array(hiddenSize));
    for (let q = 0; q < numCodebooks; q++) {
      const col = new Array(T);
      for (let t = 0; t < T; t++) col[t] = Math.max(0, Math.min(codebookSize - 1, codesMatrix[t][q] ?? 0));
      const { embT } = await runHeadGather(audioHeadSesses[q], zeroVec, col);
      if (embT && embT.length >= hiddenSize * T) {
        for (let d = 0; d < hiddenSize; d++) {
          const rowOff = d * T;
          for (let t = 0; t < T; t++) frames[t][d] += embT[rowOff + t];
        }
      }
    }
    if (scale !== 1.0) {
      for (let t = 0; t < T; t++) for (let d = 0; d < hiddenSize; d++) frames[t][d] *= scale;
    }
    return frames;
  };

  const runPrefill = async (vectors) => {
    const seqLen = vectors.length;
    const flat = new Float32Array(seqLen * hiddenSize);
    for (let i = 0; i < seqLen; i++) flat.set(vectors[i], i * hiddenSize);
    const feeds = { [prefillSess.inputNames[0]]: new ort.Tensor("float32", flat, [1, seqLen, hiddenSize]) };
    const res = await prefillSess.run(feeds);
    const allH = res[prefillSess.outputNames[0]].data;
    const lastH = allH.slice(allH.length - hiddenSize);
    const kv = {};
    for (const n of prefillSess.outputNames.slice(1)) kv[n] = res[n];
    return { allH, lastH, kv };
  };

  const runDecodeStep = async (stepEmb, pos, pastKv) => {
    const feeds = {};
    for (const name of decodeStepSess.inputNames) {
      const lower = name.toLowerCase();
      if (lower.startsWith("past_")) {
        feeds[name] = pastKv[name.replace("past_", "present_")];
      } else if (lower.includes("pos")) {
        feeds[name] = new ort.Tensor("int64", BigInt64Array.from([BigInt(pos)]), [1, 1]);
      } else {
        feeds[name] = new ort.Tensor("float32", stepEmb, [1, 1, hiddenSize]);
      }
    }
    const res = await decodeStepSess.run(feeds);
    const allH = res[decodeStepSess.outputNames[0]].data;
    const lastH = allH.slice(allH.length - hiddenSize);
    const kv = {};
    for (const n of decodeStepSess.outputNames.slice(1)) kv[n] = res[n];
    return { lastH, kv };
  };

  const runAcousticMulti = async (tokenEmbeds, seqLen, positions, pastKv) => {
    const feeds = {};
    for (const name of acousticSess.inputNames) {
      const lower = name.toLowerCase();
      if (lower.startsWith("past_")) {
        const key = name.replace("past_", "present_");
        feeds[name] = pastKv[key] || new ort.Tensor("float32", new Float32Array(0), [1, 8, 0, 96]);
      } else if (lower.includes("pos")) {
        feeds[name] = new ort.Tensor("int64", BigInt64Array.from(positions.map(BigInt)), [1, seqLen]);
      } else {
        feeds[name] = new ort.Tensor("float32", tokenEmbeds, [1, seqLen, hiddenSize]);
      }
    }
    const res = await acousticSess.run(feeds);
    const allH = res[acousticSess.outputNames[0]].data;
    const lastH = allH.slice(allH.length - hiddenSize);
    const kv = {};
    for (const n of acousticSess.outputNames.slice(1)) kv[n] = res[n];
    return { lastH, kv };
  };

  const textHeadTop3 = async (h) => {
    const { logits } = await runHeadGather(textHeadSess, h, [0]);
    const { maxV, sumExp } = softmaxStats(logits);
    const top = Array.from({ length: logits.length }, (_, i) => i).sort((a, b) => logits[b] - logits[a]).slice(0, 3);
    return top.map((idx) => {
      const p = Math.exp(logits[idx] - maxV) / sumExp;
      const raw = tok.idToToken.get(idx) || String(idx);
      const lbl = tok.isByteLevelBpe && !raw.startsWith("<|") ? decodeByteLevelToUtf8(raw).replace(/ /g, "␠") : raw;
      return `${idx}(${lbl})=${p.toFixed(2)}`;
    }).join(",");
  };

  const probTextToken = async (h, tid) => {
    const { logits } = await runHeadGather(textHeadSess, h, [0]);
    const { maxV, sumExp } = softmaxStats(logits);
    return sumExp > 0 ? Math.exp(logits[tid] - maxV) / sumExp : 0;
  };

  // ============================================================================
  // 7. Ground-Truth Self-Calibration (same math as Android `VieNeuV3Calibration`)
  // ============================================================================

  log("");
  log("--- 4. SELF-CALIBRATION AGAINST REAL voices.json & ONNX WEIGHTS ---");
  const avgTextNorm = [bosId, textStartId, speechStartId, audioRefSlotId, defaultStyleId, 100, 305]
    .reduce((a, id) => a + vectorNorm(lookupTextEmb(id)), 0) / 7;
  const rawRef14 = refCodes.length >= 14 ? await embedRefFrames(refCodes.slice(0, 14), 1.0) : [];
  const head0Emb0 = refCodes.length > 0 ? (await runHeadGather(audioHeadSesses[0], zeroVec, [refCodes[0][0]])).embT : null;
  log(`norms            : text=${avgTextNorm.toFixed(2)}, refSlot=${vectorNorm(lookupTextEmb(audioRefSlotId)).toFixed(2)}, xvec=${xvec768 ? vectorNorm(xvec768).toFixed(2) : "0"}, head0=${head0Emb0 ? vectorNorm(head0Emb0).toFixed(2) : "0"}, sum16=${rawRef14[0] ? vectorNorm(rawRef14[0]).toFixed(2) : "0"}`);

  const probeNext = async (ids) => textHeadTop3((await runPrefill(ids.map(lookupTextEmb))).lastH);
  log(`grammar probes   : bos->[${await probeNext([bosId])}] | bos+style->[${await probeNext([bosId, defaultStyleId])}] | bos+refSlot->[${await probeNext([bosId, audioRefSlotId])}] | bos+txtStart->[${await probeNext([bosId, textStartId])}] | bos+txtEnd->[${await probeNext([bosId, textStartId, ...contentIds.slice(0, 12), textEndId])}]`);

  let bestScale = 1.0;
  let bestAcousticMode = "AC_ALL_16";
  let bestJointNll = Infinity;

  if (refCodes.length >= 14) {
    const evalFrames = 10;
    const evalSlice = refCodes.slice(0, evalFrames + 1);
    for (const [scale, label] of [[1.0, "1"], [0.25, "1/4"], [1 / 16, "1/16"]]) {
      const fEmbeds = await embedRefFrames(evalSlice, scale);
      const seqVecs = [lookupTextEmb(speechStartId), ...fEmbeds.slice(0, evalFrames)];
      const { allH } = await runPrefill(seqVecs);

      let nllC0Dir = 0, nllC0Ac = 0, nllC1All = 0, nllC1Pair = 0, nllC1Solo = 0, cnt = 0;
      for (let t = 2; t < evalFrames; t++) {
        const h = allH.slice(t * hiddenSize, (t + 1) * hiddenSize);
        const gt0 = evalSlice[t][0];
        const gt1 = evalSlice[t][1];

        nllC0Dir += negativeLogProb((await runHeadGather(audioHeadSesses[0], h, [0])).logits, gt0);

        const ac0 = await runAcousticMulti(h, 1, [0], {});
        nllC0Ac += negativeLogProb((await runHeadGather(audioHeadSesses[0], ac0.lastH, [0])).logits, gt0);

        const emb0 = (await runHeadGather(audioHeadSesses[0], zeroVec, [gt0])).embT;
        const ac1All = await runAcousticMulti(emb0, 1, [1], ac0.kv);
        nllC1All += negativeLogProb((await runHeadGather(audioHeadSesses[1], ac1All.lastH, [0])).logits, gt1);

        const pair = new Float32Array(hiddenSize * 2);
        pair.set(h, 0);
        pair.set(emb0, hiddenSize);
        const ac1Pair = await runAcousticMulti(pair, 2, [0, 1], {});
        nllC1Pair += negativeLogProb((await runHeadGather(audioHeadSesses[1], ac1Pair.lastH, [0])).logits, gt1);

        const ac1Solo = await runAcousticMulti(emb0, 1, [0], {});
        nllC1Solo += negativeLogProb((await runHeadGather(audioHeadSesses[1], ac1Solo.lastH, [0])).logits, gt1);
        cnt++;
      }
      const mC0Dir = nllC0Dir / cnt, mC0Ac = nllC0Ac / cnt, mC1All = nllC1All / cnt, mC1Pair = nllC1Pair / cnt, mC1Solo = nllC1Solo / cnt;
      log(`teacherForce s=${label.padEnd(4)}: AC_ALL_16=${mC0Ac.toFixed(2)}+${mC1All.toFixed(2)} | DIRECT_C0_PAIR=${mC0Dir.toFixed(2)}+${mC1Pair.toFixed(2)} | DIRECT_C0_SOLO=${mC0Dir.toFixed(2)}+${mC1Solo.toFixed(2)}`);
      for (const [mode, score] of [["AC_ALL_16", mC0Ac + mC1All], ["DIRECT_C0_PAIR", mC0Dir + mC1Pair], ["DIRECT_C0_SOLO", mC0Dir + mC1Solo]]) {
        if (score < bestJointNll) {
          bestJointNll = score;
          bestScale = scale;
          bestAcousticMode = mode;
        }
      }
    }
  }
  log(`=> Selected audioScale=${bestScale.toFixed(4)}, acousticMode=${bestAcousticMode} (best NLL=${bestJointNll.toFixed(2)})`);

  const assemblePrompt = (layout, refAddBias, refEmbeds, ids) => {
    const out = [];
    const biasVec = refAddBias ? lookupTextEmb(audioRefSlotId) : null;
    const appendVoice = () => {
      out.push(xvec768 || lookupTextEmb(audioRefSlotId));
      for (const rf of refEmbeds) {
        if (biasVec) {
          const c = new Float32Array(hiddenSize);
          for (let d = 0; d < hiddenSize; d++) c[d] = rf[d] + biasVec[d];
          out.push(c);
        } else {
          out.push(rf);
        }
      }
    };
    const appendText = () => {
      out.push(lookupTextEmb(textStartId));
      for (const id of ids) out.push(lookupTextEmb(id));
      out.push(lookupTextEmb(textEndId));
    };
    if (layout === "BOS_STYLE_XVEC_REF_TEXT") { out.push(lookupTextEmb(bosId), lookupTextEmb(defaultStyleId)); appendVoice(); appendText(); out.push(lookupTextEmb(speechStartId)); }
    else if (layout === "BOS_XVEC_REF_TEXT") { out.push(lookupTextEmb(bosId)); appendVoice(); appendText(); out.push(lookupTextEmb(speechStartId)); }
    else if (layout === "BOS_REF_STYLE_TEXT") { out.push(lookupTextEmb(bosId)); appendVoice(); out.push(lookupTextEmb(defaultStyleId)); appendText(); out.push(lookupTextEmb(speechStartId)); }
    else if (layout === "BOS_TEXT_XVEC_REF") { out.push(lookupTextEmb(bosId)); appendText(); appendVoice(); out.push(lookupTextEmb(speechStartId)); }
    else if (layout === "BOS_TEXT_STYLE_XVEC_REF") { out.push(lookupTextEmb(bosId)); appendText(); out.push(lookupTextEmb(defaultStyleId)); appendVoice(); out.push(lookupTextEmb(speechStartId)); }
    else if (layout === "BOS_STYLE_EMO_XVEC_REF_TEXT") { out.push(lookupTextEmb(bosId), lookupTextEmb(defaultStyleId), lookupTextEmb(defaultEmotionId)); appendVoice(); appendText(); out.push(lookupTextEmb(speechStartId)); }
    else { out.push(lookupTextEmb(bosId), lookupTextEmb(defaultStyleId), xvec768 || lookupTextEmb(audioRefSlotId)); appendText(); out.push(lookupTextEmb(speechStartId)); }
    return out;
  };

  const shortRefEmbeds = refCodes.length > 0 ? await embedRefFrames(refCodes.slice(0, Math.min(12, refCodes.length)), bestScale) : [];
  const sampleIds = contentIds.slice(0, Math.min(16, contentIds.length));
  const layoutCandidates = [
    ["BOS_STYLE_XVEC_REF_TEXT", false],
    ["BOS_STYLE_XVEC_REF_TEXT", true],
    ["BOS_XVEC_REF_TEXT", false],
    ["BOS_REF_STYLE_TEXT", false],
    ["BOS_TEXT_XVEC_REF", false],
    ["BOS_TEXT_STYLE_XVEC_REF", false],
    ["BOS_STYLE_EMO_XVEC_REF_TEXT", false],
    ["BOS_XVEC_ONLY_TEXT", false]
  ];
  let bestLayout = "BOS_STYLE_XVEC_REF_TEXT";
  let bestRefBias = false;
  let bestLayoutScore = -Infinity;
  for (const [layout, bias] of layoutCandidates) {
    const vecs = assemblePrompt(layout, bias, shortRefEmbeds, sampleIds);
    const { allH } = await runPrefill(vecs);
    const preH = allH.slice((vecs.length - 2) * hiddenSize, (vecs.length - 1) * hiddenSize);
    const ssH = allH.slice((vecs.length - 1) * hiddenSize);
    const pSS = await probTextToken(preH, speechStartId);
    let c0Logits;
    if (bestAcousticMode === "AC_ALL_16") {
      const ac0 = await runAcousticMulti(ssH, 1, [0], {});
      c0Logits = (await runHeadGather(audioHeadSesses[0], ac0.lastH, [0])).logits;
    } else {
      c0Logits = (await runHeadGather(audioHeadSesses[0], ssH, [0])).logits;
    }
    const { ent: H0, maxP: p0 } = softmaxStats(c0Logits);
    const score = pSS * 3.0 + p0 * 2.0 - H0 * 0.5;
    log(`layout ${layout.padEnd(28)}(bias=${String(bias).padEnd(5)}): pSS=${pSS.toFixed(2)}, H0=${H0.toFixed(2)}, p0=${p0.toFixed(2)}, score=${score.toFixed(2)}`);
    if (score > bestLayoutScore) {
      bestLayoutScore = score;
      bestLayout = layout;
      bestRefBias = bias;
    }
  }
  log(`=> Selected layout=${bestLayout} (refAddBias=${bestRefBias})`);

  // ============================================================================
  // 8. Full Sentence Synthesis -> MOSS 48kHz Decode -> `test_voice.wav`
  // ============================================================================

  log("");
  log("--- 5. SYNTHESIZING TEST SENTENCE ---");
  const startMs = Date.now();
  const fullRefEmbeds = refCodes.length > 0 ? await embedRefFrames(refCodes, bestScale) : [];
  const promptVecs = assemblePrompt(bestLayout, bestRefBias, fullRefEmbeds, contentIds);
  log(`Prompt shape     : [1, ${promptVecs.length}, ${hiddenSize}] (refFrames=${fullRefEmbeds.length}, textTokens=${contentIds.length})`);

  let { lastH: backboneH, kv: backboneKv } = await runPrefill(promptVecs);
  log(`Prefill textHead : [${await textHeadTop3(backboneH)}]`);

  const wordCount = TEST_SENTENCE.trim().split(/\s+/).length;
  const minFrames = Math.max(18, Math.round(wordCount * 1.3));
  const maxFrames = Math.min(220, Math.max(48, Math.round(wordCount * 5.2)));
  const generatedFrames = [];
  const recentC0 = [];
  const rng = makeSeededRng(20261127);
  let pos = promptVecs.length;
  let stopReason = "max_frames";
  let repeatRun = 0;
  let prevC0 = -1;

  for (let f = 0; f < maxFrames; f++) {
    if (f >= minFrames) {
      const pEnd = await probTextToken(backboneH, speechEndId);
      const pEos = await probTextToken(backboneH, eosId);
      if (pEnd + pEos >= 0.35) {
        stopReason = `speech_end@${f}`;
        break;
      }
    }

    const codes = new Int32Array(numCodebooks);
    const embSum = new Float32Array(hiddenSize);
    let acKv = {};

    if (bestAcousticMode === "AC_ALL_16") {
      let curIn = backboneH;
      for (let q = 0; q < numCodebooks; q++) {
        const ac = await runAcousticMulti(curIn, 1, [q], acKv);
        acKv = ac.kv;
        const { logits } = await runHeadGather(audioHeadSesses[q], ac.lastH, [0]);
        const c = sampleLogits(logits, {
          temperature: q === 0 ? 0.72 : 0.55,
          topK: q === 0 ? 40 : 24,
          topP: 0.88,
          maxValid: codebookSize - 1,
          recent: q === 0 ? recentC0 : null,
          repPen: q === 0 ? 1.14 : 1.0,
          rng
        });
        codes[q] = c;
        const { embT } = await runHeadGather(audioHeadSesses[q], zeroVec, [c]);
        curIn = embT;
        for (let d = 0; d < hiddenSize; d++) embSum[d] += embT[d];
      }
    } else {
      const { logits: logits0 } = await runHeadGather(audioHeadSesses[0], backboneH, [0]);
      const c0 = sampleLogits(logits0, {
        temperature: 0.72, topK: 40, topP: 0.88, maxValid: codebookSize - 1, recent: recentC0, repPen: 1.14, rng
      });
      codes[0] = c0;
      let prevEmb = (await runHeadGather(audioHeadSesses[0], zeroVec, [c0])).embT;
      for (let d = 0; d < hiddenSize; d++) embSum[d] += prevEmb[d];

      for (let q = 1; q < numCodebooks; q++) {
        let ac;
        if (q === 1 && bestAcousticMode === "DIRECT_C0_PAIR") {
          const pair = new Float32Array(hiddenSize * 2);
          pair.set(backboneH, 0);
          pair.set(prevEmb, hiddenSize);
          ac = await runAcousticMulti(pair, 2, [0, 1], acKv);
        } else {
          const pIdx = bestAcousticMode === "DIRECT_C0_SOLO" ? q - 1 : q;
          ac = await runAcousticMulti(prevEmb, 1, [pIdx], acKv);
        }
        acKv = ac.kv;
        const { logits } = await runHeadGather(audioHeadSesses[q], ac.lastH, [0]);
        const c = sampleLogits(logits, { temperature: 0.55, topK: 24, topP: 0.88, maxValid: codebookSize - 1, rng });
        codes[q] = c;
        prevEmb = (await runHeadGather(audioHeadSesses[q], zeroVec, [c])).embT;
        for (let d = 0; d < hiddenSize; d++) embSum[d] += prevEmb[d];
      }
    }

    if (bestScale !== 1.0) {
      for (let d = 0; d < hiddenSize; d++) embSum[d] *= bestScale;
    }
    generatedFrames.push(codes);
    if (codes[0] === prevC0) {
      repeatRun++;
      if (repeatRun >= 14 && f >= minFrames) {
        stopReason = `repeat_guard@${f}`;
        break;
      }
    } else {
      repeatRun = 0;
      prevC0 = codes[0];
    }
    recentC0.push(codes[0]);
    if (recentC0.length > 24) recentC0.shift();

    const step = await runDecodeStep(embSum, pos++, backboneKv);
    backboneH = step.lastH;
    backboneKv = step.kv;
  }

  const uniqueC0 = new Set(generatedFrames.map((r) => r[0])).size;
  log(`Generated frames : ${generatedFrames.length} (stop=${stopReason}, uniqueC0=${uniqueC0}, textHead@end=[${await textHeadTop3(backboneH)}])`);
  log(`c0[0..15]        : [${generatedFrames.slice(0, 16).map((r) => r[0]).join(", ")}]`);

  // Decode via MOSS-Audio-Tokenizer-Nano
  const flatCodes = new Int32Array(generatedFrames.length * numCodebooks);
  for (let f = 0; f < generatedFrames.length; f++) {
    flatCodes.set(generatedFrames[f], f * numCodebooks);
  }
  const codecFeeds = {};
  for (const name of mossDecodeSess.inputNames) {
    if (name.toLowerCase().includes("len")) {
      codecFeeds[name] = new ort.Tensor("int32", Int32Array.from([generatedFrames.length]), [1]);
    } else {
      codecFeeds[name] = new ort.Tensor("int32", flatCodes, [1, generatedFrames.length, numCodebooks]);
    }
  }
  const codecRes = await mossDecodeSess.run(codecFeeds);
  const audioOut = codecRes[mossDecodeSess.outputNames[0]];
  const declLen = codecRes[mossDecodeSess.outputNames[1]]?.data?.[0] ?? audioOut.data.length;
  const outShape = audioOut.dims;
  const rawData = audioOut.data;

  let mono;
  if (outShape.length === 3 && outShape[1] === 2) {
    const half = Math.floor(rawData.length / 2);
    const cnt = declLen >= 256 && declLen <= half ? declLen : half;
    mono = new Float32Array(cnt);
    for (let i = 0; i < cnt; i++) mono[i] = (rawData[i] + rawData[half + i]) * 0.5;
  } else if (outShape.length === 3 && outShape[2] === 2) {
    const framesA = Math.floor(rawData.length / 2);
    const cnt = declLen >= 256 && declLen <= framesA ? declLen : framesA;
    mono = new Float32Array(cnt);
    for (let i = 0; i < cnt; i++) mono[i] = (rawData[i * 2] + rawData[i * 2 + 1]) * 0.5;
  } else {
    const cnt = declLen >= 256 && declLen <= rawData.length ? declLen : rawData.length;
    mono = rawData.slice(0, cnt);
  }

  const wavInfo = writePcm16MonoWav(OUTPUT_WAV, mono, sampleRate);
  const metrics = analyzeAudioQuality(mono, sampleRate);
  const elapsedSec = ((Date.now() - startMs) / 1000).toFixed(2);

  log("");
  log("--- 6. AUDIO QUALITY VERIFICATION (test_voice.wav) ---");
  log(`Codec out shape  : [${outShape.join("x")}], declLen=${declLen}, elapsed=${elapsedSec}s`);
  log(`WAV file         : ${OUTPUT_WAV} (${(wavInfo.fileSize / 1024).toFixed(1)} KB, PCM 16-bit Mono @ ${sampleRate} Hz)`);
  log(`Duration         : ${metrics.durationSec.toFixed(2)} s`);
  log(`RMS amplitude    : ${metrics.rms.toFixed(4)} (peakRaw=${metrics.peak.toFixed(4)}, gain=${wavInfo.gainApplied.toFixed(2)})`);
  log(`Zero-Cross Rate  : ${metrics.zcr.toFixed(4)} (speech ~0.005..0.22; white noise ~0.45..0.55)`);
  log(`Envelope Mod     : ${metrics.envelopeModulation.toFixed(4)} (syllabic RMS std/mean)`);
  log(`NaN/Inf samples  : ${metrics.nanCount}`);

  const failures = [];
  if (metrics.nanCount > 0) failures.push(`Có ${metrics.nanCount} mẫu NaN/Inf trong sóng âm đầu ra.`);
  if (metrics.durationSec < 1.5) failures.push(`Thời lượng quá ngắn: ${metrics.durationSec.toFixed(2)}s < 1.50s.`);
  if (metrics.rms < 0.008) failures.push(`Âm thanh gần như im lặng: RMS=${metrics.rms.toFixed(4)} < 0.008.`);
  if (metrics.rms > 0.92) failures.push(`Âm thanh bị bão hòa/vỡ tiếng: RMS=${metrics.rms.toFixed(4)} > 0.92.`);
  if (metrics.zcr > 0.35) failures.push(`Nghi ngờ nhiễu trắng: ZCR=${metrics.zcr.toFixed(4)} > 0.35.`);
  if (metrics.zcr < 0.001) failures.push(`Sóng âm bị phẳng/DC offset: ZCR=${metrics.zcr.toFixed(4)} < 0.001.`);
  if (metrics.envelopeModulation < 0.12) failures.push(`Thiếu biến điệu âm tiết (nhiễu đều/rè): envMod=${metrics.envelopeModulation.toFixed(4)} < 0.12.`);
  if (uniqueC0 < 8) failures.push(`Chuỗi mã âm học bị kẹt lặp (uniqueC0=${uniqueC0} < 8).`);

  seaG2p?.close();

  if (failures.length > 0) {
    log("");
    log("!!! AUTOMATED TTS AUDIO TEST FAILED !!!");
    for (const f of failures) log(` ✗ ${f}`);
    saveReport();
    process.exit(1);
  }

  log("");
  log("✓ AUTOMATED TTS AUDIO TEST PASSED! test_voice.wav sẵn sàng đính kèm lên GitHub Releases.");
  saveReport();
}

main().catch((err) => {
  log("");
  log("!!! UNHANDLED ERROR IN test_tts.mjs !!!");
  log(err?.stack || String(err));
  saveReport();
  process.exit(1);
});
