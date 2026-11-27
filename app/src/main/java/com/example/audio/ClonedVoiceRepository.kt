package com.example.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Metadata & persistent paths for a user-created Offline AI Cloned Voice (`pnnbao97/VieNeu-TTS`).
 * Stores both:
 * - `referenceText`: exact transcript of the recorded/imported sample ("Nội dung chữ trong file ghi âm mẫu")
 * - `referenceCodesPath`: binary 1D `IntArray` of NeuCodec FSQ speech token IDs extracted from the sample
 */
data class ClonedVoiceProfile(
    val id: String,
    val name: String,
    val createdAtMillis: Long,
    val sampleRate: Int,
    val durationSeconds: Float,
    val embeddingDim: Int,
    val embeddingPath: String,
    val referenceAudioPath: String,
    val referenceWavPath: String = "",
    val referenceCodesPath: String = "",
    val referenceText: String = "",
    val referenceCodeCount: Int = 0,
    val usedOnnxEncoder: Boolean,
    val estimatedPitchRatio: Float,
    val matchedPresetId: String = "",
    val warmthGainDb: Float = 0.0f,
    val presenceGainDb: Float = 0.0f,
    val brightnessGainDb: Float = 0.0f,
    val speakingRateRatio: Float = 1.0f
) {
    fun toTimbreSettings(): VoiceTimbreSettings = VoiceTimbreSettings(
        pitchRatio = estimatedPitchRatio.coerceIn(0.76f, 1.30f),
        warmthGainDb = warmthGainDb.coerceIn(-4.5f, 4.5f),
        presenceGainDb = presenceGainDb.coerceIn(-3.2f, 3.2f),
        brightnessGainDb = brightnessGainDb.coerceIn(-4.5f, 4.5f),
        speakingRateRatio = speakingRateRatio.coerceIn(0.88f, 1.14f),
        matchedPresetId = matchedPresetId.takeIf { it.isNotBlank() }
    )
}

/**
 * 100% Offline Persistent Storage for Cloned Voices (`filesDir/book_library/cloned_voices/`).
 */
class ClonedVoiceRepository(private val rootDir: File) {

    constructor(context: Context) : this(
        File(File(context.applicationContext.filesDir, "book_library"), CLONED_VOICES_DIR)
    )

    private val indexFile: File
        get() = File(rootDir, INDEX_FILE_NAME)

    @Synchronized
    fun loadAll(): List<ClonedVoiceProfile> {
        if (!indexFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            val result = ArrayList<ClonedVoiceProfile>(arr.length())
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optString("id").trim()
                val name = obj.optString("name").trim()
                val embPath = obj.optString("embeddingPath").trim()
                if (id.isEmpty() || name.isEmpty() || embPath.isEmpty()) continue
                if (!File(embPath).exists()) continue
                result.add(
                    ClonedVoiceProfile(
                        id = id,
                        name = name,
                        createdAtMillis = obj.optLong("createdAtMillis", 0L),
                        sampleRate = obj.optInt("sampleRate", VoiceSampleRecorder.DEFAULT_TARGET_SAMPLE_RATE),
                        durationSeconds = obj.optDouble("durationSeconds", 5.0).toFloat(),
                        embeddingDim = obj.optInt("embeddingDim", 32),
                        embeddingPath = embPath,
                        referenceAudioPath = obj.optString("referenceAudioPath").trim(),
                        referenceWavPath = obj.optString("referenceWavPath").trim(),
                        referenceCodesPath = obj.optString("referenceCodesPath").trim(),
                        referenceText = obj.optString("referenceText").trim(),
                        referenceCodeCount = obj.optInt("referenceCodeCount", 0),
                        usedOnnxEncoder = obj.optBoolean("usedOnnxEncoder", false),
                        estimatedPitchRatio = obj.optDouble("estimatedPitchRatio", 1.0)
                            .toFloat()
                            .coerceIn(0.75f, 1.30f),
                        matchedPresetId = obj.optString("matchedPresetId", "").trim(),
                        warmthGainDb = obj.optDouble("warmthGainDb", 0.0).toFloat().coerceIn(-4.5f, 4.5f),
                        presenceGainDb = obj.optDouble("presenceGainDb", 0.0).toFloat().coerceIn(-3.2f, 3.2f),
                        brightnessGainDb = obj.optDouble("brightnessGainDb", 0.0).toFloat().coerceIn(-4.5f, 4.5f),
                        speakingRateRatio = obj.optDouble("speakingRateRatio", 1.0).toFloat().coerceIn(0.88f, 1.14f)
                    )
                )
            }
            result.sortedByDescending { it.createdAtMillis }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun findById(voiceId: String?): ClonedVoiceProfile? {
        if (voiceId.isNullOrBlank()) return null
        return loadAll().firstOrNull { it.id == voiceId }
    }

    @Synchronized
    fun saveVoice(
        rawName: String,
        samples: FloatArray,
        sampleRate: Int,
        embedding: FloatArray,
        usedOnnxEncoder: Boolean,
        estimatedPitchRatio: Float,
        referenceCodes: IntArray? = null,
        referenceText: String = "",
        timbreSettings: VoiceTimbreSettings? = null
    ): ClonedVoiceProfile {
        rootDir.mkdirs()
        val id = "clone_${UUID.randomUUID().toString().take(8)}"
        val cleanName = rawName.trim().ifBlank { "Giọng nhân bản" }.take(36)
        val cleanRefText = referenceText.trim().take(500)
        val embFile = File(rootDir, "$id.emb")
        val refAudioFile = File(rootDir, "$id.f32")
        val refWavFile = File(rootDir, "$id.wav")
        val refCodesFile = File(rootDir, "$id.neucodes")

        writeFloatArrayBinary(embFile, embedding)
        writeFloatArrayBinary(refAudioFile, samples)
        AudioFileDecoder.writePcm16MonoWav(refWavFile, samples, sampleRate.coerceAtLeast(8_000))
        val savedCodesPath = if (referenceCodes != null && referenceCodes.isNotEmpty()) {
            writeIntArrayBinary(refCodesFile, referenceCodes)
            refCodesFile.absolutePath
        } else {
            ""
        }

        val durationSec = if (sampleRate > 0) {
            (samples.size.toFloat() / sampleRate.toFloat()).coerceIn(2.0f, 12.0f)
        } else {
            5.0f
        }

        val profile = ClonedVoiceProfile(
            id = id,
            name = cleanName,
            createdAtMillis = System.currentTimeMillis(),
            sampleRate = sampleRate,
            durationSeconds = durationSec,
            embeddingDim = embedding.size,
            embeddingPath = embFile.absolutePath,
            referenceAudioPath = refAudioFile.absolutePath,
            referenceWavPath = refWavFile.absolutePath,
            referenceCodesPath = savedCodesPath,
            referenceText = cleanRefText,
            referenceCodeCount = referenceCodes?.size ?: 0,
            usedOnnxEncoder = usedOnnxEncoder,
            estimatedPitchRatio = (timbreSettings?.pitchRatio ?: estimatedPitchRatio).coerceIn(0.75f, 1.30f),
            matchedPresetId = timbreSettings?.matchedPresetId.orEmpty(),
            warmthGainDb = (timbreSettings?.warmthGainDb ?: 0.0f).coerceIn(-4.5f, 4.5f),
            presenceGainDb = (timbreSettings?.presenceGainDb ?: 0.0f).coerceIn(-3.2f, 3.2f),
            brightnessGainDb = (timbreSettings?.brightnessGainDb ?: 0.0f).coerceIn(-4.5f, 4.5f),
            speakingRateRatio = (timbreSettings?.speakingRateRatio ?: 1.0f).coerceIn(0.88f, 1.14f)
        )

        val updated = listOf(profile) + loadAll().filterNot { it.id == id }
        saveIndex(updated)
        return profile
    }

    @Synchronized
    fun deleteVoice(voiceId: String): List<ClonedVoiceProfile> {
        val current = loadAll()
        val target = current.firstOrNull { it.id == voiceId }
        if (target != null) {
            runCatching { File(target.embeddingPath).delete() }
            if (target.referenceAudioPath.isNotBlank()) runCatching { File(target.referenceAudioPath).delete() }
            if (target.referenceWavPath.isNotBlank()) runCatching { File(target.referenceWavPath).delete() }
            if (target.referenceCodesPath.isNotBlank()) runCatching { File(target.referenceCodesPath).delete() }
            runCatching { File(rootDir, "${target.id}.wav").delete() }
            runCatching { File(rootDir, "${target.id}.codes").delete() }
            runCatching { File(rootDir, "${target.id}.neucodes").delete() }
        }
        val remaining = current.filterNot { it.id == voiceId }
        saveIndex(remaining)
        return remaining
    }

    fun loadEmbedding(profile: ClonedVoiceProfile): FloatArray? {
        val file = File(profile.embeddingPath)
        if (!file.exists()) return null
        return readFloatArrayBinary(file)
    }

    fun loadReferenceCodes(profile: ClonedVoiceProfile): IntArray? {
        val file = profile.referenceCodesPath.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(rootDir, "${profile.id}.neucodes")
        if (!file.exists()) return null
        return readIntArrayBinary(file)
    }

    fun loadReferenceAudio(profile: ClonedVoiceProfile): FloatArray? {
        if (profile.referenceAudioPath.isBlank()) return null
        val file = File(profile.referenceAudioPath)
        if (!file.exists()) return null
        return readFloatArrayBinary(file)
    }

    fun ensureReferenceWavFile(profile: ClonedVoiceProfile): File? {
        val existing = profile.referenceWavPath.takeIf { it.isNotBlank() }?.let { File(it) }
            ?: File(rootDir, "${profile.id}.wav")
        if (existing.exists() && existing.length() > 44L) {
            return existing
        }
        val refSamples = loadReferenceAudio(profile) ?: return null
        if (refSamples.isEmpty()) return null
        return runCatching {
            rootDir.mkdirs()
            AudioFileDecoder.writePcm16MonoWav(existing, refSamples, profile.sampleRate.coerceAtLeast(8_000))
        }.getOrNull()
    }

    @Synchronized
    private fun saveIndex(profiles: List<ClonedVoiceProfile>) {
        rootDir.mkdirs()
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject().apply {
                    put("id", p.id)
                    put("name", p.name)
                    put("createdAtMillis", p.createdAtMillis)
                    put("sampleRate", p.sampleRate)
                    put("durationSeconds", p.durationSeconds.toDouble())
                    put("embeddingDim", p.embeddingDim)
                    put("embeddingPath", p.embeddingPath)
                    put("referenceAudioPath", p.referenceAudioPath)
                    put("referenceWavPath", p.referenceWavPath)
                    put("referenceCodesPath", p.referenceCodesPath)
                    put("referenceText", p.referenceText)
                    put("referenceCodeCount", p.referenceCodeCount)
                    put("usedOnnxEncoder", p.usedOnnxEncoder)
                    put("estimatedPitchRatio", p.estimatedPitchRatio.toDouble())
                    put("matchedPresetId", p.matchedPresetId)
                    put("warmthGainDb", p.warmthGainDb.toDouble())
                    put("presenceGainDb", p.presenceGainDb.toDouble())
                    put("brightnessGainDb", p.brightnessGainDb.toDouble())
                    put("speakingRateRatio", p.speakingRateRatio.toDouble())
                }
            )
        }
        val tmp = File(rootDir, "$INDEX_FILE_NAME.tmp")
        tmp.writeText(arr.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(indexFile)) {
            indexFile.writeText(arr.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        private const val CLONED_VOICES_DIR = "cloned_voices"
        private const val INDEX_FILE_NAME = "cloned_voices_index.json"
        private const val NEUCODES_MAGIC = 0x4E455543 // "NEUC"

        fun writeFloatArrayBinary(file: File, values: FloatArray) {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) buffer.putFloat(v)
            file.writeBytes(buffer.array())
        }

        fun readFloatArrayBinary(file: File): FloatArray? {
            return runCatching {
                val bytes = file.readBytes()
                if (bytes.size < 4) return null
                val count = bytes.size / 4
                val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                FloatArray(count) { buffer.getFloat() }
            }.getOrNull()
        }

        fun writeIntArrayBinary(file: File, codes: IntArray) {
            if (codes.isEmpty()) return
            val bb = ByteBuffer.allocate((2 + codes.size) * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.putInt(NEUCODES_MAGIC)
            bb.putInt(codes.size)
            for (c in codes) bb.putInt(c)
            file.writeBytes(bb.array())
        }

        fun readIntArrayBinary(file: File): IntArray? {
            return runCatching {
                val bytes = file.readBytes()
                if (bytes.size < 12) return null
                val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
                val magic = bb.int
                if (magic != NEUCODES_MAGIC) return null
                val count = bb.int
                if (count <= 0 || count > 4096) return null
                if (bytes.size < (2 + count) * 4) return null
                IntArray(count) { bb.int }
            }.getOrNull()
        }
    }
}
