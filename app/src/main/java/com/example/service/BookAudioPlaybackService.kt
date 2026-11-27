package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.audio.AmbientSoundEngine
import com.example.audio.AmbientSoundTrack
import com.example.audio.ClonedVoiceProfile
import com.example.audio.ClonedVoiceRepository
import com.example.audio.OfflineOnnxVoiceEngine
import com.example.audio.OnnxAudioTrackPlayer
import com.example.audio.OnnxModelAssetHelper
import com.example.audio.OnnxVoiceEngineMode
import com.example.audio.StoryCharacterScanner
import com.example.audio.VietnameseTtsTextNormalizer
import com.example.data.LibraryChapterMeta
import com.example.data.LibraryManager
import com.example.data.ReaderPreferences
import com.example.data.ReaderVoiceProfile
import com.example.data.TtsEngineType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

private const val AUDIO_CHANNEL_ID = "epub_clean_book_audio_channel"
private const val AUDIO_NOTIFICATION_ID = 4208

/**
 * Dịch vụ đọc sách nói 100% Offline chạy nền (`foregroundServiceType="mediaPlayback"`).
 *
 * Kiến trúc nâng cấp trên Android TextToSpeech gốc:
 * 1. Quét `tts.voices`, chỉ giữ giọng tiếng Việt (`locale.language == "vi"`) hoàn toàn offline
 *    (`!isNetworkConnectionRequired`) và sắp xếp ưu tiên giọng chất lượng cao (`quality >= QUALITY_HIGH`) lên đầu.
 * 2. Tiền xử lý văn bản tiếng Việt bằng [VietnameseTtsTextNormalizer] trước khi đọc (mở rộng từ viết tắt,
 *    xóa ký tự rác *, #, [2], đổi gạch ngang đối thoại thành dấu phẩy, tách câu thông minh).
 * 3. Đẩy các câu vào hàng đợi bằng `TextToSpeech.QUEUE_ADD` xen kẽ `playSilentUtterance(sentencePauseMs)`
 *    để tạo nhịp thở tự nhiên giữa các câu, không bị đọc liến thoắng hay hụt tiếng.
 * 4. Hỗ trợ cả 6 hồ sơ giọng mẫu (3 Nam / 3 Nữ, tự fallback giọng nữ hạ pitch nếu máy thiếu giọng nam gốc)
 *    lẫn chế độ "Tùy chỉnh nâng cao" (Pitch, Speech Rate, Sentence Pause) và chọn giọng gốc trên thiết bị.
 */
class BookAudioPlaybackService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mainHandler = Handler(Looper.getMainLooper())
    private lateinit var libraryManager: LibraryManager
    private lateinit var clonedVoiceRepo: ClonedVoiceRepository
    private lateinit var onnxEngine: OfflineOnnxVoiceEngine
    private val onnxPlayer = OnnxAudioTrackPlayer()
    private val ambientEngine = AmbientSoundEngine()

    private var ttsEngine: TextToSpeech? = null
    private var isTtsReady = false

    // Danh sách giọng tiếng Việt offline đã sắp xếp ưu tiên QUALITY_HIGH lên đầu
    private var cachedOfflineViVoices: List<Voice> = emptyList()
    private var cachedDeviceVoiceModels: List<DeviceOfflineVoice> = emptyList()

    // Bộ nhớ đệm 1 chương tại một thời điểm (tối ưu RAM 4GB)
    private var activeBookId: String? = null
    private var activeBookTitle: String = ""
    private var activeToc: List<LibraryChapterMeta> = emptyList()
    private var activeChapterIndex: Int = 0
    private var activeChapterTitle: String = ""
    private var activeParagraphs: List<String> = emptyList()
    private var activeCharacterVoices: Map<String, ReaderVoiceProfile> = emptyMap()
    private var activePrefs: ReaderPreferences = ReaderPreferences()

    // Trạng thái theo dõi hàng đợi câu & đoạn đang đọc (hỗ trợ tô sáng câu & Lùi/Tiến câu)
    private var speakingParagraphIndex = -1
    private var currentParagraphSentences: List<String> = emptyList()
    private var speakingSentenceIndex = -1
    private var speakingSentenceText = ""
    private val utteranceSentenceMap = ConcurrentHashMap<String, Pair<Int, String>>()
    private var activeClonedVoice: ClonedVoiceProfile? = null
    private var activeUsingOnnxVoice = false
    private var onnxStreamJob: Job? = null

    private var activeSpeakerName: String? = null
    private var paragraphFinalUtteranceId = ""
    private var activeParagraphGeneration = 0L
    private var isPlaying = false
    private var isForegroundActive = false
    private var isPreviewingAmbient = false

    // Trạng thái nghe thử dạng công tắc (bấm 1 lần nghe, bấm lần 2 tắt ngay)
    private var isPreviewingVoice = false
    private var activePreviewTag: String? = null
    private var previewFinalUtteranceId = ""
    private var previewAudioJob: Job? = null
    private var lastPlaybackNotice: String? = null

    // Yêu cầu phát chờ TTS khởi tạo xong
    private var pendingStartRequest: PendingStartRequest? = null
    private var pendingPreviewRequest: PendingPreviewRequest? = null

    // Theo dõi thông số giọng đã áp dụng để tránh gọi setVoice/setPitch lặp thừa
    private var lastAppliedVoiceName = ""
    private var lastAppliedPitch = -1f
    private var lastAppliedRate = -1f

    // Hẹn giờ tự tắt nghe sách (Sleep Timer)
    private var sleepTimerEndAtMillis = 0L
    private var sleepTimerEndOfChapter = false
    private val sleepTimerHandler = Handler(Looper.getMainLooper())
    private val sleepTimerTickRunnable = object : Runnable {
        override fun run() {
            if (sleepTimerEndAtMillis <= 0L) return
            val remainingMs = sleepTimerEndAtMillis - System.currentTimeMillis()
            if (remainingMs <= 0L) {
                clearSleepTimerInternal(publish = false)
                pausePlayback()
            } else {
                publishState()
                val nextDelay = remainingMs.coerceAtMost(15_000L)
                sleepTimerHandler.postDelayed(this, nextDelay)
            }
        }
    }

    private data class PendingStartRequest(
        val bookId: String,
        val chapterIndex: Int,
        val paragraphIndex: Int
    )

    private data class PendingPreviewRequest(
        val profile: ReaderVoiceProfile,
        val pitchOverride: Float?,
        val rateOverride: Float?,
        val pauseMsOverride: Int?,
        val systemVoiceOverride: String?,
        val previewTag: String?
    )

    override fun onCreate() {
        super.onCreate()
        libraryManager = LibraryManager(applicationContext)
        clonedVoiceRepo = ClonedVoiceRepository(applicationContext)
        onnxEngine = OfflineOnnxVoiceEngine(applicationContext)
        activePrefs = libraryManager.getReaderPreferences()
        activeClonedVoice = clonedVoiceRepo.findById(activePrefs.selectedClonedVoiceId)
        createNotificationChannel()
        initTtsEngine()
        // Khởi nạp model ONNX / sea_g2p.bin trên luồng Worker ngầm (chế độ dự phòng an toàn nếu chưa có model)
        serviceScope.launch {
            onnxEngine.ensureInitialized()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        activePrefs = libraryManager.getReaderPreferences()
        when (intent?.action) {
            ACTION_PLAY_AT -> {
                val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: return START_NOT_STICKY
                val chapIdx = intent.getIntExtra(EXTRA_CHAPTER_INDEX, 0)
                val paraIdx = intent.getIntExtra(EXTRA_PARAGRAPH_INDEX, 0)
                ensureForegroundNotification(
                    title = activeBookTitle.ifBlank { "Đang chuẩn bị giọng đọc…" },
                    subtitle = "Sách nói ngoại tuyến",
                    playing = true
                )
                startOrJumpPlayback(bookId, chapIdx, paraIdx)
            }

            ACTION_TOGGLE_PLAY_PAUSE -> {
                if (isPlaying) {
                    pausePlayback()
                } else {
                    val bookId = intent.getStringExtra(EXTRA_BOOK_ID) ?: activeBookId
                    if (bookId != null) {
                        val chapIdx = if (intent.hasExtra(EXTRA_CHAPTER_INDEX)) {
                            intent.getIntExtra(EXTRA_CHAPTER_INDEX, activeChapterIndex)
                        } else {
                            activeChapterIndex
                        }
                        val paraIdx = if (intent.hasExtra(EXTRA_PARAGRAPH_INDEX)) {
                            intent.getIntExtra(EXTRA_PARAGRAPH_INDEX, speakingParagraphIndex.coerceAtLeast(0))
                        } else {
                            speakingParagraphIndex.coerceAtLeast(0)
                        }
                        ensureForegroundNotification(
                            title = activeBookTitle.ifBlank { "Đang phát sách nói" },
                            subtitle = activeChapterTitle.ifBlank { "Chương ${chapIdx + 1}" },
                            playing = true
                        )
                        startOrJumpPlayback(bookId, chapIdx, paraIdx)
                    }
                }
            }

            ACTION_PAUSE -> pausePlayback()
            ACTION_PREV_PARAGRAPH -> stepParagraph(-1)
            ACTION_NEXT_PARAGRAPH -> stepParagraph(+1)
            ACTION_SYNC_SETTINGS -> reloadSettingsAndRefreshActiveAudio()

            ACTION_REFRESH_VOICES -> {
                val engine = ttsEngine
                if (engine != null && isTtsReady) {
                    refreshAvailableOfflineVoices(engine)
                    publishState()
                }
            }

            ACTION_PREVIEW_VOICE -> {
                val previewTag = intent.getStringExtra(EXTRA_PREVIEW_TAG)
                // Bấm lại đúng nút đang nghe thử -> dừng ngay lập tức
                if (isPreviewingVoice && (previewTag == null || activePreviewTag == previewTag)) {
                    stopVoicePreviewInternal(publish = true)
                    return START_NOT_STICKY
                }
                val profileKey = intent.getStringExtra(EXTRA_VOICE_KEY)
                val profile = ReaderVoiceProfile.fromKey(profileKey)
                val pitchOverride = if (intent.hasExtra(EXTRA_VOICE_PITCH)) {
                    intent.getFloatExtra(EXTRA_VOICE_PITCH, 1.0f)
                } else null
                val rateOverride = if (intent.hasExtra(EXTRA_VOICE_RATE)) {
                    intent.getFloatExtra(EXTRA_VOICE_RATE, 1.0f)
                } else null
                val pauseOverride = if (intent.hasExtra(EXTRA_SENTENCE_PAUSE_MS)) {
                    intent.getIntExtra(EXTRA_SENTENCE_PAUSE_MS, activePrefs.sentencePauseMs)
                } else null
                val systemVoiceOverride = intent.getStringExtra(EXTRA_SYSTEM_VOICE_NAME)
                previewVoiceSample(
                    profile = profile,
                    pitchOverride = pitchOverride,
                    rateOverride = rateOverride,
                    pauseMsOverride = pauseOverride,
                    systemVoiceOverride = systemVoiceOverride,
                    previewTag = previewTag
                )
            }

            ACTION_PREVIEW_CLONED_VOICE -> {
                val previewTag = intent.getStringExtra(EXTRA_PREVIEW_TAG)
                val voiceId = intent.getStringExtra(EXTRA_CLONED_VOICE_ID)
                if (isPreviewingVoice && (previewTag == null || activePreviewTag == previewTag)) {
                    stopVoicePreviewInternal(publish = true)
                    return START_NOT_STICKY
                }
                previewClonedVoiceSample(voiceId = voiceId, previewTag = previewTag)
            }

            ACTION_STOP_PREVIEW -> {
                stopVoicePreviewInternal(publish = true)
            }

            ACTION_PREVIEW_AMBIENT -> {
                val active = intent.getBooleanExtra(EXTRA_AMBIENT_PREVIEW_ACTIVE, false)
                isPreviewingAmbient = active
                syncAmbientPlayback()
                if (!active && !isPlaying && !AudioPlaybackStateHolder.state.value.hasActiveSession) {
                    stopForegroundAndSelf()
                }
            }

            ACTION_SET_SLEEP_TIMER -> {
                val minutes = intent.getIntExtra(EXTRA_SLEEP_TIMER_MINUTES, 0)
                applySleepTimer(minutes)
            }

            ACTION_STOP -> stopPlaybackAndService()
        }
        return START_NOT_STICKY
    }

    // -------------------------------------------------------------------------
    // 1. Khởi tạo Android TTS & Quét / Xếp hạng giọng tiếng Việt Offline chất lượng cao
    // -------------------------------------------------------------------------

    private fun initTtsEngine() {
        var localTts: TextToSpeech? = null
        localTts = TextToSpeech(applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val engine = localTts ?: return@TextToSpeech
                runCatching {
                    engine.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                }
                val viLocale = Locale("vi", "VN")
                val langRes = runCatching { engine.setLanguage(viLocale) }
                    .getOrDefault(TextToSpeech.LANG_NOT_SUPPORTED)
                if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                    runCatching { engine.language = Locale.getDefault() }
                }

                // Quét và sắp xếp giọng tiếng Việt offline (ưu tiên Voice.QUALITY_HIGH trở lên)
                refreshAvailableOfflineVoices(engine)

                applyVoiceConfiguration(
                    engine = engine,
                    profile = activePrefs.voiceProfile,
                    isCharacterOverride = false,
                    force = true
                )

                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        val sentenceInfo = utteranceId?.let { utteranceSentenceMap[it] } ?: return
                        val capturedGen = activeParagraphGeneration
                        mainHandler.post {
                            if (!isPlaying || capturedGen != activeParagraphGeneration) return@post
                            speakingSentenceIndex = sentenceInfo.first
                            speakingSentenceText = sentenceInfo.second
                            publishState()
                        }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == null) return
                        if (utteranceId == previewFinalUtteranceId) {
                            mainHandler.post {
                                if (previewFinalUtteranceId == utteranceId) {
                                    previewFinalUtteranceId = ""
                                    isPreviewingVoice = false
                                    activePreviewTag = null
                                    publishState()
                                }
                            }
                            return
                        }
                        // Chỉ chuyển đoạn khi câu CUỐI CÙNG của đoạn hiện tại vừa hoàn tất
                        if (utteranceId != paragraphFinalUtteranceId) return
                        val capturedGen = activeParagraphGeneration
                        val interParagraphPause = (activePrefs.sentencePauseMs + 140)
                            .coerceIn(220, 950)
                            .toLong()
                        mainHandler.postDelayed({
                            if (!isPlaying || capturedGen != activeParagraphGeneration) return@postDelayed
                            advanceToNextParagraphOrChapter()
                        }, interParagraphPause)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        handleUtteranceError(utteranceId)
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        handleUtteranceError(utteranceId)
                    }
                })

                mainHandler.post {
                    isTtsReady = true
                    publishState()

                    val pendingPlay = pendingStartRequest
                    pendingStartRequest = null
                    if (pendingPlay != null) {
                        startOrJumpPlayback(
                            pendingPlay.bookId,
                            pendingPlay.chapterIndex,
                            pendingPlay.paragraphIndex
                        )
                    } else {
                        val pendingPreview = pendingPreviewRequest
                        pendingPreviewRequest = null
                        if (pendingPreview != null) {
                            previewVoiceSample(
                                profile = pendingPreview.profile,
                                pitchOverride = pendingPreview.pitchOverride,
                                rateOverride = pendingPreview.rateOverride,
                                pauseMsOverride = pendingPreview.pauseMsOverride,
                                systemVoiceOverride = pendingPreview.systemVoiceOverride,
                                previewTag = pendingPreview.previewTag
                            )
                        }
                    }
                }
            }
        }
        ttsEngine = localTts
    }

    /**
     * Quét `tts.voices`, lọc chỉ lấy giọng tiếng Việt (`locale.language == "vi"`)
     * và KHÔNG yêu cầu mạng (`!voice.isNetworkConnectionRequired`, đã cài sẵn trên máy).
     * Sắp xếp ưu tiên giọng `quality >= Voice.QUALITY_HIGH` lên đầu.
     */
    private fun refreshAvailableOfflineVoices(engine: TextToSpeech) {
        val sortedVoices = runCatching {
            engine.voices
                ?.filter { voice ->
                    val isVietnamese = voice.locale?.language.equals("vi", ignoreCase = true)
                    val isOffline = !voice.isNetworkConnectionRequired
                    val isInstalled = voice.features?.contains(
                        TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED
                    ) != true
                    isVietnamese && isOffline && isInstalled
                }
                ?.sortedWith(
                    compareByDescending<Voice> { it.quality >= Voice.QUALITY_HIGH }
                        .thenByDescending { it.quality }
                        .thenBy { it.name.lowercase(Locale.ROOT) }
                )
                .orEmpty()
        }.getOrDefault(emptyList())

        cachedOfflineViVoices = sortedVoices
        cachedDeviceVoiceModels = sortedVoices.mapIndexed { idx, voice ->
            val gender = inferGenderLabel(voice.name)
            val qualityText = formatVoiceQualityLabel(voice.quality)
            val isHigh = voice.quality >= Voice.QUALITY_HIGH
            val shortCode = voice.name
                .substringAfterLast('-')
                .substringBefore('_')
                .uppercase(Locale.ROOT)
                .take(6)
            val friendlyName = buildString {
                append("Giọng Việt #${idx + 1}")
                if (gender != "Mặc định") append(" • $gender")
                if (shortCode.isNotBlank() && shortCode != "LOCAL") append(" ($shortCode)")
            }
            DeviceOfflineVoice(
                voiceName = voice.name,
                displayLabel = friendlyName,
                quality = voice.quality,
                qualityLabel = qualityText,
                isHighQuality = isHigh,
                genderHint = gender
            )
        }
    }

    private fun handleUtteranceError(utteranceId: String?) {
        val capturedGen = activeParagraphGeneration
        mainHandler.post {
            if (utteranceId != null && utteranceId == previewFinalUtteranceId) {
                previewFinalUtteranceId = ""
                isPreviewingVoice = false
                activePreviewTag = null
                publishState()
                return@post
            }
            if (!isPlaying || capturedGen != activeParagraphGeneration) return@post
            if (utteranceId == paragraphFinalUtteranceId) {
                advanceToNextParagraphOrChapter()
            }
        }
    }

    private fun advanceToNextParagraphOrChapter() {
        val nextPara = speakingParagraphIndex + 1
        if (nextPara < activeParagraphs.size) {
            speakParagraphAt(nextPara)
        } else if (sleepTimerEndOfChapter) {
            // Hẹn giờ tự tắt khi hết chương hiện tại
            clearSleepTimerInternal(publish = false)
            pausePlayback()
        } else if (activeChapterIndex + 1 < activeToc.size && activeBookId != null) {
            loadChapterAndSpeak(activeBookId!!, activeChapterIndex + 1, 0)
        } else {
            // Đã đọc hết chương cuối của sách
            pausePlayback()
        }
    }

    private fun applySleepTimer(minutes: Int) {
        sleepTimerHandler.removeCallbacks(sleepTimerTickRunnable)
        when {
            minutes == SLEEP_TIMER_END_OF_CHAPTER -> {
                sleepTimerEndAtMillis = 0L
                sleepTimerEndOfChapter = true
            }
            minutes > 0 -> {
                sleepTimerEndOfChapter = false
                sleepTimerEndAtMillis = System.currentTimeMillis() + minutes * 60_000L
                sleepTimerHandler.postDelayed(sleepTimerTickRunnable, 15_000L)
            }
            else -> {
                sleepTimerEndAtMillis = 0L
                sleepTimerEndOfChapter = false
            }
        }
        publishState()
    }

    private fun clearSleepTimerInternal(publish: Boolean) {
        sleepTimerHandler.removeCallbacks(sleepTimerTickRunnable)
        sleepTimerEndAtMillis = 0L
        sleepTimerEndOfChapter = false
        if (publish) publishState()
    }

    // -------------------------------------------------------------------------
    // 2. Điều phối đọc đoạn văn bằng QUEUE_ADD + playSilentUtterance (nhịp thở tự nhiên)
    // -------------------------------------------------------------------------

    private fun startOrJumpPlayback(bookId: String, chapterIndex: Int, paragraphIndex: Int) {
        if (activePrefs.ttsEngineType != TtsEngineType.AI_TTS && !isTtsReady) {
            pendingStartRequest = PendingStartRequest(bookId, chapterIndex, paragraphIndex)
            return
        }
        val needReload = activeBookId != bookId ||
            activeChapterIndex != chapterIndex ||
            activeParagraphs.isEmpty()

        if (needReload) {
            loadChapterAndSpeak(bookId, chapterIndex, paragraphIndex)
        } else {
            activeCharacterVoices = libraryManager.loadCharacterVoiceAssignments(bookId)
            speakParagraphAt(paragraphIndex)
        }
    }

    private fun loadChapterAndSpeak(bookId: String, chapterIndex: Int, paragraphIndex: Int) {
        serviceScope.launch {
            val (books, _) = libraryManager.loadLibraryIndex()
            val book = books.firstOrNull { it.id == bookId }
            val toc = libraryManager.loadBookToc(bookId)
            if (toc.isEmpty()) {
                withContext(Dispatchers.Main) { pausePlayback() }
                return@launch
            }
            val safeChap = chapterIndex.coerceIn(0, toc.lastIndex)
            val content = libraryManager.loadChapterContent(bookId, toc[safeChap])
            val charVoices = libraryManager.loadCharacterVoiceAssignments(bookId)
            val safePara = paragraphIndex.coerceIn(0, (content.paragraphs.size - 1).coerceAtLeast(0))

            withContext(Dispatchers.Main) {
                activeBookId = bookId
                activeBookTitle = book?.title ?: activeBookTitle.ifBlank { "Sách đang nghe" }
                activeToc = toc
                activeChapterIndex = safeChap
                activeChapterTitle = content.title
                activeParagraphs = content.paragraphs
                activeCharacterVoices = charVoices
                speakParagraphAt(safePara)
            }
        }
    }

    private fun speakParagraphAt(startIndex: Int, startSentenceIndex: Int = 0) {
        if (activeParagraphs.isEmpty()) return
        val isAiMode = activePrefs.ttsEngineType == TtsEngineType.AI_TTS
        if (!isAiMode && (!isTtsReady || ttsEngine == null)) return

        mainHandler.removeCallbacksAndMessages(null)
        stopVoicePreviewInternal(publish = false)
        cancelOnnxStreamPlayback()
        utteranceSentenceMap.clear()
        lastPlaybackNotice = null

        // Tìm đoạn kế tiếp có nội dung đọc được sau khi chuẩn hóa tiếng Việt
        var targetIdx = startIndex.coerceIn(0, activeParagraphs.lastIndex)
        var sentences: List<String> = emptyList()
        while (targetIdx < activeParagraphs.size) {
            sentences = VietnameseTtsTextNormalizer.splitIntoSmartSentences(activeParagraphs[targetIdx])
            if (sentences.isNotEmpty()) break
            targetIdx++
        }

        if (sentences.isEmpty()) {
            val bookId = activeBookId
            if (bookId != null && activeChapterIndex + 1 < activeToc.size) {
                loadChapterAndSpeak(bookId, activeChapterIndex + 1, 0)
            } else {
                pausePlayback()
            }
            return
        }

        val safeSentenceStart = startSentenceIndex.coerceIn(0, sentences.lastIndex)
        val remainingSentences = sentences.subList(safeSentenceStart, sentences.size)

        // =====================================================================
        // CHẾ ĐỘ AI NHÂN BẢN (TUYỆT ĐỐI TẮT HOÀN TOÀN GIỌNG HỆ THỐNG!)
        // =====================================================================
        if (isAiMode) {
            runCatching { ttsEngine?.stop() }
            val selectedClone = clonedVoiceRepo.findById(activePrefs.selectedClonedVoiceId)
                ?: clonedVoiceRepo.loadAll().firstOrNull()
            activeClonedVoice = selectedClone

            if (selectedClone == null) {
                activeUsingOnnxVoice = false
                isPlaying = false
                lastPlaybackNotice = "Chế độ AI Nhân bản đang bật (giọng hệ thống đã tắt). Hãy tạo & lưu giọng AI trong Studio."
                publishState()
                syncAmbientPlayback()
                return
            }

            val clonedEmbedding = clonedVoiceRepo.loadEmbedding(selectedClone)
            if (clonedEmbedding == null || clonedEmbedding.isEmpty()) {
                activeUsingOnnxVoice = false
                isPlaying = false
                lastPlaybackNotice = "Không tìm thấy vector đặc trưng (.emb) của giọng \"${selectedClone.name}\". Hãy tạo lại trong Studio."
                publishState()
                syncAmbientPlayback()
                return
            }

            activeParagraphGeneration += 1L
            val generation = activeParagraphGeneration
            speakingParagraphIndex = targetIdx
            currentParagraphSentences = sentences
            speakingSentenceIndex = safeSentenceStart
            speakingSentenceText = sentences[safeSentenceStart]
            activeSpeakerName = selectedClone.name
            activeUsingOnnxVoice = true
            isPlaying = true

            publishState()
            syncAmbientPlayback()
            persistProgressAsync(activeBookId, activeChapterIndex, targetIdx)

            ensureForegroundNotification(
                title = activeBookTitle.ifBlank { "Đang đọc sách" },
                subtitle = "${activeChapterTitle.ifBlank { "Chương ${activeChapterIndex + 1}" }} • ${selectedClone.name}",
                playing = true
            )

            val clonedRefCodes = clonedVoiceRepo.loadReferenceCodes(selectedClone)
            val pitchRatio = if (selectedClone.usedOnnxEncoder) 1.0f else selectedClone.estimatedPitchRatio.coerceIn(0.85f, 1.18f)
            launchPipelinedOnnxSentences(
                sentences = sentences,
                startSentenceIndex = safeSentenceStart,
                embedding = clonedEmbedding,
                referenceCodes = clonedRefCodes,
                referenceText = selectedClone.referenceText,
                pitchRatio = pitchRatio,
                generation = generation
            )
            return
        }

        // =====================================================================
        // CHẾ ĐỘ GIỌNG HỆ THỐNG (CHỈ DÙNG ANDROID TTS, KHÔNG DÙNG GIỌNG NHÂN BẢN)
        // =====================================================================
        val engine = ttsEngine ?: return
        activeClonedVoice = null
        activeUsingOnnxVoice = false

        val (targetProfile, speakerName) = resolveVoiceForParagraph(activeParagraphs, targetIdx)
        val isCharacterOverride = speakerName != null

        activeParagraphGeneration += 1L
        val generation = activeParagraphGeneration
        speakingParagraphIndex = targetIdx
        currentParagraphSentences = sentences
        speakingSentenceIndex = safeSentenceStart
        speakingSentenceText = sentences[safeSentenceStart]
        activeSpeakerName = speakerName
        isPlaying = true

        applyVoiceConfiguration(
            engine = engine,
            profile = targetProfile,
            isCharacterOverride = isCharacterOverride,
            pitchOverride = null
        )

        publishState()
        syncAmbientPlayback()
        persistProgressAsync(activeBookId, activeChapterIndex, targetIdx)

        val subtitle = buildString {
            append(activeChapterTitle.ifBlank { "Chương ${activeChapterIndex + 1}" })
            if (!activeSpeakerName.isNullOrBlank()) {
                append(" • ")
                append(activeSpeakerName)
            }
        }
        ensureForegroundNotification(
            title = activeBookTitle.ifBlank { "Đang đọc sách" },
            subtitle = subtitle,
            playing = true
        )

        enqueueSentencesWithNaturalPauses(
            engine = engine,
            sentences = remainingSentences,
            sentenceIndexOffset = safeSentenceStart,
            pauseMs = activePrefs.sentencePauseMs.toLong(),
            idPrefix = "para_${generation}_${activeChapterIndex}_$targetIdx",
            fallbackProfile = targetProfile,
            isCharacterOverride = isCharacterOverride
        )
    }

    /**
     * Tổng hợp giọng nói cuốn chiếu từng câu qua luồng ONNX Worker và phát qua [OnnxAudioTrackPlayer].
     * Ở chế độ AI Nhân bản tuyệt đối KHÔNG bao giờ rơi về giọng đọc hệ thống.
     */
    private fun launchPipelinedOnnxSentences(
        sentences: List<String>,
        startSentenceIndex: Int,
        embedding: FloatArray,
        referenceCodes: IntArray?,
        referenceText: String,
        pitchRatio: Float = 1.0f,
        generation: Long
    ) {
        onnxStreamJob = serviceScope.launch {
            // Đảm bảo quá trình sao chép assets/models/ -> filesDir/models/ và nạp GGUF + NeuCodec đã hoàn tất
            onnxEngine.ensureInitialized()
            if (!onnxEngine.isFullOnnxSynthesisReady()) {
                val diag = onnxEngine.getReadinessDiagnostic()
                withContext(Dispatchers.Main) {
                    if (isPlaying && generation == activeParagraphGeneration) {
                        activeUsingOnnxVoice = false
                        lastPlaybackNotice = "Không thể tổng hợp âm VieNeu GGUF + NeuCodec: $diag"
                        pausePlayback()
                    }
                }
                return@launch
            }

            val speed = (if (activePrefs.advancedCustomEnabled) {
                activePrefs.customSpeechRate
            } else {
                activePrefs.speechRate
            }).coerceIn(0.8f, 1.5f)
            val pauseMs = activePrefs.sentencePauseMs.coerceIn(
                LibraryManager.MIN_SENTENCE_PAUSE_MS,
                LibraryManager.MAX_SENTENCE_PAUSE_MS
            ).toLong()

            var nextDeferred = async {
                onnxEngine.synthesizeSentenceDetailed(
                    sentence = sentences[startSentenceIndex],
                    speakerEmbedding = embedding,
                    speed = speed,
                    referenceCodes = referenceCodes,
                    referenceText = referenceText
                )
            }

            for (idx in startSentenceIndex until sentences.size) {
                if (!isActive || generation != activeParagraphGeneration) return@launch

                val currentResult = nextDeferred.await()
                if (!isActive || generation != activeParagraphGeneration) return@launch

                val currentAudio = currentResult.audio
                if (currentAudio == null) {
                    val reason = currentResult.errorMessage ?: "lỗi không xác định"
                    withContext(Dispatchers.Main) {
                        if (isPlaying && generation == activeParagraphGeneration) {
                            activeUsingOnnxVoice = false
                            lastPlaybackNotice = "Không thể tổng hợp âm VieNeu cho đoạn văn này: $reason"
                            pausePlayback()
                        }
                    }
                    return@launch
                }

                if (idx + 1 < sentences.size) {
                    val lookaheadSentence = sentences[idx + 1]
                    nextDeferred = async {
                        onnxEngine.synthesizeSentenceDetailed(
                            sentence = lookaheadSentence,
                            speakerEmbedding = embedding,
                            speed = speed,
                            referenceCodes = referenceCodes,
                            referenceText = referenceText
                        )
                    }
                }

                withContext(Dispatchers.Main) {
                    if (isPlaying && generation == activeParagraphGeneration) {
                        speakingSentenceIndex = idx
                        speakingSentenceText = sentences[idx]
                        publishState()
                    }
                }

                val completed = onnxPlayer.playBlocking(
                    samples = currentAudio.pcmFloat,
                    sampleRate = currentAudio.sampleRate,
                    speed = speed,
                    pitch = pitchRatio
                )
                if (!completed || !isActive || generation != activeParagraphGeneration) return@launch

                if (idx + 1 < sentences.size && pauseMs > 0L) {
                    delay(pauseMs)
                }
            }

            // Hoàn tất tất cả các câu của đoạn -> chuyển sang đoạn hoặc chương kế tiếp
            val interParagraphPause = (pauseMs + 140L).coerceIn(220L, 950L)
            delay(interParagraphPause)
            withContext(Dispatchers.Main) {
                if (isPlaying && generation == activeParagraphGeneration) {
                    advanceToNextParagraphOrChapter()
                }
            }
        }
    }

    private fun cancelOnnxStreamPlayback() {
        onnxStreamJob?.cancel()
        onnxStreamJob = null
        onnxPlayer.stopImmediately()
    }

    /**
     * Đẩy toàn bộ danh sách câu đã tách thông minh vào hàng đợi Android TTS:
     * - Câu đầu dùng `QUEUE_FLUSH` để xóa hàng đợi cũ và phát ngay lập tức.
     * - Giữa mỗi câu chèn khoảng lặng tự nhiên bằng `playSilentUtterance(pauseMs, QUEUE_ADD, ...)`.
     * - Các câu kế tiếp dùng `QUEUE_ADD` để đọc nối tiếp mượt mà, không bị giật cục hay hụt tiếng.
     */
    private fun enqueueSentencesWithNaturalPauses(
        engine: TextToSpeech,
        sentences: List<String>,
        sentenceIndexOffset: Int = 0,
        pauseMs: Long,
        idPrefix: String,
        fallbackProfile: ReaderVoiceProfile,
        isCharacterOverride: Boolean
    ) {
        if (sentences.isEmpty()) return
        val clampedPauseMs = pauseMs.coerceIn(
            LibraryManager.MIN_SENTENCE_PAUSE_MS.toLong(),
            LibraryManager.MAX_SENTENCE_PAUSE_MS.toLong()
        )
        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
        }

        var lastUttId = ""
        sentences.forEachIndexed { idx, sentence ->
            val queueMode = if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
            if (idx > 0 && clampedPauseMs > 0L) {
                val silenceId = "${idPrefix}_pause_$idx"
                runCatching {
                    engine.playSilentUtterance(clampedPauseMs, TextToSpeech.QUEUE_ADD, silenceId)
                }
            }
            val uttId = "${idPrefix}_s$idx"
            lastUttId = uttId
            utteranceSentenceMap[uttId] = (sentenceIndexOffset + idx) to sentence
            val status = runCatching {
                engine.speak(sentence, queueMode, params, uttId)
            }.getOrDefault(TextToSpeech.ERROR)

            // Tự động khôi phục ngôn ngữ & thông số nếu engine trả về lỗi ở câu đầu tiên
            if (status != TextToSpeech.SUCCESS && idx == 0) {
                runCatching {
                    val langRes = engine.setLanguage(Locale("vi", "VN"))
                    if (langRes == TextToSpeech.LANG_MISSING_DATA || langRes == TextToSpeech.LANG_NOT_SUPPORTED) {
                        engine.language = Locale.getDefault()
                    }
                    engine.setPitch(activePrefs.resolvePlaybackPitch(fallbackProfile, isCharacterOverride))
                    engine.setSpeechRate(activePrefs.resolvePlaybackRate(fallbackProfile, isCharacterOverride))
                    engine.speak(sentence, TextToSpeech.QUEUE_FLUSH, params, uttId)
                }
            }
        }
        paragraphFinalUtteranceId = lastUttId
    }

    private fun pausePlayback() {
        mainHandler.removeCallbacksAndMessages(null)
        cancelOnnxStreamPlayback()
        activeParagraphGeneration += 1L
        isPlaying = false
        paragraphFinalUtteranceId = ""
        runCatching { ttsEngine?.stop() }
        syncAmbientPlayback()
        publishState()
        persistProgressAsync(activeBookId, activeChapterIndex, speakingParagraphIndex.coerceAtLeast(0))

        if (isForegroundActive && activeBookId != null) {
            updateNotification(
                title = activeBookTitle.ifBlank { "Sách nói đã tạm dừng" },
                subtitle = "${activeChapterTitle.ifBlank { "Chương ${activeChapterIndex + 1}" }} • Đã tạm dừng",
                playing = false
            )
        }
    }

    private fun stepParagraph(delta: Int) {
        val bookId = activeBookId ?: return
        val basePara = speakingParagraphIndex.coerceAtLeast(0)
        // Lùi / Tiến từng câu trong đoạn hiện tại trước khi nhảy sang đoạn liền kề
        if (currentParagraphSentences.size > 1 && speakingSentenceIndex in currentParagraphSentences.indices) {
            val targetSentence = speakingSentenceIndex + delta
            if (targetSentence in currentParagraphSentences.indices) {
                speakParagraphAt(basePara, startSentenceIndex = targetSentence)
                return
            }
        }

        val nextIdx = basePara + delta
        when {
            nextIdx in activeParagraphs.indices -> speakParagraphAt(nextIdx, startSentenceIndex = 0)
            delta > 0 && activeChapterIndex + 1 < activeToc.size -> {
                loadChapterAndSpeak(bookId, activeChapterIndex + 1, 0)
            }
            delta < 0 && activeChapterIndex > 0 -> {
                loadChapterAndSpeak(bookId, activeChapterIndex - 1, 0)
            }
        }
    }

    private fun reloadSettingsAndRefreshActiveAudio() {
        activePrefs = libraryManager.getReaderPreferences()
        val isAiMode = activePrefs.ttsEngineType == TtsEngineType.AI_TTS
        activeClonedVoice = if (isAiMode) {
            clonedVoiceRepo.findById(activePrefs.selectedClonedVoiceId)
        } else {
            null
        }
        // Khi đổi sang AI Nhân bản -> tắt ngay mọi âm thanh từ Android TTS hệ thống
        if (isAiMode) {
            runCatching { ttsEngine?.stop() }
        }
        val bookId = activeBookId
        if (bookId != null) {
            serviceScope.launch {
                val loadedChars = libraryManager.loadCharacterVoiceAssignments(bookId)
                withContext(Dispatchers.Main) {
                    activeCharacterVoices = loadedChars
                    syncAmbientPlayback()
                    publishState()
                    if (!isAiMode) {
                        val engine = ttsEngine
                        if (engine != null && isTtsReady) {
                            applyVoiceConfiguration(
                                engine = engine,
                                profile = activePrefs.voiceProfile,
                                isCharacterOverride = false,
                                force = true
                            )
                        }
                    }
                    if (isPlaying && speakingParagraphIndex >= 0) {
                        speakParagraphAt(speakingParagraphIndex)
                    }
                }
            }
        } else {
            if (!isAiMode) {
                val engine = ttsEngine
                if (engine != null && isTtsReady) {
                    applyVoiceConfiguration(
                        engine = engine,
                        profile = activePrefs.voiceProfile,
                        isCharacterOverride = false,
                        force = true
                    )
                }
            }
            syncAmbientPlayback()
            publishState()
        }
    }

    private fun stopVoicePreviewInternal(publish: Boolean) {
        previewAudioJob?.cancel()
        previewAudioJob = null
        previewFinalUtteranceId = ""
        if (isPreviewingVoice) {
            isPreviewingVoice = false
            activePreviewTag = null
            onnxPlayer.stopImmediately()
            if (!isPlaying) {
                runCatching { ttsEngine?.stop() }
            }
            if (publish) publishState()
        }
    }

    /**
     * Đọc mẫu giọng Hệ thống (chỉ hoạt động khi đang ở chế độ Giọng Hệ thống).
     * Bấm lần 1 -> phát, bấm lần 2 -> tắt ngay.
     */
    private fun previewVoiceSample(
        profile: ReaderVoiceProfile,
        pitchOverride: Float? = null,
        rateOverride: Float? = null,
        pauseMsOverride: Int? = null,
        systemVoiceOverride: String? = null,
        previewTag: String? = null
    ) {
        // Khi đang bật chế độ AI Nhân bản -> tuyệt đối không phát tiếng hệ thống!
        if (activePrefs.ttsEngineType == TtsEngineType.AI_TTS) {
            stopVoicePreviewInternal(publish = false)
            runCatching { ttsEngine?.stop() }
            lastPlaybackNotice = "Đang ở chế độ AI Nhân bản — giọng đọc hệ thống đã tắt hoàn toàn."
            publishState()
            return
        }

        val engine = ttsEngine
        if (engine == null || !isTtsReady) {
            pendingPreviewRequest = PendingPreviewRequest(
                profile = profile,
                pitchOverride = pitchOverride,
                rateOverride = rateOverride,
                pauseMsOverride = pauseMsOverride,
                systemVoiceOverride = systemVoiceOverride,
                previewTag = previewTag
            )
            return
        }
        if (isPlaying) {
            pausePlayback()
        }
        stopVoicePreviewInternal(publish = false)

        activeParagraphGeneration += 1L
        paragraphFinalUtteranceId = ""
        lastPlaybackNotice = null

        applyVoiceConfiguration(
            engine = engine,
            profile = profile,
            isCharacterOverride = false,
            pitchOverride = pitchOverride,
            rateOverride = rateOverride,
            systemVoiceOverride = systemVoiceOverride,
            force = true
        )

        val previewSentences = VietnameseTtsTextNormalizer.splitIntoSmartSentences(
            "Xin chào, đây là giọng đọc thử của sách nói ngoại tuyến. " +
                "Bạn có thể chỉnh độ cao, tốc độ và nhịp nghỉ giữa các câu sao cho êm tai nhất."
        )
        val pauseMs = (pauseMsOverride ?: activePrefs.sentencePauseMs).toLong()
        enqueueSentencesWithNaturalPauses(
            engine = engine,
            sentences = previewSentences,
            pauseMs = pauseMs,
            idPrefix = "preview_${System.nanoTime()}",
            fallbackProfile = profile,
            isCharacterOverride = false
        )
        // Chuyển final utterance sang previewFinalUtteranceId để tự tắt trạng thái nút nghe thử khi đọc xong
        previewFinalUtteranceId = paragraphFinalUtteranceId
        paragraphFinalUtteranceId = ""
        isPreviewingVoice = true
        activePreviewTag = previewTag ?: "system_preview"
        publishState()
    }

    /**
     * Nghe thử giọng AI Nhân bản (dùng ONNX hoặc phát trực tiếp mẫu âm thanh gốc của giọng nhân bản qua AudioTrack).
     * Tuyệt đối KHÔNG gọi giọng hệ thống Android TTS!
     */
    private fun previewClonedVoiceSample(voiceId: String?, previewTag: String?) {
        if (isPlaying) {
            pausePlayback()
        }
        stopVoicePreviewInternal(publish = false)
        runCatching { ttsEngine?.stop() }
        lastPlaybackNotice = null

        val targetVoice = clonedVoiceRepo.findById(voiceId)
            ?: clonedVoiceRepo.findById(activePrefs.selectedClonedVoiceId)
            ?: clonedVoiceRepo.loadAll().firstOrNull()

        if (targetVoice == null) {
            lastPlaybackNotice = "Chưa có giọng AI nhân bản nào để nghe thử."
            publishState()
            return
        }

        val tag = previewTag ?: "cloned_${targetVoice.id}"
        isPreviewingVoice = true
        activePreviewTag = tag
        publishState()

        previewAudioJob = serviceScope.launch {
            try {
                onnxEngine.ensureInitialized()
                val embedding = clonedVoiceRepo.loadEmbedding(targetVoice)
                val refCodes = clonedVoiceRepo.loadReferenceCodes(targetVoice)
                val synthResult = if (embedding != null && onnxEngine.isFullOnnxSynthesisReady()) {
                    onnxEngine.synthesizeSentenceDetailed(
                        sentence = OnnxModelAssetHelper.AI_VOICE_TEST_SENTENCE,
                        speakerEmbedding = embedding,
                        speed = activePrefs.speechRate.coerceIn(0.8f, 1.5f),
                        referenceCodes = refCodes,
                        referenceText = targetVoice.referenceText
                    )
                } else {
                    null
                }

                val synthesized = synthResult?.audio
                if (synthesized != null) {
                    val pitchRatio = if (targetVoice.usedOnnxEncoder) 1.0f else targetVoice.estimatedPitchRatio.coerceIn(0.85f, 1.18f)
                    onnxPlayer.playBlocking(
                        samples = synthesized.pcmFloat,
                        sampleRate = synthesized.sampleRate,
                        speed = 1.0f,
                        pitch = pitchRatio
                    )
                } else {
                    // Nếu tổng hợp ONNX báo lỗi chi tiết, hiển thị rõ lý do đồng thời phát bản WAV gốc chuẩn 44-byte
                    val errDetail = synthResult?.errorMessage
                    if (!errDetail.isNullOrBlank()) {
                        withContext(Dispatchers.Main) {
                            lastPlaybackNotice = "Lỗi tổng hợp ONNX nghe thử: $errDetail"
                            publishState()
                        }
                    }
                    val wavFile = clonedVoiceRepo.ensureReferenceWavFile(targetVoice)
                    if (wavFile != null && onnxPlayer.playWavFileBlocking(wavFile)) {
                        // Played crisp 16-bit PCM WAV via MediaPlayer
                    } else {
                        val refAudio = clonedVoiceRepo.loadReferenceAudio(targetVoice)
                        if (refAudio != null && refAudio.isNotEmpty()) {
                            onnxPlayer.playBlocking(
                                samples = refAudio,
                                sampleRate = targetVoice.sampleRate,
                                speed = 1.0f
                            )
                        } else {
                            withContext(Dispatchers.Main) {
                                lastPlaybackNotice = "Không tìm thấy dữ liệu mẫu giọng của \"${targetVoice.name}\"."
                            }
                        }
                    }
                }
            } finally {
                withContext(Dispatchers.Main) {
                    if (activePreviewTag == tag) {
                        isPreviewingVoice = false
                        activePreviewTag = null
                        publishState()
                    }
                }
            }
        }
    }

    private fun syncAmbientPlayback() {
        val customId = activePrefs.selectedCustomAmbientId
        val customPath = if (!customId.isNullOrBlank()) {
            libraryManager.findCustomAmbientFilePath(customId)
        } else {
            null
        }
        val track = activePrefs.ambientSound
        val volume = activePrefs.ambientVolume
        val shouldPlayForBook = isPlaying && activePrefs.autoPlayAmbientWithAudio
        if (shouldPlayForBook || isPreviewingAmbient) {
            when {
                !customPath.isNullOrBlank() -> {
                    ambientEngine.startCustomFile(customPath, volume)
                    ambientEngine.updateVolume(volume)
                }
                track != AmbientSoundTrack.NONE -> {
                    ambientEngine.start(track, volume)
                    ambientEngine.updateVolume(volume)
                }
                else -> ambientEngine.stop()
            }
        } else {
            ambientEngine.stop()
        }
    }

    // -------------------------------------------------------------------------
    // 3. Chọn giọng gốc trên thiết bị & Fallback thông minh cho 6 hồ sơ Nam/Nữ
    // -------------------------------------------------------------------------

    private fun resolveVoiceForParagraph(
        paragraphs: List<String>,
        paraIndex: Int
    ): Pair<ReaderVoiceProfile, String?> {
        if (activePrefs.characterVoicesEnabled &&
            activeCharacterVoices.isNotEmpty() &&
            paraIndex in paragraphs.indices
        ) {
            val detectedSpeaker = StoryCharacterScanner.detectSpeakerForParagraph(
                paragraph = paragraphs[paraIndex],
                previousParagraph = paragraphs.getOrNull(paraIndex - 1),
                characterNames = activeCharacterVoices.keys.toList()
            )
            if (detectedSpeaker != null) {
                val charVoice = activeCharacterVoices[detectedSpeaker]
                if (charVoice != null) {
                    return charVoice to detectedSpeaker
                }
            }
        }
        return activePrefs.voiceProfile to null
    }

    private fun applyVoiceConfiguration(
        engine: TextToSpeech,
        profile: ReaderVoiceProfile,
        isCharacterOverride: Boolean,
        pitchOverride: Float? = null,
        rateOverride: Float? = null,
        systemVoiceOverride: String? = null,
        force: Boolean = false
    ) {
        val requestedSystemVoice = systemVoiceOverride
            ?: if (!isCharacterOverride) activePrefs.selectedSystemVoiceName else null

        val chosenVoice = pickBestOfflineVietnameseVoice(
            voices = cachedOfflineViVoices,
            profile = profile,
            preferredSystemVoiceName = requestedSystemVoice
        )

        // Nếu chọn hồ sơ giọng Nam nhưng thiết bị KHÔNG có giọng Nam gốc -> tự động dùng giọng Nữ chất lượng cao nhất + hạ nhẹ pitch
        val usedFemaleFallbackForMale = profile.isMale &&
            chosenVoice != null &&
            requestedSystemVoice.isNullOrBlank() &&
            !isLikelyMaleVoice(chosenVoice.name)

        val basePitch = pitchOverride ?: activePrefs.resolvePlaybackPitch(profile, isCharacterOverride)
        val adjustedPitch = if (usedFemaleFallbackForMale && pitchOverride == null && !activePrefs.advancedCustomEnabled) {
            (basePitch * 0.94f).coerceIn(LibraryManager.MIN_VOICE_PITCH, LibraryManager.MAX_VOICE_PITCH)
        } else {
            basePitch
        }
        val targetPitch = adjustedPitch.coerceIn(LibraryManager.MIN_VOICE_PITCH, LibraryManager.MAX_VOICE_PITCH)
        val targetRate = (rateOverride ?: activePrefs.resolvePlaybackRate(profile, isCharacterOverride))
            .coerceIn(0.65f, 2.20f)

        val resolvedVoiceName = chosenVoice?.name.orEmpty()
        val needUpdate = force ||
            lastAppliedVoiceName != resolvedVoiceName ||
            abs(lastAppliedPitch - targetPitch) > 0.01f ||
            abs(lastAppliedRate - targetRate) > 0.01f
        if (!needUpdate) return

        if (chosenVoice != null) {
            runCatching { engine.voice = chosenVoice }
        }
        runCatching {
            engine.setPitch(targetPitch)
            engine.setSpeechRate(targetRate)
        }
        lastAppliedVoiceName = resolvedVoiceName
        lastAppliedPitch = targetPitch
        lastAppliedRate = targetRate
    }

    /**
     * Chọn giọng tiếng Việt offline tối ưu từ danh sách đã sắp xếp ưu tiên `QUALITY_HIGH`:
     * 1. Nếu người dùng chọn đích danh một giọng gốc trên máy -> dùng chính giọng đó.
     * 2. Nếu ở chế độ Tự động -> chọn giọng đúng giới tính Nam/Nữ ưu tiên chất lượng cao nhất;
     *    nếu máy không có giọng Nam gốc -> tự fallback về giọng Nữ chất lượng cao nhất (sẽ hạ pitch tự nhiên).
     */
    private fun pickBestOfflineVietnameseVoice(
        voices: List<Voice>,
        profile: ReaderVoiceProfile,
        preferredSystemVoiceName: String?
    ): Voice? {
        if (voices.isEmpty()) return null

        if (!preferredSystemVoiceName.isNullOrBlank()) {
            val exact = voices.firstOrNull { it.name == preferredSystemVoiceName }
            if (exact != null) return exact
        }

        val maleCandidates = voices.filter { isLikelyMaleVoice(it.name) }
        val femaleCandidates = voices.filter { !isLikelyMaleVoice(it.name) }

        val pool = if (profile.isMale) {
            // Ưu tiên giọng Nam gốc chất lượng cao; nếu không có -> fallback giọng Nữ chất lượng cao nhất
            maleCandidates.ifEmpty { femaleCandidates.ifEmpty { voices } }
        } else {
            femaleCandidates.ifEmpty { voices }
        }
        return pool[profile.slotIndex % pool.size]
    }

    private fun isLikelyMaleVoice(voiceName: String): Boolean {
        val n = voiceName.lowercase(Locale.ROOT)
        return listOf("male", "-vic-", "-vie-", "nam", "_m_", "-m-").any { n.contains(it) }
    }

    private fun inferGenderLabel(voiceName: String): String {
        val n = voiceName.lowercase(Locale.ROOT)
        return when {
            isLikelyMaleVoice(n) -> "Nam"
            listOf("female", "-gft-", "-vif-", "-vid-", "nu", "_f_", "-f-").any { n.contains(it) } -> "Nữ"
            else -> "Mặc định"
        }
    }

    private fun formatVoiceQualityLabel(quality: Int): String = when {
        quality >= Voice.QUALITY_VERY_HIGH -> "Rất cao"
        quality >= Voice.QUALITY_HIGH -> "Chất lượng cao"
        quality >= Voice.QUALITY_NORMAL -> "Tiêu chuẩn"
        else -> "Cơ bản"
    }

    private fun publishState() {
        val activeRate = if (activePrefs.advancedCustomEnabled) {
            activePrefs.customSpeechRate
        } else {
            activePrefs.speechRate
        }
        val remainingMins = if (sleepTimerEndAtMillis > 0L) {
            val diffMs = (sleepTimerEndAtMillis - System.currentTimeMillis()).coerceAtLeast(0L)
            ((diffMs + 59_999L) / 60_000L).toInt()
        } else 0
        AudioPlaybackStateHolder.update {
            BookAudioPlaybackState(
                bookId = activeBookId,
                bookTitle = activeBookTitle,
                chapterIndex = activeChapterIndex,
                chapterTitle = activeChapterTitle,
                totalChapters = activeToc.size,
                paragraphIndex = speakingParagraphIndex,
                activeSentenceIndex = speakingSentenceIndex,
                activeSentenceText = speakingSentenceText,
                isPlaying = isPlaying,
                isPaused = !isPlaying && activeBookId != null,
                isTtsReady = isTtsReady,
                isPreviewingVoice = isPreviewingVoice,
                activePreviewTag = activePreviewTag,
                playbackNotice = lastPlaybackNotice,
                activeSpeakerName = activeSpeakerName,
                activeClonedVoiceId = activeClonedVoice?.id,
                activeClonedVoiceName = activeClonedVoice?.name,
                isUsingOnnxVoice = activeUsingOnnxVoice,
                speechRate = activeRate,
                voiceProfile = activePrefs.voiceProfile,
                availableDeviceVoices = cachedDeviceVoiceModels,
                sleepTimerEndAtMillis = sleepTimerEndAtMillis,
                sleepTimerEndOfChapter = sleepTimerEndOfChapter,
                sleepTimerRemainingMinutes = remainingMins
            )
        }
    }

    private fun persistProgressAsync(bookId: String?, chapterIndex: Int, paragraphIndex: Int) {
        if (bookId == null) return
        serviceScope.launch {
            runCatching {
                libraryManager.updateReadingProgress(bookId, chapterIndex, paragraphIndex)
            }
        }
    }

    private fun stopPlaybackAndService() {
        mainHandler.removeCallbacksAndMessages(null)
        stopVoicePreviewInternal(publish = false)
        cancelOnnxStreamPlayback()
        clearSleepTimerInternal(publish = false)
        activeParagraphGeneration += 1L
        isPlaying = false
        isPreviewingAmbient = false
        paragraphFinalUtteranceId = ""
        speakingSentenceIndex = -1
        speakingSentenceText = ""
        currentParagraphSentences = emptyList()
        utteranceSentenceMap.clear()
        runCatching { ttsEngine?.stop() }
        ambientEngine.stop()
        persistProgressAsync(activeBookId, activeChapterIndex, speakingParagraphIndex.coerceAtLeast(0))
        activeBookId = null
        activeSpeakerName = null
        speakingParagraphIndex = -1
        AudioPlaybackStateHolder.reset()
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        isForegroundActive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    private fun ensureForegroundNotification(title: String, subtitle: String, playing: Boolean) {
        val notification = buildNotification(title, subtitle, playing)
        if (!isForegroundActive) {
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        AUDIO_NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(AUDIO_NOTIFICATION_ID, notification)
                }
                isForegroundActive = true
            }
        } else {
            updateNotification(title, subtitle, playing)
        }
    }

    private fun updateNotification(title: String, subtitle: String, playing: Boolean) {
        runCatching {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(AUDIO_NOTIFICATION_ID, buildNotification(title, subtitle, playing))
        }
    }

    private fun buildNotification(title: String, subtitle: String, playing: Boolean): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPending = PendingIntent.getActivity(
            this,
            10,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        fun serviceActionPending(action: String, reqCode: Int): PendingIntent {
            val i = Intent(this, BookAudioPlaybackService::class.java).apply { this.action = action }
            return PendingIntent.getService(
                this,
                reqCode,
                i,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val toggleLabel = if (playing) "Tạm dừng" else "Phát tiếp"
        return NotificationCompat.Builder(this, AUDIO_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle)
            .setContentIntent(openPending)
            .setOngoing(playing)
            .setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Trước", serviceActionPending(ACTION_PREV_PARAGRAPH, 11))
            .addAction(0, toggleLabel, serviceActionPending(ACTION_TOGGLE_PLAY_PAUSE, 12))
            .addAction(0, "Tiếp", serviceActionPending(ACTION_NEXT_PARAGRAPH, 13))
            .addAction(0, "Dừng", serviceActionPending(ACTION_STOP, 14))
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                AUDIO_CHANNEL_ID,
                "Nghe đọc sách chạy nền",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Giữ giọng đọc sách và âm thanh nền chạy liên tục khi thoát màn hình hoặc thu nhỏ ứng dụng."
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        sleepTimerHandler.removeCallbacksAndMessages(null)
        cancelOnnxStreamPlayback()
        ambientEngine.stop()
        runCatching {
            ttsEngine?.stop()
            ttsEngine?.shutdown()
        }
        ttsEngine = null
        runCatching { onnxEngine.close() }
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val SLEEP_TIMER_OFF = 0
        const val SLEEP_TIMER_END_OF_CHAPTER = -1

        private const val ACTION_PLAY_AT = "com.example.service.AUDIO_PLAY_AT"
        private const val ACTION_TOGGLE_PLAY_PAUSE = "com.example.service.AUDIO_TOGGLE"
        private const val ACTION_PAUSE = "com.example.service.AUDIO_PAUSE"
        private const val ACTION_PREV_PARAGRAPH = "com.example.service.AUDIO_PREV"
        private const val ACTION_NEXT_PARAGRAPH = "com.example.service.AUDIO_NEXT"
        private const val ACTION_SYNC_SETTINGS = "com.example.service.AUDIO_SYNC_SETTINGS"
        private const val ACTION_REFRESH_VOICES = "com.example.service.AUDIO_REFRESH_VOICES"
        private const val ACTION_PREVIEW_VOICE = "com.example.service.AUDIO_PREVIEW_VOICE"
        private const val ACTION_PREVIEW_CLONED_VOICE = "com.example.service.AUDIO_PREVIEW_CLONED_VOICE"
        private const val ACTION_STOP_PREVIEW = "com.example.service.AUDIO_STOP_PREVIEW"
        private const val ACTION_PREVIEW_AMBIENT = "com.example.service.AUDIO_PREVIEW_AMBIENT"
        private const val ACTION_SET_SLEEP_TIMER = "com.example.service.AUDIO_SET_SLEEP_TIMER"
        private const val ACTION_STOP = "com.example.service.AUDIO_STOP"

        private const val EXTRA_BOOK_ID = "extra_book_id"
        private const val EXTRA_CHAPTER_INDEX = "extra_chapter_index"
        private const val EXTRA_PARAGRAPH_INDEX = "extra_paragraph_index"
        private const val EXTRA_VOICE_KEY = "extra_voice_key"
        private const val EXTRA_VOICE_PITCH = "extra_voice_pitch"
        private const val EXTRA_VOICE_RATE = "extra_voice_rate"
        private const val EXTRA_SENTENCE_PAUSE_MS = "extra_sentence_pause_ms"
        private const val EXTRA_SYSTEM_VOICE_NAME = "extra_system_voice_name"
        private const val EXTRA_PREVIEW_TAG = "extra_preview_tag"
        private const val EXTRA_CLONED_VOICE_ID = "extra_cloned_voice_id"
        private const val EXTRA_AMBIENT_PREVIEW_ACTIVE = "extra_ambient_preview_active"
        private const val EXTRA_SLEEP_TIMER_MINUTES = "extra_sleep_timer_minutes"

        fun playAt(context: Context, bookId: String, chapterIndex: Int, paragraphIndex: Int) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_PLAY_AT
                putExtra(EXTRA_BOOK_ID, bookId)
                putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                putExtra(EXTRA_PARAGRAPH_INDEX, paragraphIndex)
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun togglePlayPause(
            context: Context,
            bookId: String? = null,
            chapterIndex: Int? = null,
            paragraphIndex: Int? = null
        ) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_TOGGLE_PLAY_PAUSE
                if (bookId != null) putExtra(EXTRA_BOOK_ID, bookId)
                if (chapterIndex != null) putExtra(EXTRA_CHAPTER_INDEX, chapterIndex)
                if (paragraphIndex != null) putExtra(EXTRA_PARAGRAPH_INDEX, paragraphIndex)
            }
            if (AudioPlaybackStateHolder.state.value.isPlaying) {
                context.applicationContext.startService(intent)
            } else {
                ContextCompat.startForegroundService(context.applicationContext, intent)
            }
        }

        fun pause(context: Context) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_PAUSE
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun prevParagraph(context: Context) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_PREV_PARAGRAPH
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun nextParagraph(context: Context) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_NEXT_PARAGRAPH
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun syncSettings(context: Context) {
            if (!AudioPlaybackStateHolder.state.value.hasActiveSession &&
                !AudioPlaybackStateHolder.state.value.isPreviewingVoice
            ) {
                return
            }
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_SYNC_SETTINGS
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun refreshDeviceVoices(context: Context) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_REFRESH_VOICES
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun previewVoice(
            context: Context,
            profile: ReaderVoiceProfile,
            pitch: Float,
            rate: Float? = null,
            pauseMs: Int? = null,
            systemVoiceName: String? = null,
            previewTag: String? = null
        ) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_PREVIEW_VOICE
                putExtra(EXTRA_VOICE_KEY, profile.storageKey)
                putExtra(EXTRA_VOICE_PITCH, pitch)
                if (rate != null) putExtra(EXTRA_VOICE_RATE, rate)
                if (pauseMs != null) putExtra(EXTRA_SENTENCE_PAUSE_MS, pauseMs)
                if (systemVoiceName != null) putExtra(EXTRA_SYSTEM_VOICE_NAME, systemVoiceName)
                if (previewTag != null) putExtra(EXTRA_PREVIEW_TAG, previewTag)
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun previewClonedVoice(
            context: Context,
            voiceId: String?,
            previewTag: String? = null
        ) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_PREVIEW_CLONED_VOICE
                if (voiceId != null) putExtra(EXTRA_CLONED_VOICE_ID, voiceId)
                if (previewTag != null) putExtra(EXTRA_PREVIEW_TAG, previewTag)
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun stopPreview(context: Context) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_STOP_PREVIEW
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun setAmbientPreview(context: Context, active: Boolean) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_PREVIEW_AMBIENT
                putExtra(EXTRA_AMBIENT_PREVIEW_ACTIVE, active)
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun setSleepTimer(context: Context, minutes: Int) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_SET_SLEEP_TIMER
                putExtra(EXTRA_SLEEP_TIMER_MINUTES, minutes)
            }
            runCatching { context.applicationContext.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context.applicationContext, BookAudioPlaybackService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { context.applicationContext.startService(intent) }
        }
    }
}
