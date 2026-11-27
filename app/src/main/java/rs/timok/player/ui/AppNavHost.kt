package rs.timok.player.ui

import android.net.Uri
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.viewinterop.AndroidView
import android.view.LayoutInflater
import androidx.compose.runtime.key
import rs.timok.player.R
import androidx.navigation.compose.currentBackStackEntryAsState
import rs.timok.player.ui.components.BrandBackdrop
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import rs.timok.player.player.PlaybackController
import rs.timok.player.player.AspectMode
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import rs.timok.player.ui.archive.ArchiveScreen
import rs.timok.player.ui.channels.ChannelsScreen
import rs.timok.player.ui.login.LoginScreen
import rs.timok.player.ui.player.PlayerScreen
import rs.timok.player.ui.search.SearchScreen
import rs.timok.player.ui.settings.SettingsScreen
import rs.timok.player.ui.components.NavDest
import rs.timok.player.ui.vod.VodBrowseScreen
import rs.timok.player.ui.vod.VodDetailsScreen
import rs.timok.player.ui.vod.VodPlayerScreen
import rs.timok.player.data.repo.VodPlayRequest
import rs.timok.player.domain.VodKind
import kotlinx.serialization.json.Json
import android.view.KeyEvent
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text

object Routes {
    const val LOGIN = "login"
    /** Sign-in of an additional account, opened from Settings; Back returns there. */
    const val LOGIN_ADD = "login_add"
    const val CHANNELS = "channels"
    const val SETTINGS = "settings"
    const val SEARCH = "search"
    const val ARCHIVE = "archive/{streamId}?name={name}&logo={logo}"
    const val PLAYER = "player/{group}/{streamId}"
    const val VOD = "vod/{kind}"
    const val VOD_DETAILS = "vodDetails/{kind}/{id}"
    const val VOD_PLAYER = "vodPlay/{req}/{start}"
    const val PROGRAM = "program/{group}/{streamId}"
    const val GUIDE = "guide/{group}?streamId={streamId}"

    /**
     * Menus where typing a channel number opens that channel. Not the players (they have their own entry or
     * none) and not screens with text fields (login, search, settings), where digits are typed text.
     */
    val NUMBER_ENTRY = setOf(CHANNELS, VOD, VOD_DETAILS, ARCHIVE, PROGRAM, GUIDE)

    /** Menus where the remote's GUIDE key opens the guide grid (the player handles it itself, the guide closes). */
    val GUIDE_KEY = setOf(CHANNELS, VOD, VOD_DETAILS, ARCHIVE, PROGRAM, SEARCH, SETTINGS)

    fun player(group: String, streamId: Long) = "player/${Uri.encode(group)}/$streamId"
    fun program(group: String, streamId: Long) = "program/${Uri.encode(group)}/$streamId"
    fun guide(group: String, streamId: Long?) = "guide/${Uri.encode(group)}?streamId=${streamId ?: -1}"
    fun archive(streamId: Long, name: String, logo: String?) =
        "archive/$streamId?name=${Uri.encode(name)}&logo=${Uri.encode(logo.orEmpty())}"
    fun vod(kind: VodKind) = "vod/${kind.name}"
    fun vodDetails(kind: VodKind, id: Long) = "vodDetails/${kind.name}/$id"
    fun vodPlayer(request: VodPlayRequest, startMs: Long) = "vodPlay/${Uri.encode(Json.encodeToString(request))}/$startMs"
}

@Composable
fun AppNavHost(mainViewModel: MainViewModel, playback: PlaybackController) {
    val start by mainViewModel.startRoute.collectAsStateWithLifecycle()
    val numberInput by mainViewModel.numberInput.collectAsStateWithLifecycle()
    var currentRoute by remember { mutableStateOf<String?>(null) }
    // OK that confirmed a number: its key-up must not also click the focused item.
    var swallowCenterUp by remember { mutableStateOf(false) }
    // Set once the nav controller exists; the key handler sits outside the NavHost.
    // A plain holder, not state: assigning it during composition must not trigger recomposition.
    val openGuide = remember { GuideOpener() }
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_GUIDE && currentRoute in Routes.GUIDE_KEY) {
                if (e.type == KeyEventType.KeyDown) openGuide.action?.invoke()
                return@onPreviewKeyEvent true
            }
            if (currentRoute !in Routes.NUMBER_ENTRY) return@onPreviewKeyEvent false
            val code = e.nativeKeyEvent.keyCode
            val down = e.type == KeyEventType.KeyDown
            when {
                code in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> {
                    if (down) mainViewModel.onDigit(code - KeyEvent.KEYCODE_0, e.nativeKeyEvent.eventTime)
                    true
                }
                code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER -> when {
                    down && mainViewModel.commitNumber() -> { swallowCenterUp = true; true }
                    !down && swallowCenterUp -> { swallowCenterUp = false; true }
                    else -> false
                }
                code == KeyEvent.KEYCODE_BACK && numberInput.isNotEmpty() -> {
                    if (down) mainViewModel.clearNumber()
                    true
                }
                else -> false
            }
        },
    ) {
        val startRoute = start ?: return@Box
        // Live video sits behind every screen; screens draw their own (translucent) backgrounds.
        VideoBackground(playback)
        val nav = rememberNavController()
        var showAccounts by remember { mutableStateOf(false) }
        // The startup jump into the last channel happens under the splash, so it must not animate.
        var instantPlayer by remember { mutableStateOf(false) }
        val startChannel by mainViewModel.startChannel.collectAsStateWithLifecycle()
        // Top-level screens (rail): TV stays at the bottom of the stack, the others replace each other above it.
        val onNavigate: (NavDest) -> Unit = { dest ->
            when (dest) {
                NavDest.TV -> nav.popBackStack(Routes.CHANNELS, inclusive = false)
                NavDest.GUIDE -> openGuide.action?.invoke()
                NavDest.MOVIES, NavDest.SERIES -> nav.navigate(Routes.vod(if (dest == NavDest.MOVIES) VodKind.MOVIE else VodKind.SERIES)) {
                    popUpTo(Routes.CHANNELS)
                    launchSingleTop = true
                }
                NavDest.SEARCH -> nav.navigate(Routes.SEARCH) {
                    popUpTo(Routes.CHANNELS)
                    launchSingleTop = true
                }
                NavDest.SETTINGS -> nav.navigate(Routes.SETTINGS) { launchSingleTop = true }
                NavDest.ACCOUNTS -> showAccounts = true
            }
        }
        // Another account became active: start over from its channel list (its last channel resumes there).
        LaunchedEffect(Unit) {
            mainViewModel.accountChanged.collect {
                showAccounts = false
                nav.navigate(Routes.CHANNELS) { popUpTo(0) { inclusive = true } }
            }
        }
        openGuide.action = {
            val (streamId, g) = playback.lastPlayed ?: (null to rs.timok.player.data.repo.GroupKey.Favorites)
            nav.navigate(Routes.guide(g.key, streamId)) {
                popUpTo(Routes.CHANNELS)
                launchSingleTop = true
            }
        }
        val route = nav.currentBackStackEntryAsState().value?.destination?.route
        LaunchedEffect(route) {
            currentRoute = route
            mainViewModel.clearNumber()
        }
        val context = LocalContext.current
        LaunchedEffect(Unit) {
            mainViewModel.openChannel.collect { target ->
                instantPlayer = false
                // TV list stays below the player, so Back from it lands on the channel list.
                nav.navigate(Routes.player(target.group, target.streamId)) { popUpTo(Routes.CHANNELS) }
            }
        }
        LaunchedEffect(Unit) {
            mainViewModel.numberNotFound.collect { text ->
                Toast.makeText(context, context.getString(R.string.channel_not_found, text), Toast.LENGTH_SHORT).show()
            }
        }
        // Movies and series get a still backdrop instead of live TV; the channel returns on the TV screens.
        val vodBrowsing = route == Routes.VOD || route == Routes.VOD_DETAILS
        if (vodBrowsing) BrandBackdrop()
        LaunchedEffect(route) {
            when (route) {
                Routes.VOD, Routes.VOD_DETAILS -> playback.pauseLiveForBrowse()
                Routes.CHANNELS, Routes.SEARCH, Routes.PLAYER, Routes.GUIDE -> playback.resumeLiveAfterBrowse()
            }
        }
        // Animations on: the library default fade (700 ms); off: screens replace each other at once.
        val animate = rs.timok.player.ui.theme.LocalAnimations.current
        // TVs that crop the picture edges (overscan) hide menus and the info bar; the margin moves all screens in.
        // The video itself stays full screen behind them.
        val margin by rs.timok.player.data.settings.AppPrefs.screenMargin.collectAsStateWithLifecycle()
        val cfg = androidx.compose.ui.platform.LocalConfiguration.current
        val scale = rs.timok.player.data.settings.AppPrefs.uiScale.collectAsStateWithLifecycle().value
        val accounts by mainViewModel.accounts.collectAsStateWithLifecycle()
        val activeAccount by mainViewModel.activeAccount.collectAsStateWithLifecycle()
        androidx.compose.runtime.CompositionLocalProvider(
            rs.timok.player.ui.components.LocalAccountSwitch provides
                if (accounts.size > 1) activeAccount?.name else null,
        ) {
        NavHost(
            modifier = Modifier.padding(
                horizontal = (cfg.screenWidthDp / scale * margin / 100f).dp,
                vertical = (cfg.screenHeightDp / scale * margin / 100f).dp,
            ),
            navController = nav,
            startDestination = startRoute,
            enterTransition = { if (animate) fadeIn(androidx.compose.animation.core.tween(700)) else EnterTransition.None },
            exitTransition = { if (animate) fadeOut(androidx.compose.animation.core.tween(700)) else ExitTransition.None },
            popEnterTransition = { if (animate) fadeIn(androidx.compose.animation.core.tween(700)) else EnterTransition.None },
            popExitTransition = { if (animate) fadeOut(androidx.compose.animation.core.tween(700)) else ExitTransition.None },
        ) {
            composable(Routes.LOGIN) {
                LoginScreen(onSignedIn = {
                    nav.navigate(Routes.CHANNELS) { popUpTo(Routes.LOGIN) { inclusive = true } }
                })
            }
            composable(Routes.LOGIN_ADD) {
                LoginScreen(
                    onSignedIn = mainViewModel::onAccountChanged,
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.CHANNELS,
                exitTransition = { if (instantPlayer || !animate) ExitTransition.None else null },
            ) {
                ChannelsScreen(
                    onPlay = { group, streamId ->
                        instantPlayer = false
                        nav.navigate(Routes.player(group, streamId))
                    },
                    onNavigate = onNavigate,
                    onProgram = { group, streamId -> nav.navigate(Routes.program(group, streamId)) },
                )
            }
            composable(
                Routes.PROGRAM,
                arguments = listOf(
                    navArgument("group") { type = NavType.StringType },
                    navArgument("streamId") { type = NavType.LongType },
                ),
            ) {
                rs.timok.player.ui.program.ProgramScreen(
                    onWatch = { group, streamId ->
                        instantPlayer = false
                        nav.navigate(Routes.player(group, streamId)) { popUpTo(Routes.CHANNELS) }
                    },
                    onPlayArchive = { request -> nav.navigate(Routes.vodPlayer(request, 0)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(
                Routes.GUIDE,
                arguments = listOf(
                    navArgument("group") { type = NavType.StringType },
                    navArgument("streamId") { type = NavType.LongType; defaultValue = -1L },
                ),
            ) {
                rs.timok.player.ui.guide.GuideScreen(
                    onWatch = { group, streamId ->
                        instantPlayer = false
                        nav.navigate(Routes.player(group, streamId)) { popUpTo(Routes.CHANNELS) }
                    },
                    onArchive = { request -> nav.navigate(Routes.vodPlayer(request, 0)) },
                    onProgram = { group, streamId -> nav.navigate(Routes.program(group, streamId)) },
                    onBack = { nav.popBackStack() },
                )
            }
            composable(Routes.SEARCH) {
                SearchScreen(
                    onPlay = { group, streamId -> nav.navigate(Routes.player(group, streamId)) },
                    onOpenVod = { item -> nav.navigate(Routes.vodDetails(item.kind, item.id)) },
                    onNavigate = onNavigate,
                )
            }
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    onBack = { nav.popBackStack() },
                    onSignedOut = {
                        playback.stop()
                        nav.navigate(Routes.LOGIN) { popUpTo(0) { inclusive = true } }
                    },
                    onAddAccount = { nav.navigate(Routes.LOGIN_ADD) { launchSingleTop = true } },
                    onSwitchAccount = mainViewModel::switchAccount,
                    onAccountChanged = mainViewModel::onAccountChanged,
                )
            }
            composable(Routes.VOD, arguments = listOf(navArgument("kind") { type = NavType.StringType })) {
                VodBrowseScreen(
                    onNavigate = onNavigate,
                    onOpen = { item -> nav.navigate(Routes.vodDetails(item.kind, item.id)) },
                )
            }
            composable(
                Routes.VOD_DETAILS,
                arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.LongType }),
            ) {
                VodDetailsScreen(onPlay = { target -> nav.navigate(Routes.vodPlayer(target.request, target.startMs)) })
            }
            composable(
                Routes.VOD_PLAYER,
                arguments = listOf(navArgument("req") { type = NavType.StringType }, navArgument("start") { type = NavType.LongType }),
            ) {
                VodPlayerScreen(onExit = { nav.popBackStack() })
            }
            composable(
                Routes.PLAYER,
                arguments = listOf(
                    navArgument("group") { type = NavType.StringType },
                    navArgument("streamId") { type = NavType.LongType },
                ),
                enterTransition = { if (instantPlayer || !animate) EnterTransition.None else null },
            ) {
                PlayerScreen(
                    onExit = {
                        playback.onLeaveFullscreen()
                        nav.popBackStack()
                    },
                    onArchive = { id, name, logo -> nav.navigate(Routes.archive(id, name, logo)) },
                    onProgram = { group, streamId -> nav.navigate(Routes.program(group, streamId)) },
                    onGuide = { group, streamId -> nav.navigate(Routes.guide(group, streamId)) },
                )
            }
            composable(
                Routes.ARCHIVE,
                arguments = listOf(
                    navArgument("streamId") { type = NavType.LongType },
                    navArgument("name") { type = NavType.StringType; defaultValue = "" },
                    navArgument("logo") { type = NavType.StringType; defaultValue = "" },
                ),
            ) {
                ArchiveScreen(
                    onPlay = { request -> nav.navigate(Routes.vodPlayer(request, 0)) },
                    onBack = { nav.popBackStack() },
                )
            }
        }
        }
        if (showAccounts) {
            rs.timok.player.ui.components.AccountPickerDialog(
                accounts = accounts,
                activeId = activeAccount?.id,
                onPick = { id ->
                    showAccounts = false
                    mainViewModel.switchAccount(id)
                },
                onDismiss = { showAccounts = false },
            )
        }
        rs.timok.player.ui.update.UpdateDialog(mainViewModel.updates)
        // Last, so it sits above everything else: the PIN for locked categories, asked for by any screen.
        val pinRequest by mainViewModel.parental.request.collectAsStateWithLifecycle()
        if (pinRequest != null) rs.timok.player.ui.components.ParentalPinPrompt(mainViewModel.parental)
        val dueReminder by mainViewModel.reminders.due.collectAsStateWithLifecycle()
        dueReminder?.let { r ->
            ReminderPopup(
                reminder = r,
                onWatch = {
                    mainViewModel.reminders.dismissDue()
                    instantPlayer = false
                    nav.navigate(Routes.player(r.groupKey, r.streamId)) { popUpTo(Routes.CHANNELS) }
                },
                onDismiss = mainViewModel.reminders::dismissDue,
            )
        }
        if (numberInput.isNotEmpty() && route in Routes.NUMBER_ENTRY) {
            // Same look as the number in the fullscreen player.
            Text(
                numberInput,
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(32.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 24.dp, vertical = 8.dp),
            )
        }
        LaunchedEffect(startChannel) {
            val target = startChannel ?: return@LaunchedEffect
            // Skip when the back stack was restored with the player already on top.
            if (startRoute == Routes.CHANNELS && nav.currentDestination?.route == Routes.CHANNELS) {
                instantPlayer = true
                nav.navigate(Routes.player(target.group, target.streamId))
            }
            mainViewModel.consumeStartChannel()
        }
    }
}

/** Opens the guide grid; filled in once the nav controller exists. */
private class GuideOpener {
    var action: (() -> Unit)? = null
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoBackground(playback: PlaybackController) {
    val aspect by playback.aspect.collectAsStateWithLifecycle()
    val texture by playback.textureVideo.collectAsStateWithLifecycle(initialValue = null)
    // Wait for the stored choice so the view is not created twice on start.
    val useTexture = texture ?: return
    // A new view per surface type; the old one detaches the player in onRelease.
    key(useTexture) {
    // Forced 16:9 / 4:3: the view is given that shape (centered, black around it) and fills it.
    val ratio = aspect.forcedRatio
    // Black behind the video: the window has no background of its own, so bars beside a 4:3 picture would
    // otherwise show whatever lies below the app (a white launcher on some boxes). The SurfaceView still punches
    // through this fill where the picture is.
    Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
    AndroidView(
        factory = { ctx ->
            val layout = if (useTexture) R.layout.player_view_texture else R.layout.player_view_surface
            (LayoutInflater.from(ctx).inflate(layout, null) as PlayerView).apply {
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                keepScreenOn = true
                isFocusable = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                // No background of its own: letterbox areas are transparent and show the black display below,
                // which saves a full-screen fill on every UI frame.
                setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                player = playback.livePlayer.player
            }
        },
        update = {
            it.resizeMode = when (aspect) {
                AspectMode.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
                AspectMode.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                AspectMode.STRETCH, AspectMode.RATIO_16_9, AspectMode.RATIO_4_3 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
            }
        },
        onRelease = { it.player = null },
        modifier = if (ratio != null) Modifier.fillMaxHeight().aspectRatio(ratio, matchHeightConstraintsFirst = true) else Modifier.fillMaxSize(),
    )
    }
    }
    // Radio channels have no picture: a radio screen instead of black.
    rs.timok.player.ui.player.RadioScreen()
}
