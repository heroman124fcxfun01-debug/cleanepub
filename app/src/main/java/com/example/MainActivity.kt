package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.example.data.ErrorMemoryManager
import com.example.pipeline.FilePathResolver
import com.example.pipeline.ProcessingPhase
import com.example.service.BookAudioPlaybackService
import com.example.ui.BookLibraryViewModel
import com.example.ui.LibraryImportFlowState
import com.example.ui.MainViewModel
import com.example.ui.components.EpubMetadataEditorDialog
import com.example.ui.components.FloatingBackgroundAudioBanner
import com.example.ui.components.SettingsDialog
import com.example.ui.screens.BookLibraryScreen
import com.example.ui.screens.BookReaderScreen
import com.example.ui.screens.MainScreen
import com.example.ui.screens.ManualEditorScreen
import com.example.ui.screens.ProcessingScreen
import com.example.ui.screens.ReviewScreen
import com.example.ui.theme.EpubCleanAITheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

const val ROUTE_HOME = "home"
const val ROUTE_BOOK_LIBRARY = "book_library"
const val ROUTE_BOOK_READER = "book_reader"
const val ROUTE_PROCESSING = "processing"
const val ROUTE_REVIEW = "review"
const val ROUTE_MANUAL_EDITOR = "manual_editor"

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialize PDFBox-Android resource loader (100% on-device)
        PDFBoxResourceLoader.init(applicationContext)

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                android.graphics.Color.TRANSPARENT,
                android.graphics.Color.TRANSPARENT
            )
        )

        setContent {
            val viewModel: MainViewModel = viewModel()
            val libraryViewModel: BookLibraryViewModel = viewModel()
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
            var forceLightSystemBars by remember { mutableStateOf(false) }

            LaunchedEffect(isDarkMode, forceLightSystemBars) {
                val useDarkBars = isDarkMode && !forceLightSystemBars
                enableEdgeToEdge(
                    statusBarStyle = if (useDarkBars) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (useDarkBars) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    }
                )
            }

            EpubCleanAITheme(darkTheme = isDarkMode) {
                EpubCleanNavHost(
                    viewModel = viewModel,
                    libraryViewModel = libraryViewModel,
                    onForceLightBarsChanged = { forceLightSystemBars = it }
                )
            }
        }
    }
}

@Composable
private fun EpubCleanNavHost(
    viewModel: MainViewModel,
    libraryViewModel: BookLibraryViewModel,
    onForceLightBarsChanged: (Boolean) -> Unit
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
    val isResumingDraft by viewModel.isResumingDraft.collectAsStateWithLifecycle()
    val autoSaveDraft by viewModel.autoSaveDraft.collectAsStateWithLifecycle()
    val customReplaceRules by viewModel.customReplaceRules.collectAsStateWithLifecycle()
    val errorMemoryAutoApply by viewModel.errorMemoryAutoApply.collectAsStateWithLifecycle()
    val errorMemoryRuleCount by viewModel.errorMemoryRuleCount.collectAsStateWithLifecycle()
    val statusToastMessage by viewModel.statusToastMessage.collectAsStateWithLifecycle()
    val hasAllFilesAccess by viewModel.hasAllFilesAccess.collectAsStateWithLifecycle()
    val snapshot by viewModel.processingSnapshot.collectAsStateWithLifecycle()
    val saveUiState by viewModel.saveUiState.collectAsStateWithLifecycle()
    val homeError by viewModel.homeErrorBanner.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val cleaningOptions by viewModel.cleaningOptions.collectAsStateWithLifecycle()
    val isLoadingMetadata by viewModel.isLoadingMetadata.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val customSaveFolderLabel by viewModel.customSaveFolderLabel.collectAsStateWithLifecycle()
    val manualEditorState by viewModel.manualEditorState.collectAsStateWithLifecycle()

    val libraryBooks by libraryViewModel.books.collectAsStateWithLifecycle()
    val libraryCollections by libraryViewModel.collections.collectAsStateWithLifecycle()
    val isImportingLibraryBook by libraryViewModel.isImporting.collectAsStateWithLifecycle()
    val libraryImportFlowState by libraryViewModel.importFlowState.collectAsStateWithLifecycle()
    val libraryToastMessage by libraryViewModel.statusMessage.collectAsStateWithLifecycle()
    val bookReaderState by libraryViewModel.readerState.collectAsStateWithLifecycle()
    val bookReaderPrefs by libraryViewModel.readerPrefs.collectAsStateWithLifecycle()
    val audioPlaybackState by libraryViewModel.audioPlaybackState.collectAsStateWithLifecycle()

    LaunchedEffect(statusToastMessage) {
        val msg = statusToastMessage
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            viewModel.clearStatusToast()
        }
    }

    LaunchedEffect(libraryToastMessage) {
        val msg = libraryToastMessage
        if (!msg.isNullOrBlank()) {
            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            libraryViewModel.clearStatusMessage()
        }
    }

    val currentBackStackEntry by navController.currentBackStackEntryAsState()
    LaunchedEffect(currentBackStackEntry?.destination?.route) {
        val route = currentBackStackEntry?.destination?.route
        onForceLightBarsChanged(route == ROUTE_MANUAL_EDITOR || route == ROUTE_BOOK_READER)
    }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showReviewMetadataDialog by remember { mutableStateOf(false) }
    var coverPickerForReview by remember { mutableStateOf(false) }

    // Re-check MANAGE_EXTERNAL_STORAGE & newly installed offline TTS voices on ON_RESUME
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStoragePermission()
                if (libraryViewModel.audioPlaybackState.value.isTtsReady) {
                    BookAudioPlaybackService.refreshDeviceVoices(context)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request POST_NOTIFICATIONS on Android 13+ for Foreground Service progress & background audio controls
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Service continues safely regardless of notification permission */ }
    )

    LaunchedEffect(audioPlaybackState.isPlaying) {
        if (audioPlaybackState.isPlaying && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notifGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!notifGranted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    val legacyStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = { viewModel.refreshStoragePermission() }
    )

    // Zero-permission Android Photo Picker for changing book cover
    val coverPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.onCoverImagePicked(uri, forReviewScreen = coverPickerForReview)
            }
        }
    )

    // System Folder Picker (SAF OpenDocumentTree) for re-selecting output save folder
    val saveFolderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree(),
        onResult = { treeUri ->
            if (treeUri != null) {
                viewModel.onSaveFolderPicked(treeUri)
            }
        }
    )

    // Upgrade 1.3: SAF Export Error Memory Library (`EpubCleanAI_ErrorLib.json`)
    val exportErrorLibLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json"),
        onResult = { uri ->
            if (uri != null) {
                viewModel.exportErrorMemoryLibrary(uri)
            }
        }
    )

    // Upgrade 1.3: SAF Import & Merge Error Memory Library (`.json`)
    val importErrorLibLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                viewModel.importErrorMemoryLibrary(uri)
            }
        }
    )

    // Direct offline import picker for Book Library (.epub / .pdf) -> prompts "Có muốn dọn sách không?"
    val libraryImportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                libraryViewModel.onLibraryFilePicked(uri)
            }
        }
    )

    // System file picker for .epub and .pdf -> opens Metadata & Cover Editor before scanning
    val documentPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            if (uri != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val notifGranted = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                    if (!notifGranted) {
                        notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
                viewModel.onFilePicked(uri)
            }
        }
    )

    // Automatic screen transition when processing completes in foreground or background
    LaunchedEffect(snapshot.phase, libraryImportFlowState) {
        val currentRoute = navController.currentDestination?.route
        if (libraryImportFlowState is LibraryImportFlowState.CleaningInProgress) {
            when (snapshot.phase) {
                ProcessingPhase.COMPLETED -> {
                    val ws = snapshot.workspace
                    if (ws != null) {
                        val totalFixes = snapshot.stats.junkLinksRemoved +
                            snapshot.stats.whitespaceFixes +
                            snapshot.stats.spellingFixes +
                            snapshot.stats.mergedParagraphs +
                            snapshot.stats.headersFootersRemoved +
                            snapshot.stats.footnotesRemoved +
                            snapshot.stats.unicodeSanitized +
                            snapshot.stats.dialoguesFormatted
                        libraryViewModel.onCleaningCompletedForLibrary(ws, totalFixes)
                    }
                }
                ProcessingPhase.ERROR -> {
                    libraryViewModel.onCleaningFailedOrCancelled(
                        snapshot.errorMessage ?: "Quá trình dọn sách gặp lỗi."
                    )
                }
                ProcessingPhase.CANCELLED -> {
                    libraryViewModel.onCleaningFailedOrCancelled(null)
                }
                else -> Unit
            }
        } else if (snapshot.phase == ProcessingPhase.COMPLETED && currentRoute == ROUTE_PROCESSING) {
            navController.navigate(ROUTE_REVIEW) {
                popUpTo(ROUTE_PROCESSING) { inclusive = true }
                launchSingleTop = true
            }
        }
    }

    fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = FilePathResolver.buildAllFilesAccessIntent(context)
            runCatching { context.startActivity(intent) }
        } else {
            legacyStorageLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }
    }

    fun openEpubExternally(absolutePath: String?, uriString: String?) {
        val intent = FilePathResolver.buildOpenEpubIntent(context, absolutePath, uriString)
        if (intent != null) {
            runCatching { context.startActivity(intent) }.onFailure {
                Toast.makeText(
                    context,
                    "Không tìm thấy ứng dụng đọc EPUB trên thiết bị.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    val currentRoute = currentBackStackEntry?.destination?.route

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(
            navController = navController,
            startDestination = ROUTE_HOME
        ) {
        composable(ROUTE_HOME) {
            MainScreen(
                recentFiles = recentFiles,
                libraryBookCount = libraryBooks.size,
                isOpeningEditor = isResumingDraft,
                hasAllFilesAccess = hasAllFilesAccess,
                cleaningOptions = cleaningOptions,
                isLoadingMetadata = isLoadingMetadata,
                errorBanner = homeError,
                onOpenLibraryClick = {
                    libraryViewModel.refreshLibrary()
                    navController.navigate(ROUTE_BOOK_LIBRARY) {
                        launchSingleTop = true
                    }
                },
                onSelectFileClick = {
                    documentPickerLauncher.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                onRequestStoragePermission = ::requestStorageAccess,
                onOpenSettingsClick = { showSettingsDialog = true },
                onUpdateCleaningOptions = { viewModel.updateCleaningOptions(it) },
                onContinueEditHistoryFile = { entry ->
                    viewModel.continueEditingHistoryItem(entry) {
                        navController.navigate(ROUTE_MANUAL_EDITOR) {
                            launchSingleTop = true
                        }
                    }
                },
                onOpenHistoryFile = { entry ->
                    openEpubExternally(entry.filePath, entry.uriString)
                },
                onReprocessHistoryFile = { entry ->
                    viewModel.reprocessHistoryItem(entry)
                },
                onLearnErrorsFromHistory = { entry ->
                    viewModel.learnErrorsFromHistory(entry)
                },
                onDeleteHistoryFile = { id -> viewModel.deleteHistoryItem(id) },
                onClearAllHistory = { viewModel.clearAllHistory() },
                onDismissError = { viewModel.clearHomeError() }
            )
        }

        composable(ROUTE_BOOK_LIBRARY) {
            BookLibraryScreen(
                books = libraryBooks,
                collections = libraryCollections,
                isImporting = isImportingLibraryBook,
                importFlowState = libraryImportFlowState,
                cleaningProgressPercent = (snapshot.progressFraction * 100f).toInt().coerceIn(0, 100),
                cleaningStatusMessage = snapshot.statusDetail,
                onBackClick = { navController.popBackStack() },
                onImportBookClick = {
                    libraryImportLauncher.launch(
                        arrayOf(
                            "application/epub+zip",
                            "application/pdf",
                            "application/octet-stream",
                            "*/*"
                        )
                    )
                },
                onConfirmCleanBeforeImport = {
                    val source = libraryViewModel.startCleaningFlowForPickedFile()
                    if (source != null) {
                        viewModel.startAutoCleanForLibraryImport(source)
                    }
                },
                onConfirmDirectImportNoClean = {
                    libraryViewModel.confirmDirectImportWithoutCleaning()
                },
                onCancelAutoClean = {
                    viewModel.cancelProcessing()
                    libraryViewModel.dismissImportFlow()
                },
                onContinueEditCleanedBook = {
                    libraryViewModel.dismissImportFlow()
                    navController.navigate(ROUTE_REVIEW) {
                        launchSingleTop = true
                    }
                },
                onAcceptCleanedBookOk = {
                    libraryViewModel.dismissImportFlow()
                    viewModel.resetProcessingSession()
                },
                onDismissImportFlow = {
                    libraryViewModel.dismissImportFlow()
                },
                onOpenBook = { book ->
                    libraryViewModel.openBookForReading(book) {
                        navController.navigate(ROUTE_BOOK_READER) {
                            launchSingleTop = true
                        }
                    }
                },
                onDeleteBook = { bookId -> libraryViewModel.deleteBook(bookId) },
                onCreateCollection = { name -> libraryViewModel.createCollection(name) },
                onSetCollectionBooks = { colId, bookIds ->
                    libraryViewModel.setCollectionBooks(colId, bookIds)
                },
                onAddBookToCollection = { colId, bookId ->
                    libraryViewModel.addBookToCollection(colId, bookId)
                },
                onDeleteCollection = { colId -> libraryViewModel.deleteCollection(colId) }
            )
        }

        composable(ROUTE_BOOK_READER) {
            BookReaderScreen(
                readerState = bookReaderState,
                readerPrefs = bookReaderPrefs,
                audioPlaybackState = audioPlaybackState,
                onBackClick = { chapIdx, paraIdx ->
                    libraryViewModel.saveReadingProgress(chapIdx, paraIdx)
                    navController.popBackStack()
                },
                onSelectChapter = { chapIdx, startPara, continueAudio ->
                    libraryViewModel.selectChapter(chapIdx, startPara, continueAudio)
                },
                onConsumeAutoContinueAudio = { libraryViewModel.consumeAutoContinueAudioFlag() },
                onSaveProgress = { chapIdx, paraIdx ->
                    libraryViewModel.saveReadingProgress(chapIdx, paraIdx)
                },
                onSetFontSize = { size -> libraryViewModel.setReaderFontSize(size) },
                onAdjustFontSize = { delta -> libraryViewModel.adjustReaderFontSize(delta) },
                onSelectPaperTheme = { theme -> libraryViewModel.selectReaderPaperTheme(theme) },
                onSelectScrollDirection = { dir -> libraryViewModel.selectScrollDirection(dir) },
                onAdjustPageMargin = { delta -> libraryViewModel.adjustPageMargin(delta) },
                onAdjustLineHeight = { delta -> libraryViewModel.adjustLineHeightMultiplier(delta) },
                onAdjustLetterSpacing = { delta -> libraryViewModel.adjustLetterSpacing(delta) },
                onCycleSpeechRate = { libraryViewModel.cycleSpeechRate() },
                onCycleVoiceProfile = { libraryViewModel.cycleVoiceProfile() },
                onSelectNarratorVoice = { profile -> libraryViewModel.selectNarratorVoice(profile) },
                onSelectSystemVoice = { voiceName -> libraryViewModel.selectSystemDeviceVoice(voiceName) },
                onToggleAdvancedCustom = { enabled -> libraryViewModel.setAdvancedVoiceCustomEnabled(enabled) },
                onAdvancedPitchChanged = { pitch -> libraryViewModel.setAdvancedCustomPitch(pitch) },
                onAdvancedRateChanged = { rate -> libraryViewModel.setAdvancedCustomSpeechRate(rate) },
                onSentencePauseChanged = { pauseMs -> libraryViewModel.setSentencePauseMs(pauseMs) },
                onResetAdvancedDefaults = { libraryViewModel.resetAdvancedVoiceSettings() },
                onSaveVoicePitch = { profile, pitch -> libraryViewModel.saveCustomVoicePitch(profile, pitch) },
                onResetVoicePitch = { profile -> libraryViewModel.resetCustomVoicePitch(profile) },
                onScanCharacters = { libraryViewModel.scanCharactersForCurrentBook() },
                onConfirmCharacterAssignments = { map ->
                    libraryViewModel.confirmCharacterVoiceAssignments(map)
                },
                onHideCharacterPicker = { libraryViewModel.hideCharacterVoicePicker() },
                onSelectAmbientSound = { track -> libraryViewModel.selectAmbientSound(track) },
                onChangeAmbientVolume = { vol -> libraryViewModel.setAmbientVolume(vol) }
            )
        }

        composable(ROUTE_PROCESSING) {
            ProcessingScreen(
                snapshot = snapshot,
                onCancelClick = {
                    viewModel.cancelProcessing()
                },
                onRetryClick = {
                    viewModel.retryProcessing()
                },
                onBackHomeClick = {
                    viewModel.resetProcessingSession()
                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                }
            )
        }

        composable(ROUTE_REVIEW) {
            ReviewScreen(
                snapshot = snapshot,
                saveUiState = saveUiState,
                customSaveFolderLabel = customSaveFolderLabel,
                onToggleRegenerateToc = { enabled -> viewModel.setRegenerateToc(enabled) },
                onToggleDiff = { diffId -> viewModel.toggleDiffAccepted(diffId) },
                onSetAllDiffs = { accepted -> viewModel.setAllDiffsAccepted(accepted) },
                onEditMetadataClick = { showReviewMetadataDialog = true },
                onPickSaveFolderClick = { saveFolderPickerLauncher.launch(null) },
                onSaveWithMode = { mode -> viewModel.saveProcessedBook(mode) },
                onContinueEdit = { mode ->
                    viewModel.openManualEditor(mode)
                    navController.navigate(ROUTE_MANUAL_EDITOR) {
                        launchSingleTop = true
                    }
                },
                onOpenSavedFile = { absPath, uriStr -> openEpubExternally(absPath, uriStr) },
                onFinishToHome = {
                    viewModel.resetProcessingSession()
                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                },
                onDismissSaveError = { viewModel.dismissSaveState() }
            )
        }

        composable(ROUTE_MANUAL_EDITOR) {
            ManualEditorScreen(
                editorState = manualEditorState,
                snapshot = snapshot,
                saveUiState = saveUiState,
                customSaveFolderLabel = customSaveFolderLabel,
                autoSaveDraft = autoSaveDraft,
                customReplaceRules = customReplaceRules,
                onSelectChapter = { chapIdx, paraIdx ->
                    viewModel.selectManualChapter(chapIdx, paraIdx)
                },
                onActiveParagraphChanged = { paraIdx ->
                    viewModel.setActiveParagraphIndex(paraIdx)
                },
                onToggleEditMode = { viewModel.toggleManualEditMode() },
                onToggleCompareMode = { viewModel.toggleManualCompareMode() },
                onAdjustFontSize = { delta -> viewModel.adjustManualFontSize(delta) },
                onSelectReadingMode = { mode -> viewModel.setManualReadingMode(mode) },
                onUpdateParagraph = { chapIdx, paraIdx, text ->
                    viewModel.updateManualParagraph(chapIdx, paraIdx, text)
                },
                onUndoEdit = { viewModel.undoManualEdit() },
                onSetSkipUndoConfirm = { skip -> viewModel.setSkipUndoConfirm(skip) },
                onAddManualToc = { chapIdx, paraIdx, title ->
                    viewModel.addManualTocBookmark(chapIdx, paraIdx, title)
                },
                onAddNewChapter = { title -> viewModel.addNewChapterInManualEditor(title) },
                onRenameTocItem = { id, newTitle -> viewModel.renameTocItem(id, newTitle) },
                onDeleteTocItem = { id -> viewModel.deleteTocItem(id) },
                onSplitChapterAtActiveParagraph = { viewModel.splitCurrentChapterAtActiveParagraph() },
                onMergeChapterWithNext = { sourceIdx -> viewModel.mergeChapterWithNext(sourceIdx) },
                onSearchQueryChange = { q -> viewModel.updateManualSearchQuery(q) },
                onReplaceQueryChange = { r -> viewModel.updateManualReplaceQuery(r) },
                onNavigateSearchMatch = { step -> viewModel.navigateSearchMatch(step) },
                onReplaceCurrentMatch = { viewModel.replaceCurrentSearchMatch() },
                onReplaceAllMatches = { viewModel.replaceAllSearchMatches() },
                onSaveDraftClick = { viewModel.saveCurrentManualDraft(showFeedback = true) },
                onAutoSaveDraft = { viewModel.saveCurrentManualDraft(showFeedback = false) },
                onAddCustomReplaceRule = { find, repl -> viewModel.addCustomReplaceRule(find, repl) },
                onDeleteCustomReplaceRule = { id -> viewModel.deleteCustomReplaceRule(id) },
                onApplyAllCustomReplaceRules = { viewModel.applyAllCustomReplaceRulesToBook() },
                onClearFeedback = { viewModel.clearManualFeedbackMessage() },
                onPickSaveFolderClick = { saveFolderPickerLauncher.launch(null) },
                onSaveWithMode = { mode -> viewModel.saveFromManualEditor(mode) },
                onOpenSavedFile = { absPath, uriStr -> openEpubExternally(absPath, uriStr) },
                onBackToReview = { navController.popBackStack() },
                onSaveDraftAndExitToHome = {
                    viewModel.saveCurrentManualDraft(showFeedback = false) {
                        viewModel.resetProcessingSession()
                        navController.popBackStack(ROUTE_HOME, inclusive = false)
                    }
                },
                onFinishToHome = {
                    viewModel.resetProcessingSession()
                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                },
                onDismissSaveError = { viewModel.dismissSaveState() }
            )
        }
        }

        // Persistent floating Now Playing pill when background audio is active outside BookReaderScreen
        AnimatedVisibility(
            visible = audioPlaybackState.hasActiveSession && currentRoute != ROUTE_BOOK_READER,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .widthIn(max = 560.dp)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            FloatingBackgroundAudioBanner(
                playbackState = audioPlaybackState,
                onOpenBookClick = {
                    libraryViewModel.openCurrentlyPlayingBook {
                        navController.navigate(ROUTE_BOOK_READER) {
                            launchSingleTop = true
                        }
                    }
                },
                onTogglePlayPause = {
                    BookAudioPlaybackService.togglePlayPause(context)
                },
                onStopClick = {
                    BookAudioPlaybackService.stop(context)
                }
            )
        }
    }

    // Ultra-Compact Floating Settings Dialog (Light/Dark + Auto-Save + Error Memory + Embedded Custom Replace Table + App Info)
    SettingsDialog(
        visible = showSettingsDialog,
        isDarkMode = isDarkMode,
        autoSaveDraft = autoSaveDraft,
        customReplaceRules = customReplaceRules,
        errorMemoryAutoApply = errorMemoryAutoApply,
        errorMemoryRuleCount = errorMemoryRuleCount,
        onToggleDarkMode = { enabled -> viewModel.setDarkMode(enabled) },
        onToggleAutoSaveDraft = { enabled -> viewModel.setAutoSaveDraft(enabled) },
        onAddCustomReplaceRule = { find, repl -> viewModel.addCustomReplaceRule(find, repl) },
        onDeleteCustomReplaceRule = { id -> viewModel.deleteCustomReplaceRule(id) },
        onToggleErrorMemoryAutoApply = { enabled -> viewModel.setErrorMemoryAutoApply(enabled) },
        onExportErrorMemoryClick = {
            exportErrorLibLauncher.launch(ErrorMemoryManager.DEFAULT_EXPORT_FILENAME)
        },
        onImportErrorMemoryClick = {
            importErrorLibLauncher.launch(arrayOf("application/json", "text/plain", "*/*"))
        },
        onDismiss = { showSettingsDialog = false }
    )

    // Floating iOS Metadata & Cover Editor Dialog immediately on file import
    val currentPending = pendingImport
    if (currentPending != null) {
        EpubMetadataEditorDialog(
            metadata = currentPending.metadata,
            fileDisplayName = currentPending.source.displayName,
            confirmButtonLabel = "Bắt đầu xử lý",
            onPickCoverClick = {
                coverPickerForReview = false
                coverPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onConfirm = { edited ->
                val started = viewModel.confirmImportAndStartProcessing(edited)
                if (started) {
                    navController.navigate(ROUTE_PROCESSING) {
                        launchSingleTop = true
                    }
                }
            },
            onDismiss = { viewModel.dismissPendingImport() }
        )
    }

    // Optional Metadata & Cover Editor from Review screen before saving
    val reviewMeta = snapshot.metadata
    val reviewSource = snapshot.sourceFile
    if (showReviewMetadataDialog && reviewMeta != null && reviewSource != null) {
        EpubMetadataEditorDialog(
            metadata = reviewMeta,
            fileDisplayName = reviewSource.displayName,
            confirmButtonLabel = "Áp dụng thông tin",
            onPickCoverClick = {
                coverPickerForReview = true
                coverPickerLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            },
            onConfirm = { edited ->
                viewModel.updateReviewMetadata(edited)
                showReviewMetadataDialog = false
            },
            onDismiss = { showReviewMetadataDialog = false }
        )
    }
}
