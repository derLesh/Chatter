package me.lesh.chatter.ui.recap

import android.app.Activity
import android.view.accessibility.AccessibilityManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import me.lesh.chatter.R
import me.lesh.chatter.auth.AuthState
import me.lesh.chatter.chat.isDefaultPicture
import me.lesh.chatter.ui.MainViewModel
import me.lesh.chatter.ui.theme.isAppInDarkTheme

/**
 * The look back at [year], one full-screen page after another like the stories other apps end
 * their year with: a tap on the right goes on, on the left back, holding pauses. It ends on a
 * picture to share.
 */
@Composable
fun RecapScreen(vm: MainViewModel, year: Int, onClose: () -> Unit) {
    // Taken once: the numbers counting up should not jump while the user sends something.
    val stats = remember(year) { vm.yearStats(year) }
    val info by vm.channelInfo.collectAsStateWithLifecycle()
    val pictures by vm.userPictures.collectAsStateWithLifecycle()
    LaunchedEffect(stats) {
        vm.requestPictures((stats.topMentionedBy(PEOPLE_SHOWN) + stats.topTalkedTo(PEOPLE_SHOWN)).map { it.first })
    }
    val auth by vm.authState.collectAsStateWithLifecycle()
    val me = (auth as? AuthState.LoggedIn)?.account?.let { account ->
        RecapProfile(account.displayName.ifEmpty { account.login }, account.avatarUrl.takeUnless(::isDefaultPicture))
    }
    val pages = remember(stats) { recapPages(stats) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var held by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Pages that turn by themselves cannot be read in time with TalkBack.
    val autoAdvance = remember {
        context.getSystemService(AccessibilityManager::class.java)?.isTouchExplorationEnabled != true
    }
    val last = pages.lastIndex
    // Counted in frame time rather than animated: with animations off in Android's settings an
    // animation ends at once, and every page would rush past.
    var shownMs by remember(page) { mutableLongStateOf(0L) }
    // A page that has to be scrolled, in landscape or with large text, waits for a tap instead.
    var pageScrolls by remember(page) { mutableStateOf(false) }
    LaunchedEffect(page, held, pageScrolls) {
        if (held || !autoAdvance || pageScrolls) return@LaunchedEffect
        var before = withFrameMillis { it }
        while (shownMs < PAGE_MS) {
            val now = withFrameMillis { it }
            shownMs += now - before
            before = now
        }
        if (page < last) page++
    }
    val goBack = { if (page > 0) page-- }
    val goOn = { if (page < last) page++ }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    val colors = pages[page].colors
    val content by animateColorAsState(colors.content, label = "recap-content")
    SystemBarIcons(light = colors.background.luminance() > 0.5f)

    val previousLabel = stringResource(R.string.recap_previous)
    val nextLabel = stringResource(R.string.recap_next)
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.background)
            .pointerInput(last, rtl) {
                detectTapGestures(
                    onPress = {
                        held = true
                        tryAwaitRelease()
                        held = false
                    },
                    // Without it, letting go after holding would count as a tap and turn the page.
                    onLongPress = {},
                    onTap = { at ->
                        val start = if (rtl) at.x > size.width * (1 - BACK_ZONE) else at.x < size.width * BACK_ZONE
                        if (start) goBack() else goOn()
                    },
                )
            }
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(previousLabel) { goBack(); true },
                    CustomAccessibilityAction(nextLabel) { goOn(); true },
                )
            },
    ) {
        AnimatedContent(
            targetState = page,
            transitionSpec = { (fadeIn(tween(320)) + scaleIn(tween(420), initialScale = 0.94f)) togetherWith fadeOut(tween(180)) },
            label = "recap-page",
        ) { shown ->
            val kind = pages[shown]
            Box(Modifier.fillMaxSize().background(kind.colors.background)) {
                Decorations(kind, kind.colors)
                Box(
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.safeDrawing)
                        .padding(top = TOP_BAR_HEIGHT),
                    contentAlignment = Alignment.TopCenter,
                ) {
                    Box(Modifier.widthIn(max = MAX_PAGE_WIDTH).fillMaxHeight()) {
                        RecapPageContent(
                            page = kind,
                            year = year,
                            stats = stats,
                            info = info,
                            imageLoader = vm.imageLoader,
                            staticImageLoader = vm.staticImageLoader,
                            pictures = pictures,
                            me = me,
                            onReplay = { page = 0 },
                            onNeedsScrolling = { if (shown == page) pageScrolls = it },
                        )
                    }
                }
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .padding(start = 16.dp, end = 4.dp, top = 8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    pages.indices.forEach { i ->
                        val filled = when {
                            i < page -> 1f
                            i > page -> 0f
                            !autoAdvance || pageScrolls -> 1f
                            else -> (shownMs.toFloat() / PAGE_MS).coerceAtMost(1f)
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .height(4.dp)
                                .clip(CircleShape)
                                .background(content.copy(alpha = 0.3f)),
                        ) {
                            Box(
                                Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(filled)
                                    .background(content),
                            )
                        }
                    }
                }
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = stringResource(R.string.recap_close), tint = content)
                }
            }
        }
    }
}

/** Status and navigation bar icons readable on the page; the theme's again once the look back closes. */
@Composable
private fun SystemBarIcons(light: Boolean) {
    val view = LocalView.current
    val themeLight = !isAppInDarkTheme()
    DisposableEffect(light, themeLight) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = light
        controller?.isAppearanceLightNavigationBars = light
        onDispose {
            controller?.isAppearanceLightStatusBars = themeLight
            controller?.isAppearanceLightNavigationBars = themeLight
        }
    }
}

/** How long a page stays before the next one; long enough to read the busiest page twice. */
private const val PAGE_MS = 8_000

/** The share of the width at the start edge that goes back instead of on. */
private const val BACK_ZONE = 0.3f

private val TOP_BAR_HEIGHT = 56.dp

/** On tablets and in landscape the page keeps the width of a phone's, centred. */
private val MAX_PAGE_WIDTH = 480.dp
