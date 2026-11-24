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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.pipeline.FilePathResolver
import com.example.pipeline.ProcessingPhase
import com.example.ui.MainViewModel
import com.example.ui.components.EpubMetadataEditorDialog
import com.example.ui.components.SettingsDialog
import com.example.ui.screens.MainScreen
import com.example.ui.screens.ProcessingScreen
import com.example.ui.screens.ReviewScreen
import com.example.ui.theme.EpubCleanAITheme
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

const val ROUTE_HOME = "home"
const val ROUTE_PROCESSING = "processing"
const val ROUTE_REVIEW = "review"

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
            val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()

            LaunchedEffect(isDarkMode) {
                enableEdgeToEdge(
                    statusBarStyle = if (isDarkMode) {
                        SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                    } else {
                        SystemBarStyle.light(
                            android.graphics.Color.TRANSPARENT,
                            android.graphics.Color.TRANSPARENT
                        )
                    },
                    navigationBarStyle = if (isDarkMode) {
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
                EpubCleanNavHost(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun EpubCleanNavHost(
    viewModel: MainViewModel
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    val recentFiles by viewModel.recentFiles.collectAsStateWithLifecycle()
    val hasAllFilesAccess by viewModel.hasAllFilesAccess.collectAsStateWithLifecycle()
    val snapshot by viewModel.processingSnapshot.collectAsStateWithLifecycle()
    val saveUiState by viewModel.saveUiState.collectAsStateWithLifecycle()
    val homeError by viewModel.homeErrorBanner.collectAsStateWithLifecycle()
    val isDarkMode by viewModel.isDarkMode.collectAsStateWithLifecycle()
    val cleaningOptions by viewModel.cleaningOptions.collectAsStateWithLifecycle()
    val isLoadingMetadata by viewModel.isLoadingMetadata.collectAsStateWithLifecycle()
    val pendingImport by viewModel.pendingImport.collectAsStateWithLifecycle()
    val customSaveFolderLabel by viewModel.customSaveFolderLabel.collectAsStateWithLifecycle()

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showReviewMetadataDialog by remember { mutableStateOf(false) }
    var coverPickerForReview by remember { mutableStateOf(false) }

    // Re-check MANAGE_EXTERNAL_STORAGE when returning from Android Settings
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshStoragePermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Request POST_NOTIFICATIONS on Android 13+ for Foreground Service progress visibility
    val notifPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
        onResult = { /* Service continues safely regardless of notification permission */ }
    )

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
    LaunchedEffect(snapshot.phase) {
        val currentRoute = navController.currentDestination?.route
        if (snapshot.phase == ProcessingPhase.COMPLETED && currentRoute == ROUTE_PROCESSING) {
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

    NavHost(
        navController = navController,
        startDestination = ROUTE_HOME
    ) {
        composable(ROUTE_HOME) {
            MainScreen(
                recentFiles = recentFiles,
                hasAllFilesAccess = hasAllFilesAccess,
                cleaningOptions = cleaningOptions,
                isLoadingMetadata = isLoadingMetadata,
                errorBanner = homeError,
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
                onOpenHistoryFile = { entry ->
                    openEpubExternally(entry.filePath, entry.uriString)
                },
                onReprocessHistoryFile = { entry ->
                    viewModel.reprocessHistoryItem(entry)
                },
                onDeleteHistoryFile = { id -> viewModel.deleteHistoryItem(id) },
                onClearAllHistory = { viewModel.clearAllHistory() },
                onDismissError = { viewModel.clearHomeError() }
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
                onOpenSavedFile = { absPath, uriStr -> openEpubExternally(absPath, uriStr) },
                onFinishToHome = {
                    viewModel.resetProcessingSession()
                    navController.popBackStack(ROUTE_HOME, inclusive = false)
                },
                onDismissSaveError = { viewModel.dismissSaveState() }
            )
        }
    }

    // Floating iOS Settings Dialog (Light/Dark Mode + App Info)
    SettingsDialog(
        visible = showSettingsDialog,
        isDarkMode = isDarkMode,
        onToggleDarkMode = { enabled -> viewModel.setDarkMode(enabled) },
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
