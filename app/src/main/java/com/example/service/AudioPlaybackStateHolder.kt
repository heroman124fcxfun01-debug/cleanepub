package com.example.service

import com.example.data.ReaderVoiceProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Thông tin một giọng tiếng Việt Offline thực tế tìm thấy trên thiết bị Android (`tts.voices`).
 */
data class DeviceOfflineVoice(
    val voiceName: String,
    val displayLabel: String,
    val quality: Int,
    val qualityLabel: String,
    val isHighQuality: Boolean,
    val genderHint: String
)

/**
 * Process-scoped state for background Offline TTS & Ambient Sound playback.
 * Survives navigating back to Library/Home screens and leaving the app to Android Home.
 */
data class BookAudioPlaybackState(
    val bookId: String? = null,
    val bookTitle: String = "",
    val chapterIndex: Int = 0,
    val chapterTitle: String = "",
    val totalChapters: Int = 0,
    val paragraphIndex: Int = -1,
    val activeSentenceIndex: Int = -1,
    val activeSentenceText: String = "",
    val isPlaying: Boolean = false,
    val isPaused: Boolean = false,
    val isTtsReady: Boolean = false,
    val activeSpeakerName: String? = null,
    val activeClonedVoiceId: String? = null,
    val activeClonedVoiceName: String? = null,
    val isUsingOnnxVoice: Boolean = false,
    val speechRate: Float = 1.0f,
    val voiceProfile: ReaderVoiceProfile = ReaderVoiceProfile.FEMALE_1,
    val availableDeviceVoices: List<DeviceOfflineVoice> = emptyList(),
    // Hẹn giờ tự tắt nghe sách (Sleep Timer)
    val sleepTimerEndAtMillis: Long = 0L,
    val sleepTimerEndOfChapter: Boolean = false,
    val sleepTimerRemainingMinutes: Int = 0
) {
    val hasActiveSession: Boolean
        get() = bookId != null && (isPlaying || isPaused)

    val hasActiveSleepTimer: Boolean
        get() = sleepTimerEndOfChapter || sleepTimerEndAtMillis > 0L
}

object AudioPlaybackStateHolder {
    private val _state = MutableStateFlow(BookAudioPlaybackState())
    val state: StateFlow<BookAudioPlaybackState> = _state.asStateFlow()

    fun update(transform: (BookAudioPlaybackState) -> BookAudioPlaybackState) {
        _state.value = transform(_state.value)
    }

    fun reset() {
        val currentVoices = _state.value.availableDeviceVoices
        val ttsReady = _state.value.isTtsReady
        _state.value = BookAudioPlaybackState(
            isTtsReady = ttsReady,
            availableDeviceVoices = currentVoices
        )
    }
}
