package com.example.audio

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Persistent 0ms-latency preview WAV cache stored in `context.filesDir/voice_previews/preview_<voice_id>.wav`.
 *
 * - Newly created cloned voices copy their already-synthesized draft preview WAV straight into this
 *   cache on Save so tapping "Nghe thử" plays in <50ms without running GGUF + NeuCodec again.
 * - Official `voices.json` presets are pre-rendered lazily on `Dispatchers.IO` when the user opens
 *   the Studio voice list, yielding immediately whenever book playback or voice creation is active.
 */
object VoicePreviewCache {

    private const val TAG = "VoicePreviewCache"
    private const val PREVIEW_DIR_NAME = "voice_previews"
    private const val CLEAN_ONSET_MARKER = ".v2_clean_onset"
    private const val MIN_VALID_WAV_BYTES = 4_800L // >= ~0.1s @ 24kHz 16-bit mono + 44-byte header

    const val INSTANT_PREVIEW_SENTENCE =
        "Xin chào, đây là giọng đọc của tôi trên ứng dụng đọc sách."

    fun previewDir(context: Context): File {
        val dir = File(context.applicationContext.filesDir, PREVIEW_DIR_NAME)
        val marker = File(dir, CLEAN_ONSET_MARKER)
        if (dir.exists() && !marker.exists()) {
            runCatching {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".wav") }?.forEach { it.delete() }
                marker.createNewFile()
            }
        }
        return dir
    }

    fun sanitizeVoiceId(voiceId: String): String {
        val cleaned = voiceId.trim()
            .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
            .trim('_')
        return cleaned.ifBlank { "default" }
    }

    fun previewFileForVoice(context: Context, voiceId: String): File {
        val safe = sanitizeVoiceId(voiceId)
        return File(previewDir(context), "preview_$safe.wav")
    }

    fun hasValidPreview(context: Context, voiceId: String): Boolean {
        val f = previewFileForVoice(context, voiceId)
        return f.exists() && f.length() >= MIN_VALID_WAV_BYTES
    }

    fun getValidPreviewFile(context: Context, voiceId: String): File? {
        val f = previewFileForVoice(context, voiceId)
        return if (f.exists() && f.length() >= MIN_VALID_WAV_BYTES) f else null
    }

    /**
     * Atomically writes a Mono Float32 PCM waveform to `preview_<voice_id>.wav`.
     */
    fun savePreviewPcm(
        context: Context,
        voiceId: String,
        samples: FloatArray,
        sampleRate: Int
    ): File? {
        if (samples.isEmpty() || sampleRate <= 0) return null
        return runCatching {
            val dir = previewDir(context).apply { mkdirs() }
            val safe = sanitizeVoiceId(voiceId)
            val dest = File(dir, "preview_$safe.wav")
            val tmp = File(dir, "preview_${safe}_${System.nanoTime()}.tmp")
            AudioFileDecoder.writePcm16MonoWav(tmp, samples, sampleRate)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "Cached instant preview WAV: ${dest.name} (${dest.length()} bytes)")
            dest
        }.onFailure { e ->
            Log.w(TAG, "Failed saving preview WAV for $voiceId: ${e.message}")
        }.getOrNull()
    }

    /**
     * Promotes a synthesized draft WAV directly into `filesDir/voice_previews/preview_<savedVoiceId>.wav`
     * so the newly saved voice plays instantaneously on first tap.
     */
    fun copyDraftPreviewToSavedVoice(
        context: Context,
        draftWavPath: String?,
        savedVoiceId: String
    ): File? {
        if (draftWavPath.isNullOrBlank()) return null
        val src = File(draftWavPath)
        if (!src.exists() || src.length() < MIN_VALID_WAV_BYTES) return null
        return runCatching {
            val dir = previewDir(context).apply { mkdirs() }
            val safe = sanitizeVoiceId(savedVoiceId)
            val dest = File(dir, "preview_$safe.wav")
            val tmp = File(dir, "preview_${safe}_${System.nanoTime()}.tmp")
            src.copyTo(tmp, overwrite = true)
            if (dest.exists()) dest.delete()
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            Log.i(TAG, "Promoted draft preview -> ${dest.name} (${dest.length()} bytes)")
            dest
        }.getOrNull()
    }

    fun deletePreview(context: Context, voiceId: String) {
        runCatching {
            val f = previewFileForVoice(context, voiceId)
            if (f.exists()) f.delete()
        }
    }
}
