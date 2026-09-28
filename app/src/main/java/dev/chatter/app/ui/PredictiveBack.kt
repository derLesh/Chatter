package dev.chatter.app.ui

import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.SeekableTransitionState
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import kotlinx.coroutines.launch

/**
 * A move between screens that the back gesture can hold halfway. While the user drags, the
 * transition is seeked from [current] towards [backTo] as far as their finger has gone, so the
 * screen underneath shows before they let go; letting go calls [onBack] and the move finishes
 * from where the finger left it, calling it off slides everything back.
 *
 * Without [backTo] there is nowhere to go back to here, and the gesture is left to whoever
 * handles it further out — in the end, Android, which leaves the app.
 *
 * Every other change of [current] (a tap on a button, a notification) animates on its own.
 */
@Composable
fun <T> rememberPredictiveTransition(current: T, backTo: T?, onBack: () -> Unit, label: String): Transition<T> {
    val seekable = remember { SeekableTransitionState(current) }
    val scope = rememberCoroutineScope()
    val back = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)

    LaunchedEffect(current) { seekable.animateTo(current) }

    val gesture = back.transitionState
    LaunchedEffect(gesture, backTo) {
        if (gesture is NavigationEventTransitionState.InProgress && backTo != null && backTo != current) {
            seekable.seekTo(gesture.latestEvent.progress, targetState = backTo)
        }
    }
    NavigationBackHandler(
        state = back,
        isBackEnabled = backTo != null,
        onBackCancelled = { scope.launch { seekable.animateTo(current) } },
        onBackCompleted = onBack,
    )
    return rememberTransition(seekable, label = label)
}

/**
 * Like Android: the screen being opened (title bar included) slides in over the one it opens
 * from, which moves a little aside and dims; going back, it slides out on top again. Screens are
 * opaque, so nothing shows through.
 */
fun slideBetweenScreens(forward: Boolean): ContentTransform {
    val transform = if (forward) {
        slideInHorizontally { it } togetherWith
            (slideOutHorizontally { -it / 4 } + fadeOut(targetAlpha = 0.5f))
    } else {
        (slideInHorizontally { -it / 4 } + fadeIn(initialAlpha = 0.5f)) togetherWith
            slideOutHorizontally { it }
    }
    return transform.apply { targetContentZIndex = if (forward) 1f else -1f }
}
