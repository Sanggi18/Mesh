package com.example.ui.navigation

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.example.ui.navigation.components.AddToPlaylistOverlay
import com.example.ui.navigation.components.DrawerHost
import com.example.ui.navigation.components.SettingsOverlay
import com.example.ui.navigation.components.TabItem
import com.example.ui.common.theme.ExpressiveMotion
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.SRMusicApp
import com.example.core.data.repository.DeleteSongResult
import com.example.ui.common.LocalMiniPlayerVisible
import com.example.ui.common.LocalSongDisplayOptions
import com.example.ui.sidebar.core.CollectionAction
import com.example.ui.sidebar.core.CollectionUiState
import com.example.ui.equalizer.core.EqualizerAction
import com.example.ui.equalizer.EqualizerScreen
import com.example.ui.equalizer.core.EqualizerUiState
import com.example.ui.home.core.HomeAction
import com.example.ui.home.core.HomeChildScreen
import com.example.ui.home.core.HomeUiState
import com.example.ui.library.core.LibraryAction
import com.example.ui.library.core.LibraryUiState
import com.example.ui.library.core.SongAction
import com.example.ui.player.core.PlayerAction
import com.example.ui.player.PlayerHost
import com.example.ui.player.core.PlayerUiState
import com.example.ui.settings.core.SettingsAction
import com.example.ui.settings.core.SettingsUiState
import com.example.ui.settings.core.toTabItems
import com.example.ui.settings.SettingsDestination
import com.example.core.model.NowPlayingArtworkStyle
import com.example.core.model.NowPlayingTheme
import com.example.core.model.Song
import com.example.ui.common.components.SongInfoBottomSheet
import com.example.ui.common.components.invalidateArtworkCache
import kotlinx.coroutines.launch

private tailrec fun Context.findHostActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findHostActivity()
    else -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppNavigation(
    settingsUiState: SettingsUiState,
    onSettingsAction: (SettingsAction) -> Unit,
    equalizerUiState: EqualizerUiState,
    onEqualizerAction: (EqualizerAction) -> Unit,
    homeUiState: HomeUiState,
    onHomeAction: (HomeAction) -> Unit,
    libraryUiState: LibraryUiState,
    onLibraryAction: (LibraryAction) -> Unit,
    collectionUiState: CollectionUiState,
    onCollectionAction: (CollectionAction) -> Unit,
    playerUiState: PlayerUiState,
    onPlayerAction: (PlayerAction) -> Unit,
    playbackProgressFlow: kotlinx.coroutines.flow.StateFlow<com.example.core.model.PlaybackProgress>? = null,
    onRequestPermission: () -> Unit,
    onRefreshMedia: () -> Unit,
    navigationState: NavigationState = rememberNavigationState(),
    modifier: Modifier = Modifier
) {
    val coroutineScope = rememberCoroutineScope()

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    var settingsInitialDestination: SettingsDestination by remember { mutableStateOf(SettingsDestination.Main) }

    val closeEqualizerAction = {
        navigationState.onAction(NavigationAction.SetEqualizerOpen(false))
    }

    val enabledTabs = remember(settingsUiState.enabledTabIds) {
        settingsUiState.enabledTabIds.toTabItems()
    }

    val totalSizeBytes = remember(homeUiState.songs) {
        homeUiState.songs.sumOf { it.size ?: 0L }
    }

    val effectivePlayerUiState = remember(playerUiState, settingsUiState.isArtworkAccent) {
        playerUiState.copy(isArtworkAccent = settingsUiState.isArtworkAccent)
    }

    val firstTab = enabledTabs.firstOrNull() ?: TabItem.HOME

    val artistListState = rememberLazyListState()

    val artistGridState = rememberLazyGridState()

    val albumListState = rememberLazyListState()

    val albumGridState = rememberLazyGridState()

    val pagerState = rememberPagerState(initialPage = 0) { enabledTabs.size }

    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as? SRMusicApp }

    var songInfoTarget by remember { mutableStateOf<Song?>(null) }
    var songDeleteTarget by remember { mutableStateOf<Song?>(null) }
    var pendingPermissionDeleteSong by remember { mutableStateOf<Song?>(null) }
    var isDeletingSong by remember { mutableStateOf(false) }
    var showDonateDialog by remember { mutableStateOf(false) }

    val deletePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val pendingSong = pendingPermissionDeleteSong
        pendingPermissionDeleteSong = null
        if (result.resultCode == Activity.RESULT_OK && pendingSong != null) {
            coroutineScope.launch {
                val repo = app?.songRepository
                val ok = if (repo == null) {
                    false
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    repo.finalizeSongDeletion(pendingSong)
                    true
                } else {
                    repo.deleteSong(pendingSong) is DeleteSongResult.Success
                }
                isDeletingSong = false
                if (ok) {
                    invalidateArtworkCache(
                        dataPath = pendingSong.dataPath,
                        contentUri = pendingSong.contentUri,
                        artworkUri = pendingSong.artworkUri
                    )
                    onPlayerAction(PlayerAction.RemoveSongFromQueue(pendingSong.id))
                    Toast.makeText(context, "Deleted \"${pendingSong.title}\"", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "Failed to delete \"${pendingSong.title}\"", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            isDeletingSong = false
        }
    }

    fun executeDeleteSong(song: Song) {
        if (isDeletingSong) return
        val repo = app?.songRepository ?: return
        isDeletingSong = true
        coroutineScope.launch {
            when (val res = repo.deleteSong(song)) {
                is DeleteSongResult.Success -> {
                    isDeletingSong = false
                    invalidateArtworkCache(
                        dataPath = song.dataPath,
                        contentUri = song.contentUri,
                        artworkUri = song.artworkUri
                    )
                    onPlayerAction(PlayerAction.RemoveSongFromQueue(song.id))
                    Toast.makeText(context, "Deleted \"${song.title}\"", Toast.LENGTH_SHORT).show()
                }

                is DeleteSongResult.NeedsPermission -> {
                    pendingPermissionDeleteSong = song
                    runCatching {
                        deletePermissionLauncher.launch(
                            IntentSenderRequest.Builder(res.intentSender).build()
                        )
                    }.onFailure {
                        pendingPermissionDeleteSong = null
                        isDeletingSong = false
                        Toast.makeText(context, "Failed to request delete permission", Toast.LENGTH_SHORT).show()
                    }
                }

                is DeleteSongResult.Failed -> {
                    isDeletingSong = false
                    Toast.makeText(context, "Failed to delete \"${song.title}\"", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    LaunchedEffect(pagerState, enabledTabs) {
        snapshotFlow { pagerState.settledPage }
            .collect { settledPage ->
                val targetTab = enabledTabs.getOrNull(settledPage)

                if (targetTab != null && targetTab != navigationState.selectedTab) {
                    navigationState.onAction(NavigationAction.SelectTab(targetTab))

                    onLibraryAction(LibraryAction.ClearDetail)

                    navigationState.onAction(NavigationAction.ClearDetailSourceTab)
                }

                navigationState.isBackNavigating = false
            }
    }

    LaunchedEffect(enabledTabs) {
        navigationState.tabBackStack.retainAll(enabledTabs.toSet())

        val currentIndex = enabledTabs.indexOf(navigationState.selectedTab)

        if (currentIndex != -1) {
            if (pagerState.currentPage != currentIndex) {
                pagerState.scrollToPage(currentIndex)
            }
        } else if (enabledTabs.isNotEmpty()) {
            navigationState.onAction(NavigationAction.SelectTab(enabledTabs[0]))

            pagerState.scrollToPage(0)
        }
    }

    fun selectTab(tab: TabItem, animate: Boolean = true) {
        val targetIndex = enabledTabs.indexOf(tab)

        if (targetIndex != -1 && targetIndex != pagerState.currentPage) {
            if (libraryUiState.selectedArtistName != null || libraryUiState.selectedAlbumName != null) {
                onLibraryAction(LibraryAction.ClearDetail)
            }

            if (navigationState.detailSourceTab != null) {
                navigationState.onAction(NavigationAction.ClearDetailSourceTab)
            }

            navigationState.onAction(NavigationAction.SelectTab(tab))

            coroutineScope.launch {
                val pageDiff = kotlin.math.abs(targetIndex - pagerState.currentPage)
                if (animate && pageDiff <= 1) {
                    pagerState.animateScrollToPage(
                        page = targetIndex,
                        animationSpec = tween(
                            durationMillis = 240,
                            easing = androidx.compose.animation.core.FastOutSlowInEasing
                        )
                    )
                } else {
                    pagerState.scrollToPage(targetIndex)
                }
            }
        }
    }

    val onSongActionDispatcher: (SongAction, Song) -> Unit = { action, song ->
        when (action) {
            SongAction.PLAY -> {
                onPlayerAction(PlayerAction.PlaySong(song, listOf(song)))
            }

            SongAction.PLAY_NEXT -> {
                onPlayerAction(PlayerAction.PlayNext(song))
            }

            SongAction.ADD_TO_QUEUE -> {
                onPlayerAction(PlayerAction.AddToQueue(song))
            }

            SongAction.TOGGLE_FAVORITE,
            SongAction.REMOVE_FROM_FAVORITES -> {
                onCollectionAction(CollectionAction.ToggleFavorite(song.id))
            }

            SongAction.ADD_TO_PLAYLIST -> {
                navigationState.onAction(NavigationAction.ShowAddToPlaylist(song))
            }

            SongAction.GO_TO_ARTIST -> {
                song.artist.takeIf { it.isNotBlank() }?.let { artistName ->
                    navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.NONE))
                    navigationState.onAction(NavigationAction.SetHomeChildScreen(HomeChildScreen.NONE))
                    navigationState.onAction(NavigationAction.OpenArtist(artistName, navigationState.selectedTab))

                    onLibraryAction(LibraryAction.SelectAlbum(null))

                    onLibraryAction(LibraryAction.SelectArtist(artistName))

                    val targetIndex = enabledTabs.indexOf(TabItem.ARTIST)

                    if (targetIndex != -1) {
                        coroutineScope.launch { pagerState.scrollToPage(targetIndex) }
                    }
                }
            }

            SongAction.GO_TO_ALBUM -> {
                song.album.takeIf { it.isNotBlank() }?.let { albumName ->
                    navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.NONE))
                    navigationState.onAction(NavigationAction.SetHomeChildScreen(HomeChildScreen.NONE))
                    navigationState.onAction(NavigationAction.OpenAlbum(albumName, navigationState.selectedTab))

                    onLibraryAction(LibraryAction.SelectArtist(null))

                    onLibraryAction(LibraryAction.SelectAlbum(albumName))

                    val targetIndex = enabledTabs.indexOf(TabItem.ALBUM)

                    if (targetIndex != -1) {
                        coroutineScope.launch { pagerState.scrollToPage(targetIndex) }
                    }
                }
            }

            SongAction.GO_TO_FOLDER -> {
                val folderPath = com.example.core.filter.FolderTreeParser.getSongRelativeDirectory(song)

                navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.NONE))
                navigationState.onAction(NavigationAction.SetHomeChildScreen(HomeChildScreen.NONE))
                navigationState.onAction(NavigationAction.OpenFolder(folderPath))

                onLibraryAction(LibraryAction.ClearDetail)

                onLibraryAction(LibraryAction.SelectFolder(folderPath))

                val targetIndex = enabledTabs.indexOf(TabItem.FOLDER)

                if (targetIndex != -1) {
                    coroutineScope.launch { pagerState.scrollToPage(targetIndex) }
                }
            }

            SongAction.SONG_INFO -> {
                songInfoTarget = song
            }

            SongAction.DELETE -> {
                songDeleteTarget = song
            }
        }
    }

    val hasDetailOpen = libraryUiState.selectedArtistName != null || libraryUiState.selectedAlbumName != null

    val isHomeChildOpen = navigationState.activeHomeChildScreen != HomeChildScreen.NONE

    val isSidebarChildOpen = navigationState.activeSidebarChildScreen != com.example.ui.sidebar.core.SidebarChildScreen.NONE

    val isBottomNavVisible = !hasDetailOpen && !isHomeChildOpen && !isSidebarChildOpen

    val canGoBackTab = navigationState.tabBackStack.isNotEmpty() || (navigationState.selectedTab != TabItem.HOME && navigationState.selectedTab != firstTab)

    BackNavigationHandler(
        isDrawerOpen = false,
        onCloseDrawer = { coroutineScope.launch { drawerState.close() } },
        isSettingsOpen = navigationState.isSettingsOpen,
        onCloseSettings = { navigationState.onAction(NavigationAction.SetSettingsOpen(false)) },
        isEqualizerOpen = navigationState.isEqualizerOpen,
        onCloseEqualizer = closeEqualizerAction,
        isLyricsOpen = navigationState.isLyricsOpen,
        onCloseLyrics = { navigationState.onAction(NavigationAction.SetLyricsOpen(false)) },
        hasDetailOpen = hasDetailOpen,
        onBackFromDetail = {
            onLibraryAction(LibraryAction.ClearDetail)

            navigationState.detailSourceTab?.let { origin ->
                navigationState.onAction(NavigationAction.ClearDetailSourceTab)

                selectTab(origin, animate = false)
            }
        },
        activeHomeChildScreen = navigationState.activeHomeChildScreen,
        onCloseHomeChildScreen = { navigationState.onAction(NavigationAction.SetHomeChildScreen(HomeChildScreen.NONE)) },
        activeSidebarChildScreen = navigationState.activeSidebarChildScreen,
        onCloseSidebarChildScreen = { navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.NONE)) },
        canGoBackTab = canGoBackTab,
        onBackTab = {
            val targetTab = navigationState.popTabBackStack(enabledTabs, firstTab)

            if (targetTab != null) {
                onLibraryAction(LibraryAction.ClearDetail)

                navigationState.onAction(NavigationAction.ClearDetailSourceTab)

                val targetIndex = enabledTabs.indexOf(targetTab)

                if (targetIndex != -1) {
                    val pageDiff = kotlin.math.abs(targetIndex - pagerState.currentPage)

                    coroutineScope.launch {
                        if (pageDiff <= 1) {
                            pagerState.animateScrollToPage(targetIndex)
                        } else {
                            pagerState.scrollToPage(targetIndex)
                        }
                    }
                }
            }
        }
    )

    val playerExpansionProgressState = remember { mutableFloatStateOf(if (playerUiState.isExpanded) 1f else 0f) }

    val isMiniPlayerVisible = playerUiState.currentSong != null &&
        !playerUiState.isDismissed

    var isDetailHeroOverStatusBar by remember { mutableStateOf(true) }
    var isHomeChildHeroOverStatusBar by remember { mutableStateOf(false) }
    var isSidebarHeroOverStatusBar by remember { mutableStateOf(false) }

    val isAppLight = MaterialTheme.colorScheme.background.luminance() > 0.5f &&
        MaterialTheme.colorScheme.surface.luminance() > 0.5f

    val isBlurLyrics = playerUiState.nowPlayingTheme == NowPlayingTheme.BLUR ||
        playerUiState.nowPlayingTheme == NowPlayingTheme.BLUR_2 ||
        playerUiState.nowPlayingTheme == NowPlayingTheme.MESH_GRADIENT

    val isNowPlayingExpanded = isMiniPlayerVisible && playerExpansionProgressState.floatValue > 0.5f

    val targetLightStatusBars = when {
        navigationState.isLyricsOpen -> if (isBlurLyrics) false else isAppLight
        navigationState.isEqualizerOpen -> isAppLight
        navigationState.isSettingsOpen -> isAppLight
        isNowPlayingExpanded -> if (effectivePlayerUiState.artworkStyle == NowPlayingArtworkStyle.FULL) false else isAppLight
        isSidebarChildOpen -> when (navigationState.activeSidebarChildScreen) {
            com.example.ui.sidebar.core.SidebarChildScreen.PLAYLIST -> if (isSidebarHeroOverStatusBar) false else isAppLight
            else -> isAppLight
        }
        hasDetailOpen -> if (isDetailHeroOverStatusBar) false else isAppLight
        isHomeChildOpen -> when (navigationState.activeHomeChildScreen) {
            HomeChildScreen.FOR_YOU_REMIX,
            HomeChildScreen.GENRE_TODAY,
            HomeChildScreen.NEW_RELEASE,
            HomeChildScreen.ALL_GENRE,
            HomeChildScreen.ALL_RELEASE -> if (isHomeChildHeroOverStatusBar) false else isAppLight
            HomeChildScreen.NONE -> isAppLight
        }
        else -> isAppLight
    }

    val view = LocalView.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val latestLightStatusBars by rememberUpdatedState(targetLightStatusBars)
    val latestLightNavBars by rememberUpdatedState(isAppLight)

    LaunchedEffect(targetLightStatusBars, isAppLight) {
        if (!view.isInEditMode) {
            val window = view.context.findHostActivity()?.window
            if (window != null) {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = targetLightStatusBars
                controller.isAppearanceLightNavigationBars = isAppLight
            }
        }
    }

    DisposableEffect(lifecycleOwner, view) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && !view.isInEditMode) {
                val window = view.context.findHostActivity()?.window
                if (window != null) {
                    window.statusBarColor = android.graphics.Color.TRANSPARENT
                    window.navigationBarColor = android.graphics.Color.TRANSPARENT
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        window.isStatusBarContrastEnforced = false
                        window.isNavigationBarContrastEnforced = false
                    }
                    val controller = WindowCompat.getInsetsController(window, window.decorView)
                    controller.isAppearanceLightStatusBars = latestLightStatusBars
                    controller.isAppearanceLightNavigationBars = latestLightNavBars
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val drawerGesturesEnabled = !hasDetailOpen && !isHomeChildOpen && !isSidebarChildOpen && !navigationState.isLyricsOpen && !navigationState.isEqualizerOpen && !navigationState.isSettingsOpen && !playerUiState.isExpanded

    CompositionLocalProvider(
        LocalMiniPlayerVisible provides isMiniPlayerVisible,
        LocalSongDisplayOptions provides libraryUiState.songDisplayOptions
    ) {
        Box(
            modifier = Modifier.fillMaxSize()
        ) {
            DrawerHost(
                drawerState = drawerState,
                gesturesEnabled = drawerGesturesEnabled,
                onScanMedia = {
                    coroutineScope.launch { drawerState.close() }

                    onRefreshMedia()
                },
                onOpenSettings = {
                    coroutineScope.launch { drawerState.close() }

                    settingsInitialDestination = SettingsDestination.Main

                    navigationState.onAction(NavigationAction.SetSettingsOpen(true))
                },
                totalSongs = homeUiState.songs.size,
                totalSizeBytes = totalSizeBytes,
                settingsUiState = settingsUiState,
                onOpenPlaylists = {
                    coroutineScope.launch { drawerState.close() }

                    navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.PLAYLIST))
                },
                onOpenFavorites = {
                    coroutineScope.launch { drawerState.close() }

                    navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.FAVORITES))
                },
                onOpenHistory = {
                    coroutineScope.launch { drawerState.close() }

                    navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.HISTORY))
                },
                onOpenListeningStats = {
                    coroutineScope.launch { drawerState.close() }

                    navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.LISTENING_STATS))
                },
                onDonateClick = {
                    coroutineScope.launch { drawerState.close() }
                    showDonateDialog = true
                }
            ) {
                Scaffold(
                    modifier = modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
                    bottomBar = {
                        if (isBottomNavVisible) {
                            MainBottomNavigationBar(
                                enabledTabs = enabledTabs,
                                selectedTab = navigationState.selectedTab,
                                pagerState = pagerState,
                                expansionProgressProvider = { playerExpansionProgressState.floatValue },
                                isEqualizerOpen = navigationState.isEqualizerOpen,
                                showLabels = settingsUiState.showBottomBarLabels,
                                showIconBackground = settingsUiState.showBottomTabIconBackground,
                                cornerRadiusDp = settingsUiState.bottomBarCornerRadiusDp,
                                borderEnabled = settingsUiState.bottomBarBorderEnabled,
                                borderWidthDp = settingsUiState.bottomBarBorderWidthDp,
                                widthPercent = settingsUiState.bottomBarWidthPercent,
                                onTabSelected = { selectTab(it) }
                            )
                        }
                    }
                ) { innerPadding ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainTabContent(
                            enabledTabs = enabledTabs,
                            selectedTab = navigationState.selectedTab,
                            pagerState = pagerState,
                            hasDetailOpen = hasDetailOpen,
                            isEqualizerOpen = navigationState.isEqualizerOpen,
                            innerPadding = innerPadding,
                            homeUiState = homeUiState,
                            onHomeAction = onHomeAction,
                            libraryUiState = libraryUiState,
                            onLibraryAction = onLibraryAction,
                            collectionUiState = collectionUiState,
                            onCollectionAction = onCollectionAction,
                            playerUiState = playerUiState,
                            onSongAction = onSongActionDispatcher,
                            onSongClick = { song, queue ->
                                onPlayerAction(PlayerAction.PlaySong(song, queue ?: emptyList()))
                            },
                            onArtistSelect = { artistName ->
                                navigationState.onAction(NavigationAction.OpenArtist(artistName, navigationState.selectedTab))

                                onLibraryAction(LibraryAction.SelectAlbum(null))

                                onLibraryAction(LibraryAction.SelectArtist(artistName))

                                val targetIndex = enabledTabs.indexOf(TabItem.ARTIST)

                                if (targetIndex != -1) {
                                    coroutineScope.launch { pagerState.scrollToPage(targetIndex) }
                                }
                            },
                            onAlbumSelect = { albumName ->
                                navigationState.onAction(NavigationAction.OpenAlbum(albumName, navigationState.selectedTab))

                                onLibraryAction(LibraryAction.SelectArtist(null))

                                onLibraryAction(LibraryAction.SelectAlbum(albumName))

                                val targetIndex = enabledTabs.indexOf(TabItem.ALBUM)

                                if (targetIndex != -1) {
                                    coroutineScope.launch { pagerState.scrollToPage(targetIndex) }
                                }
                            },
                            onMenuClick = { coroutineScope.launch { drawerState.open() } },
                            onRequestPermission = onRequestPermission,
                            onRefresh = onRefreshMedia,
                            onBackFromDetail = {
                                onLibraryAction(LibraryAction.ClearDetail)

                                navigationState.detailSourceTab?.let { origin ->
                                    navigationState.onAction(NavigationAction.ClearDetailSourceTab)

                                    selectTab(origin, animate = false)
                                }
                            },
                            artistListState = artistListState,
                            artistGridState = artistGridState,
                            albumListState = albumListState,
                            albumGridState = albumGridState,
                            activeHomeChildScreen = navigationState.activeHomeChildScreen,
                            onHomeChildScreenChange = { navigationState.onAction(NavigationAction.SetHomeChildScreen(it)) },
                            onOpenDacSettings = {
                                settingsInitialDestination = SettingsDestination.DacConfig
                                navigationState.onAction(NavigationAction.SetSettingsOpen(true))
                            },
                            onDetailHeroOverStatusBarChanged = { isDetailHeroOverStatusBar = it },
                            onHomeChildHeroOverStatusBarChanged = { isHomeChildHeroOverStatusBar = it }
                        )

                        var displayedSidebarChildScreen by remember {
                            mutableStateOf(navigationState.activeSidebarChildScreen)
                        }
                        if (navigationState.activeSidebarChildScreen != com.example.ui.sidebar.core.SidebarChildScreen.NONE) {
                            displayedSidebarChildScreen = navigationState.activeSidebarChildScreen
                        }

                        LaunchedEffect(navigationState.activeSidebarChildScreen) {
                            if (navigationState.activeSidebarChildScreen != com.example.ui.sidebar.core.SidebarChildScreen.PLAYLIST) {
                                isSidebarHeroOverStatusBar = false
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = isSidebarChildOpen,
                            enter = ExpressiveMotion.ScreenEnterTransition,
                            exit = ExpressiveMotion.ScreenExitTransition
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(MaterialTheme.colorScheme.background)
                            ) {
                                when (displayedSidebarChildScreen) {
                                    com.example.ui.sidebar.core.SidebarChildScreen.PLAYLIST -> {
                                        com.example.ui.sidebar.SidebarPlaylistChildScreen(
                                            uiState = collectionUiState,
                                            allSongs = homeUiState.songs,
                                            currentPlayingSongId = playerUiState.currentSong?.id,
                                            isPlaying = playerUiState.playbackInfo.isPlaying,
                                            onBackClick = { navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.NONE)) },
                                            onAction = onCollectionAction,
                                            onSongClick = { song, queue -> onPlayerAction(PlayerAction.PlaySong(song, queue)) },
                                            onSongAction = onSongActionDispatcher,
                                            onHeroOverStatusBarChanged = { isSidebarHeroOverStatusBar = it },
                                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                                        )
                                    }

                                    com.example.ui.sidebar.core.SidebarChildScreen.FAVORITES -> {
                                        com.example.ui.sidebar.SidebarFavoritesChildScreen(
                                            favoriteSongs = collectionUiState.favoriteSongs,
                                            currentPlayingSongId = playerUiState.currentSong?.id,
                                            isPlaying = playerUiState.playbackInfo.isPlaying,
                                            onBackClick = { navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.NONE)) },
                                            onSongClick = { song, queue -> onPlayerAction(PlayerAction.PlaySong(song, queue)) },
                                            onSongAction = onSongActionDispatcher,
                                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                                        )
                                    }

                                    com.example.ui.sidebar.core.SidebarChildScreen.HISTORY -> {
                                        com.example.ui.sidebar.SidebarHistoryChildScreen(
                                            historySongs = collectionUiState.historySongs,
                                            currentPlayingSongId = playerUiState.currentSong?.id,
                                            isPlaying = playerUiState.playbackInfo.isPlaying,
                                            onBackClick = { navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.NONE)) },
                                            onClearHistory = { onCollectionAction(CollectionAction.ClearHistory) },
                                            onSongClick = { song, queue -> onPlayerAction(PlayerAction.PlaySong(song, queue)) },
                                            onSongAction = onSongActionDispatcher,
                                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                                        )
                                    }

                                    com.example.ui.sidebar.core.SidebarChildScreen.LISTENING_STATS -> {
                                        com.example.ui.sidebar.SidebarListeningStatsChildScreen(
                                            statsSummary = collectionUiState.listeningStats,
                                            selectedPeriod = collectionUiState.selectedStatsPeriod,
                                            currentPlayingSongId = playerUiState.currentSong?.id,
                                            onSelectPeriod = { period -> onCollectionAction(CollectionAction.SelectStatsPeriod(period)) },
                                            onBackClick = { navigationState.onAction(NavigationAction.SetSidebarChildScreen(com.example.ui.sidebar.core.SidebarChildScreen.NONE)) },
                                            onSongClick = { song, queue -> onPlayerAction(PlayerAction.PlaySong(song, queue)) },
                                            modifier = Modifier.fillMaxSize().padding(innerPadding)
                                        )
                                    }

                                    com.example.ui.sidebar.core.SidebarChildScreen.NONE -> Unit
                                }
                            }
                        }
                    }
                }

                PlayerHost(
                    uiState = effectivePlayerUiState,
                    onAction = onPlayerAction,
                    playbackProgressFlow = playbackProgressFlow,
                    isEqualizerOpen = navigationState.isEqualizerOpen,
                    isLyricsOpen = navigationState.isLyricsOpen,
                    isSettingsOpen = navigationState.isSettingsOpen,
                    isBottomBarVisible = isBottomNavVisible,
                    onNavigateToEqualizer = {
                        navigationState.onAction(NavigationAction.SetEqualizerOpen(true))
                    },
                    onNavigateToLyrics = {
                        navigationState.onAction(NavigationAction.SetLyricsOpen(true))
                    },
                    onExpansionProgressChanged = { progress ->
                        playerExpansionProgressState.floatValue = progress
                    },
                    miniPlayerCornerRadiusDp = settingsUiState.miniPlayerCornerRadiusDp,
                    miniPlayerBorderEnabled = settingsUiState.miniPlayerBorderEnabled,
                    miniPlayerBorderWidthDp = settingsUiState.miniPlayerBorderWidthDp,
                    miniPlayerArtworkStyle = settingsUiState.miniPlayerArtworkStyle,
                    miniPlayerCustomArtworkShape = settingsUiState.miniPlayerCustomArtworkShape
                )
            }

            SettingsOverlay(
                isOpen = navigationState.isSettingsOpen,
                onClose = {
                    navigationState.onAction(NavigationAction.SetSettingsOpen(false))

                    settingsInitialDestination = SettingsDestination.Main
                },
                uiState = settingsUiState,
                onAction = onSettingsAction,
                onNavigateToEqualizer = {
                    navigationState.onAction(NavigationAction.SetEqualizerOpen(true))
                },
                onRefreshLibrary = onRefreshMedia,
                initialDestination = settingsInitialDestination
            )

            androidx.compose.animation.AnimatedVisibility(
                visible = navigationState.isEqualizerOpen,
                enter = ExpressiveMotion.ScreenEnterTransition,
                exit = ExpressiveMotion.ScreenExitTransition
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    EqualizerScreen(
                        uiState = equalizerUiState,
                        onAction = onEqualizerAction,
                        onBackClick = closeEqualizerAction,
                        onMenuClick = { coroutineScope.launch { drawerState.open() } },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            androidx.compose.animation.AnimatedVisibility(
                visible = navigationState.isLyricsOpen,
                enter = ExpressiveMotion.ScreenEnterTransition,
                exit = ExpressiveMotion.ScreenExitTransition
            ) {
                val isBlurLyrics = playerUiState.nowPlayingTheme == com.example.core.model.NowPlayingTheme.BLUR ||
                    playerUiState.nowPlayingTheme == com.example.core.model.NowPlayingTheme.BLUR_2 ||
                    playerUiState.nowPlayingTheme == com.example.core.model.NowPlayingTheme.MESH_GRADIENT

                com.example.ui.player.style.NowPlayingThemeContainer(
                    theme = playerUiState.nowPlayingTheme,
                    song = playerUiState.currentSong,
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (isBlurLyrics) {
                            val lyricsOverlayAlpha = if (playerUiState.nowPlayingTheme == com.example.core.model.NowPlayingTheme.MESH_GRADIENT) {
                                0.18f
                            } else {
                                0.48f
                            }
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = lyricsOverlayAlpha))
                            )
                        }

                        com.example.ui.lyrics.LyricsChildScreen(
                            song = playerUiState.currentSong,
                            playbackProgressFlow = playbackProgressFlow,
                            isPlaying = playerUiState.playbackInfo.isPlaying,
                            forceLightContent = isBlurLyrics,
                            onBackClick = { navigationState.onAction(NavigationAction.SetLyricsOpen(false)) },
                            onSeekTo = { positionMs ->
                                onPlayerAction(PlayerAction.SeekTo(positionMs))
                            },
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }

            AddToPlaylistOverlay(
                song = navigationState.songForAddToPlaylist,
                playlists = collectionUiState.playlists,
                onDismiss = { navigationState.onAction(NavigationAction.DismissAddToPlaylist) },
                onAddSongToPlaylist = { playlistId, songId ->
                    onCollectionAction(CollectionAction.AddSongToPlaylist(playlistId, songId))
                },
                onCreatePlaylist = { name, callback ->
                    onCollectionAction(CollectionAction.CreatePlaylist(name, callback))
                }
            )

            if (songInfoTarget != null) {
                SongInfoBottomSheet(
                    song = songInfoTarget!!,
                    onDismissRequest = { songInfoTarget = null }
                )
            }

            songDeleteTarget?.let { targetSong ->
                AlertDialog(
                    onDismissRequest = {
                        if (!isDeletingSong) songDeleteTarget = null
                    },
                    title = {
                        Text(
                            text = "Delete Song",
                            style = MaterialTheme.typography.titleLarge
                        )
                    },
                    text = {
                        Text(
                            text = "Permanently delete \"${targetSong.title}\" from your device?",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                songDeleteTarget = null
                                executeDeleteSong(targetSong)
                            },
                            enabled = !isDeletingSong,
                            colors = ButtonDefaults.textButtonColors(
                                contentColor = MaterialTheme.colorScheme.error
                            ),
                            modifier = Modifier.testTag("delete_song_confirm_button")
                        ) {
                            Text("Delete")
                        }
                    },
                    dismissButton = {
                        TextButton(
                            onClick = { songDeleteTarget = null },
                            enabled = !isDeletingSong,
                            modifier = Modifier.testTag("delete_song_cancel_button")
                        ) {
                            Text("Cancel")
                        }
                    }
                )
            }

            if (showDonateDialog) {
                com.example.ui.common.components.DonateDialog(
                    onDismiss = { showDonateDialog = false }
                )
            }
        }
    }
}
