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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.example.ai.configureAppCheck
import com.example.pipeline.FilePathResolver
import com.example.pipeline.ProcessingPhase
import com.example.ui.MainViewModel
import com.example.ui.screens.HomeScreen
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

        // STEP 1 (MANDATORY): Configure Firebase App Check with launch intent before any UI or Firebase calls
        configureAppCheck(this, intent)

        // Initialize PDFBox-Android resource loader
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
            EpubCleanAITheme {
                EpubCleanNavHost()
            }
        }
    }
}

@Composable
private fun EpubCleanNavHost(
    viewModel: MainViewModel = viewModel()
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    val lifecycleOwner = LocalLifecycleOwner.current

    val recentBooks by viewModel.recentBooks.collectAsStateWithLifecycle()
    val hasAllFilesAccess by viewModel.hasAllFilesAccess.collectAsStateWithLifecycle()
    val snapshot by viewModel.processingSnapshot.collectAsStateWithLifecycle()
    val saveUiState by viewModel.saveUiState.collectAsStateWithLifecycle()
    val homeError by viewModel.homeErrorBanner.collectAsStateWithLifecycle()

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

    // System file picker for .epub and .pdf
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
                val accepted = viewModel.onFilePicked(uri)
                if (accepted) {
                    navController.navigate(ROUTE_PROCESSING) {
                        launchSingleTop = true
                    }
                }
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
            HomeScreen(
                recentBooks = recentBooks,
                hasAllFilesAccess = hasAllFilesAccess,
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
                onOpenRecentBook = { book ->
                    openEpubExternally(book.outputFilePath, book.outputUriString)
                },
                onDeleteRecentBook = { id -> viewModel.deleteHistoryItem(id) },
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
                onToggleDiff = { diffId -> viewModel.toggleDiffAccepted(diffId) },
                onSetAllDiffs = { accepted -> viewModel.setAllDiffsAccepted(accepted) },
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
}
