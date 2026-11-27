package com.example.audio

import android.util.Log
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.util.Locale

/**
 * Zero-heap (`mmap` via [FileChannel.map]) binary dictionary reader for `pnnbao97/sea-g2p`'s
 * `sea_g2p.bin` (`SEAP` v2 format, ~47.77 MB, 1.16M+ pre-sorted entries).
 *
 * Header layout (32 bytes, Little-Endian, verified directly from device `sea_g2p.bin`):
 *   0x00..0x03 : magic = "SEAP"
 *   0x04..0x07 : version = 2 (u32)
 *   0x08..0x0B : nStrings (u32, e.g. 2,190,407)
 *   0x0C..0x0F : nEntries (u32, e.g. 1,160,327)
 *   0x10..0x13 : nExtra   (u32, e.g. 231)
 *   0x14..0x17 : strOffsetsPos (u32, e.g. 32,043,957 -> nStrings * 4 bytes)
 *   0x18..0x1B : entriesPos    (u32, e.g. 40,805,585 -> nEntries * 8 bytes)
 *   0x1C..0x1F : extraPos      (u32, e.g. 50,088,201)
 *
 * Automatically detects the exact string-pool framing (raw slice / NUL-terminated / u8 or u16
 * length prefix), absolute vs relative offsets, and optional language key prefixes (`vi:`, etc.),
 * and runs a startup self-test on core Vietnamese words before activating lookups.
 */
class SeaG2pBinaryDict private constructor(
    private val raf: RandomAccessFile,
    private val mapped: MappedByteBuffer,
    private val fileSize: Long,
    val version: Int,
    val nStrings: Int,
    val nEntries: Int,
    val nExtra: Int,
    private val strOffsetsPos: Long,
    private val entriesPos: Long,
    private val extraPos: Long,
    private val poolBase: Long,
    private val poolEnd: Long,
    private val strFraming: StringFraming,
    private val detectedKeyPrefix: String,
    val isValidatedForVietnamese: Boolean,
    val diagnosticSummary: String
) : AutoCloseable {

    enum class StringFraming {
        RAW_SLICE,
        NUL_TERMINATED,
        LEN_U8_PREFIX,
        LEN_U16_PREFIX
    }

    private fun readU32(pos: Long): Long {
        if (pos < 0L || pos + 4L > fileSize) return 0L
        return mapped.getInt(pos.toInt()).toLong() and 0xFFFFFFFFL
    }

    private fun readU16(pos: Long): Int {
        if (pos < 0L || pos + 2L > fileSize) return 0
        return mapped.getShort(pos.toInt()).toInt() and 0xFFFF
    }

    private fun readU8(pos: Long): Int {
        if (pos < 0L || pos >= fileSize) return 0
        return mapped.get(pos.toInt()).toInt() and 0xFF
    }

    fun readStringById(strId: Int): String? {
        if (strId < 0 || strId >= nStrings) return null
        val rawOff = readU32(strOffsetsPos + strId.toLong() * 4L)
        val start = poolBase + rawOff
        if (start < 0L || start >= poolEnd) return null

        val nextRawOff = if (strId + 1 < nStrings) {
            readU32(strOffsetsPos + (strId + 1).toLong() * 4L)
        } else {
            poolEnd - poolBase
        }
        val sliceEnd = (poolBase + nextRawOff).coerceIn(start, poolEnd)

        return when (strFraming) {
            StringFraming.LEN_U8_PREFIX -> {
                val len = readU8(start)
                val dataStart = start + 1L
                val dataEnd = (dataStart + len).coerceAtMost(poolEnd)
                readUtf8Slice(dataStart, dataEnd)
            }
            StringFraming.LEN_U16_PREFIX -> {
                val len = readU16(start)
                val dataStart = start + 2L
                val dataEnd = (dataStart + len).coerceAtMost(poolEnd)
                readUtf8Slice(dataStart, dataEnd)
            }
            StringFraming.NUL_TERMINATED -> {
                var end = sliceEnd
                if (end > start && readU8(end - 1L) == 0) {
                    end--
                } else {
                    // Scan up to 256 bytes for NUL if sliceEnd wasn't monotonically bounded
                    val scanLimit = minOf(start + 256L, poolEnd)
                    var cur = start
                    while (cur < scanLimit && readU8(cur) != 0) cur++
                    end = cur
                }
                readUtf8Slice(start, end)
            }
            StringFraming.RAW_SLICE -> {
                var end = sliceEnd
                while (end > start && readU8(end - 1L) == 0) end--
                readUtf8Slice(start, end)
            }
        }
    }

    private fun readUtf8Slice(start: Long, end: Long): String {
        val len = (end - start).toInt().coerceIn(0, 512)
        if (len <= 0) return ""
        val buf = ByteArray(len)
        val base = start.toInt()
        for (i in 0 until len) {
            buf[i] = mapped.get(base + i)
        }
        return String(buf, Charsets.UTF_8)
    }

    private fun readEntryPair(index: Int): Pair<Int, Int> {
        val pos = entriesPos + index.toLong() * 8L
        val kId = readU32(pos).toInt()
        val vId = readU32(pos + 4L).toInt()
        return kId to vId
    }

    /**
     * Looks up a normalized lowercase Vietnamese or English word/phrase using O(log N) binary search
     * directly over the memory-mapped `sea_g2p.bin` entries table (0 KB heap allocation).
     */
    fun lookupVietnamese(wordLower: String): String? {
        if (!isValidatedForVietnamese || wordLower.isBlank()) return null
        if (detectedKeyPrefix.isNotEmpty()) {
            binarySearchExact(detectedKeyPrefix + wordLower)?.let { return it }
        }
        return binarySearchExact(wordLower)
    }

    /**
     * Raw exact binary search using unsigned UTF-8 byte order (matching Rust `str::cmp`).
     */
    fun binarySearchExact(key: String): String? {
        val targetBytes = key.toByteArray(Charsets.UTF_8)
        var lo = 0
        var hi = nEntries - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val (kId, vId) = readEntryPair(mid)
            val midStr = readStringById(kId) ?: return null
            val cmp = compareUtf8Unsigned(midStr.toByteArray(Charsets.UTF_8), targetBytes)
            when {
                cmp == 0 -> return readStringById(vId)
                cmp < 0 -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return null
    }

    override fun close() {
        runCatching { raf.close() }
    }

    companion object {
        private const val TAG = "SeaG2pBinaryDict"

        private fun compareUtf8Unsigned(a: ByteArray, b: ByteArray): Int {
            val minLen = minOf(a.size, b.size)
            for (i in 0 until minLen) {
                val av = a[i].toInt() and 0xFF
                val bv = b[i].toInt() and 0xFF
                if (av != bv) return av - bv
            }
            return a.size - b.size
        }

        fun open(file: File?): SeaG2pBinaryDict? {
            if (file == null || !file.exists() || file.length() < 64L) return null
            var raf: RandomAccessFile? = null
            try {
                val len = file.length()
                if (len > Int.MAX_VALUE.toLong()) return null
                raf = RandomAccessFile(file, "r")
                val ch = raf.channel
                val mapped = ch.map(FileChannel.MapMode.READ_ONLY, 0L, len)
                mapped.order(ByteOrder.LITTLE_ENDIAN)

                if (mapped.get(0) != 'S'.code.toByte() ||
                    mapped.get(1) != 'E'.code.toByte() ||
                    mapped.get(2) != 'A'.code.toByte() ||
                    mapped.get(3) != 'P'.code.toByte()
                ) {
                    raf.close()
                    return null
                }

                val version = mapped.getInt(4)
                val nStrings = mapped.getInt(8)
                val nEntries = mapped.getInt(12)
                val nExtra = mapped.getInt(16)
                val strOffsetsPos = mapped.getInt(20).toLong() and 0xFFFFFFFFL
                val entriesPos = mapped.getInt(24).toLong() and 0xFFFFFFFFL
                val extraPos = mapped.getInt(28).toLong() and 0xFFFFFFFFL

                if (nStrings <= 0 || nEntries <= 0 ||
                    strOffsetsPos < 32L || strOffsetsPos + nStrings.toLong() * 4L > len ||
                    entriesPos < strOffsetsPos || entriesPos + nEntries.toLong() * 8L > len
                ) {
                    raf.close()
                    return null
                }

                // Auto-detect poolBase (0 if offsets are absolute file offsets, 32 if relative to header end)
                val off0 = mapped.getInt(strOffsetsPos.toInt()).toLong() and 0xFFFFFFFFL
                val off1 = if (nStrings > 1) mapped.getInt((strOffsetsPos + 4L).toInt()).toLong() and 0xFFFFFFFFL else strOffsetsPos
                val off2 = if (nStrings > 2) mapped.getInt((strOffsetsPos + 8L).toInt()).toLong() and 0xFFFFFFFFL else strOffsetsPos

                val poolBase = if (off0 < 32L) 32L else 0L
                val poolEnd = strOffsetsPos

                val len0 = (off1 - off0).toInt()
                val len1 = (off2 - off1).toInt()
                val framing = detectStringFraming(mapped, poolBase, off0, len0, off1, len1, poolEnd)

                // Create temporary probe instance to inspect sample entries & detect any language prefix
                val probe = SeaG2pBinaryDict(
                    raf = raf,
                    mapped = mapped,
                    fileSize = len,
                    version = version,
                    nStrings = nStrings,
                    nEntries = nEntries,
                    nExtra = nExtra,
                    strOffsetsPos = strOffsetsPos,
                    entriesPos = entriesPos,
                    extraPos = extraPos,
                    poolBase = poolBase,
                    poolEnd = poolEnd,
                    strFraming = framing,
                    detectedKeyPrefix = "",
                    isValidatedForVietnamese = false,
                    diagnosticSummary = ""
                )

                val sampleIndices = listOf(0, 1, nEntries / 4, nEntries / 2, (nEntries * 3) / 4, nEntries - 1)
                    .filter { it in 0 until nEntries }
                    .distinct()
                val samplePairs = sampleIndices.map { idx ->
                    val (kId, vId) = probe.readEntryPair(idx)
                    val ks = probe.readStringById(kId)?.replace("\u0000", "\\0")?.take(24) ?: "?"
                    val vs = probe.readStringById(vId)?.replace("\u0000", "\\0")?.take(24) ?: "?"
                    "[$idx]$ks=>$vs"
                }

                // Probe possible Vietnamese key prefixes (empty, "vi:", "vi\u0000", "vi|", "vi/", "vi\t", "vi ")
                val candidatePrefixes = listOf("", "vi:", "vi\u0000", "vi|", "vi/", "vi\t", "vi ")
                val testWords = listOf("một", "ngày", "mưa", "hà", "nội", "tôi")
                var bestPrefix = ""
                var bestHits = 0
                var bestResults = emptyList<String>()

                for (pfx in candidatePrefixes) {
                    val hits = ArrayList<String>()
                    for (w in testWords) {
                        val res = probe.binarySearchExact(pfx + w)
                        if (!res.isNullOrBlank()) {
                            hits.add("$w->$res")
                        }
                    }
                    if (hits.size > bestHits) {
                        bestHits = hits.size
                        bestPrefix = pfx
                        bestResults = hits
                    }
                }

                // Validate that returned values look like real IPA strings (printable, reasonable length)
                val validated = bestHits >= 3 && bestResults.all { entry ->
                    val v = entry.substringAfter("->")
                    v.length in 1..48 && v.none { it.code < 32 }
                }

                val pfxLabel = bestPrefix.replace("\u0000", "\\0").replace("\t", "\\t")
                val summary = "SEAP v$version | str=$nStrings, ent=$nEntries, extra=$nExtra | " +
                    "poolBase=$poolBase, framing=$framing, viPrefix='$pfxLabel', valid=$validated | " +
                    "test=[${bestResults.joinToString(", ").ifEmpty { "0 hits" }}] | " +
                    "samples=[${samplePairs.joinToString(" ; ")}]"
                Log.i(TAG, summary)

                return SeaG2pBinaryDict(
                    raf = raf,
                    mapped = mapped,
                    fileSize = len,
                    version = version,
                    nStrings = nStrings,
                    nEntries = nEntries,
                    nExtra = nExtra,
                    strOffsetsPos = strOffsetsPos,
                    entriesPos = entriesPos,
                    extraPos = extraPos,
                    poolBase = poolBase,
                    poolEnd = poolEnd,
                    strFraming = framing,
                    detectedKeyPrefix = bestPrefix,
                    isValidatedForVietnamese = validated,
                    diagnosticSummary = summary
                )
            } catch (e: Throwable) {
                Log.w(TAG, "Failed to mmap sea_g2p.bin: ${e.message}")
                runCatching { raf?.close() }
                return null
            }
        }

        private fun detectStringFraming(
            mapped: MappedByteBuffer,
            poolBase: Long,
            off0: Long,
            len0: Int,
            off1: Long,
            len1: Int,
            poolEnd: Long
        ): StringFraming {
            val s0 = poolBase + off0
            val s1 = poolBase + off1
            if (s0 < 0L || s0 >= poolEnd) return StringFraming.RAW_SLICE

            // Check u16 length prefix
            if (len0 >= 3 && len1 >= 3 && s0 + 2L <= poolEnd && s1 + 2L <= poolEnd) {
                val u16_0 = mapped.getShort(s0.toInt()).toInt() and 0xFFFF
                val u16_1 = mapped.getShort(s1.toInt()).toInt() and 0xFFFF
                if (u16_0 == len0 - 2 && u16_1 == len1 - 2) {
                    return StringFraming.LEN_U16_PREFIX
                }
            }
            // Check u8 length prefix
            if (len0 >= 2 && len1 >= 2 && s0 + 1L <= poolEnd && s1 + 1L <= poolEnd) {
                val u8_0 = mapped.get(s0.toInt()).toInt() and 0xFF
                val u8_1 = mapped.get(s1.toInt()).toInt() and 0xFF
                if (u8_0 == len0 - 1 && u8_1 == len1 - 1) {
                    return StringFraming.LEN_U8_PREFIX
                }
            }
            // Check trailing NUL byte
            if (len0 >= 2 && s0 + len0 - 1L < poolEnd && mapped.get((s0 + len0 - 1L).toInt()).toInt() == 0) {
                return StringFraming.NUL_TERMINATED
            }
            return StringFraming.RAW_SLICE
        }
    }
}
