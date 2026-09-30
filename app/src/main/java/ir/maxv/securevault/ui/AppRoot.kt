package ir.maxv.securevault.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import ir.maxv.securevault.core.VaultPaths
import ir.maxv.securevault.data.Stage

/** The whole app shell: which screen, the top bar, the snackbar and the breadcrumb of routes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(viewModel: VaultViewModel) {
    val state by viewModel.state.collectAsState()
    val route by viewModel.routes.collectAsState()
    val note by viewModel.note.collectAsState()
    val search by viewModel.search.collectAsState()
    val searching by viewModel.searching.collectAsState()
    val message by viewModel.message.collectAsState()

    var showSettings by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    SecureVaultTheme {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Scaffold(
                snackbarHost = { SnackbarHost(snackbar) },
                containerColor = MaterialTheme.colorScheme.background,
                topBar = {
                    if (state.stage == Stage.UNLOCKED) {
                        TopAppBar(
                            colors = TopAppBarDefaults.topAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface,
                            ),
                            title = {
                                Text(
                                    when (val current = route) {
                                        is Route.Folder -> if (current.path.isEmpty()) "گنجینه" else VaultPaths.nameOf(current.path)
                                        is Route.Note -> VaultPaths.titleOf(current.path)
                                        Route.Search -> "جست‌وجوی لفظی"
                                    },
                                    maxLines = 1,
                                )
                            },
                            navigationIcon = {
                                val canGoBack = !(route is Route.Folder && (route as Route.Folder).path.isEmpty())
                                if (canGoBack) {
                                    IconButton(onClick = { viewModel.back() }) {
                                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "بازگشت")
                                    }
                                }
                            },
                            actions = {
                                IconButton(onClick = { viewModel.openSearch() }) {
                                    Icon(Icons.Filled.Search, contentDescription = "جست‌وجو")
                                }
                                IconButton(onClick = { showSettings = true }) {
                                    Icon(Icons.Filled.Settings, contentDescription = "تنظیمات")
                                }
                            },
                        )
                    }
                },
            ) { padding ->
                Surface(
                    Modifier
                        .fillMaxSize()
                        .padding(padding),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    when (state.stage) {
                        Stage.SETUP -> SetupScreen(
                            initial = state.settings,
                            hasCredentials = state.hasCredentials,
                            busy = state.busy,
                            error = state.error,
                            onTest = { settings, access, secret ->
                                viewModel.saveSetup(settings, access.ifBlank { null }, secret.ifBlank { null })
                                viewModel.testConnection()
                            },
                            onSync = { settings, access, secret ->
                                viewModel.saveSetup(settings, access.ifBlank { null }, secret.ifBlank { null })
                                viewModel.sync()
                            },
                            onDismissError = viewModel::consumeMessage,
                        )

                        Stage.LOCKED -> UnlockScreen(
                            info = state.info,
                            busy = state.busy,
                            progress = state.progress,
                            error = state.error,
                            onUnlock = viewModel::unlock,
                            onSync = viewModel::sync,
                            onSettings = { showSettings = true },
                            onDismissError = viewModel::consumeMessage,
                        )

                        Stage.UNLOCKED -> Box(Modifier.fillMaxSize()) {
                            when (val current = route) {
                                is Route.Folder -> {
                                    val children = remember(
                                        current.path,
                                        state.info?.lastSyncAt,
                                        state.info?.downloadedNotes,
                                    ) { viewModel.repository.children(current.path) }
                                    FolderScreen(
                                        folder = current.path,
                                        children = children,
                                        folderNote = remember(current.path, state.info?.lastSyncAt) {
                                            viewModel.repository.folderNote(current.path)
                                        },
                                        notePreview = { row -> viewModel.repository.fileNote(row) },
                                        downloaded = { row -> viewModel.repository.isDownloaded(row) },
                                        onOpenFolder = viewModel::openFolder,
                                        onOpenNote = { row -> viewModel.openNote(row.path) },
                                        onCrumb = viewModel::openFolder,
                                        header = {},
                                        busy = state.busy,
                                    )
                                }

                                is Route.Note -> {
                                    val currentNote = note
                                    if (currentNote == null || currentNote.row.path != current.path) {
                                        Box(Modifier.fillMaxSize()) {
                                            androidx.compose.material3.CircularProgressIndicator(
                                                modifier = Modifier.padding(24.dp)
                                            )
                                        }
                                        LaunchedEffect(current.path) { viewModel.openNote(current.path, current.highlight) }
                                    } else {
                                        NoteScreen(
                                            view = currentNote,
                                            highlight = current.highlight,
                                            imageLoader = { url -> viewModel.imageBytes(url) },
                                            onNeedImage = viewModel::requestImage,
                                            onReveal = viewModel::revealSecret,
                                            onDownload = { viewModel.openNote(currentNote.row.path) },
                                        )
                                    }
                                }

                                Route.Search -> SearchScreen(
                                    results = search,
                                    searching = searching,
                                    progress = state.progress,
                                    missingNotes = viewModel.repository.notesMissingLocally(),
                                    onSearch = viewModel::runSearch,
                                    onOpen = { result ->
                                        viewModel.openNote(result.row.path, result.snippet.let { _ -> search?.query })
                                    },
                                    onDownloadAll = viewModel::downloadEverything,
                                )
                            }
                        }
                    }
                }
            }

            if (showSettings) {
                SettingsSheet(
                    settings = state.settings,
                    info = state.info,
                    autoLockMinutes = viewModel.repository.autoLockMinutes,
                    busy = state.busy,
                    onDismiss = { showSettings = false },
                    onSave = { settings, access, secret, autoLock ->
                        viewModel.saveSetup(settings, access, secret)
                        viewModel.repository.autoLockMinutes = autoLock
                    },
                    onSync = viewModel::sync,
                    onDownloadAll = viewModel::downloadEverything,
                    onLock = {
                        showSettings = false
                        viewModel.lock()
                    },
                    onWipe = {
                        showSettings = false
                        viewModel.wipe()
                    },
                )
            }

            BackHandler(enabled = state.stage == Stage.UNLOCKED && !viewModel.atRoot()) {
                viewModel.back()
            }
        }
    }
}
