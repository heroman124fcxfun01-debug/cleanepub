package com.example.audio

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Metadata & persistent vector paths for a user-created Offline AI Cloned Voice.
 * Stored permanently on device (`filesDir/book_library/cloned_voices/`).
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
    val usedOnnxEncoder: Boolean,
    val estimatedPitchRatio: Float
)

/**
 * 100% Offline Persistent Storage for Cloned Voices (speaker embedding Float32 vectors + reference audio).
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
                        embeddingDim = obj.optInt("embeddingDim", 256),
                        embeddingPath = embPath,
                        referenceAudioPath = obj.optString("referenceAudioPath").trim(),
                        usedOnnxEncoder = obj.optBoolean("usedOnnxEncoder", false),
                        estimatedPitchRatio = obj.optDouble("estimatedPitchRatio", 1.0)
                            .toFloat()
                            .coerceIn(0.75f, 1.28f)
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
        estimatedPitchRatio: Float
    ): ClonedVoiceProfile {
        rootDir.mkdirs()
        val id = "clone_${UUID.randomUUID().toString().take(8)}"
        val cleanName = rawName.trim().ifBlank { "Giọng nhân bản" }.take(36)
        val embFile = File(rootDir, "$id.emb")
        val refAudioFile = File(rootDir, "$id.f32")

        writeFloatArrayBinary(embFile, embedding)
        writeFloatArrayBinary(refAudioFile, samples)

        val durationSec = if (sampleRate > 0) {
            (samples.size.toFloat() / sampleRate.toFloat()).coerceIn(3.0f, 8.5f)
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
            usedOnnxEncoder = usedOnnxEncoder,
            estimatedPitchRatio = estimatedPitchRatio.coerceIn(0.75f, 1.28f)
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
            if (target.referenceAudioPath.isNotBlank()) {
                runCatching { File(target.referenceAudioPath).delete() }
            }
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

    fun loadReferenceAudio(profile: ClonedVoiceProfile): FloatArray? {
        if (profile.referenceAudioPath.isBlank()) return null
        val file = File(profile.referenceAudioPath)
        if (!file.exists()) return null
        return readFloatArrayBinary(file)
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
                    put("usedOnnxEncoder", p.usedOnnxEncoder)
                    put("estimatedPitchRatio", p.estimatedPitchRatio.toDouble())
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

        fun writeFloatArrayBinary(file: File, values: FloatArray) {
            val buffer = ByteBuffer.allocate(values.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (v in values) {
                buffer.putFloat(v)
            }
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
    }
}
