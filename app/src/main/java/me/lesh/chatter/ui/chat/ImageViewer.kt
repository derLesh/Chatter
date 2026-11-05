package me.lesh.chatter.ui.chat

import android.content.ClipData
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animate
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.center
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.geometry.isUnspecified
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.util.lerp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import me.lesh.chatter.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Shows a linked image full screen in the app; provided by [ImageViewerHost]. */
fun interface ImageViewer {
    fun show(url: String, title: String?)
}

/** Null without an [ImageViewerHost] around, as in Compose previews: the image then opens as a link. */
val LocalImageViewer = staticCompositionLocalOf<ImageViewer?> { null }

/**
 * Provides [LocalImageViewer] to [content] and draws the image it shows. Sits above the chat, so the
 * viewer stays open while its message scrolls out of the list. Always uses the animated [loader]:
 * the user asked for this one image.
 */
@Composable
fun ImageViewerHost(loader: ImageLoader, content: @Composable () -> Unit) {
    var shownUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var shownTitle by rememberSaveable { mutableStateOf<String?>(null) }
    val viewer = remember {
        ImageViewer { url, title ->
            shownUrl = url
            shownTitle = title
        }
    }
    CompositionLocalProvider(LocalImageViewer provides viewer) { content() }
    shownUrl?.let { url -> ImageViewerDialog(url, shownTitle, loader, onDismiss = { shownUrl = null }) }
}

@Composable
private fun ImageViewerDialog(url: String, title: String?, loader: ImageLoader, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            // No dim of its own, so dragging the image away fades to the chat; light icons on black.
            window.setDimAmount(0f)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
        val uriHandler = LocalUriHandler.current
        val clipboard = LocalClipboard.current
        val scope = rememberCoroutineScope()
        val zoom = remember(url) { ZoomState() }
        val dismissDistance = with(LocalDensity.current) { DISMISS_DISTANCE.toPx() }
        var controlsShown by remember { mutableStateOf(true) }
        var loading by remember(url) { mutableStateOf(true) }
        var failed by remember(url) { mutableStateOf(false) }
        val dragged = (abs(zoom.dismissOffset) / dismissDistance).coerceIn(0f, 1f)

        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 1f - dragged * 0.7f))) {
            val context = LocalPlatformContext.current
            // Up to the loader's size cap rather than screen size, so zooming in stays sharper.
            val request = remember(url) { ImageRequest.Builder(context).data(url).size(CoilSize.ORIGINAL).build() }
            AsyncImage(
                model = request,
                contentDescription = title?.removeSurrounding("[", "]") ?: stringResource(R.string.linked_image),
                imageLoader = loader,
                contentScale = ContentScale.Fit,
                onSuccess = {
                    loading = false
                    zoom.imageSize = it.painter.intrinsicSize
                },
                onError = {
                    loading = false
                    failed = true
                },
                // The gestures come before graphicsLayer, so they see the screen, not the moved image.
                modifier = Modifier
                    .fillMaxSize()
                    .onSizeChanged { zoom.resize(it.toSize()) }
                    .pointerInput(zoom) {
                        detectTapGestures(
                            onTap = { controlsShown = !controlsShown },
                            onDoubleTap = { tap -> zoom.toggle(tap, scope) },
                        )
                    }
                    .pointerInput(zoom) { zoomAndDismiss(zoom, dismissDistance, scope, onDismiss) }
                    .graphicsLayer {
                        scaleX = zoom.scale
                        scaleY = zoom.scale
                        translationX = zoom.offset.x
                        translationY = zoom.offset.y + zoom.dismissOffset
                    },
            )
            if (loading) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            if (failed) {
                Text(
                    stringResource(R.string.image_viewer_failed),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            val controlsVisible = controlsShown && dragged == 0f
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(12.dp),
            ) {
                Surface(onClick = onDismiss, shape = CircleShape, color = BUBBLE_COLOR, contentColor = Color.White) {
                    Icon(Icons.Default.Close, stringResource(R.string.image_viewer_close), Modifier.padding(10.dp))
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                    .padding(16.dp),
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    ViewerBubble(R.drawable.ic_content_copy, R.string.image_viewer_copy_link) {
                        scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(url, url))) }
                    }
                    ViewerBubble(R.drawable.ic_open_in_new, R.string.image_viewer_open_in_browser) {
                        // Closed first: the link check may ask, and its dialog would sit below this one.
                        onDismiss()
                        uriHandler.openUri(url)
                    }
                }
            }
        }
    }
}

@Composable
private fun ViewerBubble(@DrawableRes icon: Int, @StringRes label: Int, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = BUBBLE_COLOR, contentColor = Color.White) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
        ) {
            Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Zoom and position of the shown image, in pixels; [offset] is from the centre of the screen. */
@Stable
private class ZoomState {
    var scale by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var dismissOffset by mutableFloatStateOf(0f)
    var containerSize = Size.Zero
    var imageSize = Size.Unspecified
    private var animation: Job? = null

    /** The image as [ContentScale.Fit] draws it at scale 1; panning stops at its edges, not the screen's. */
    private fun fittedSize(): Size {
        if (imageSize.isUnspecified || imageSize.width <= 0f || imageSize.height <= 0f) return containerSize
        val fit = min(containerSize.width / imageSize.width, containerSize.height / imageSize.height)
        return Size(imageSize.width * fit, imageSize.height * fit)
    }

    /** A folded or split screen moves the edges, so a zoomed image is held inside them again. */
    fun resize(size: Size) {
        containerSize = size
        offset = clamp(offset, scale)
    }

    private fun clamp(offset: Offset, scale: Float): Offset {
        val fitted = fittedSize()
        val maxX = max(0f, (fitted.width * scale - containerSize.width) / 2)
        val maxY = max(0f, (fitted.height * scale - containerSize.height) / 2)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }

    /** Where [offset] has to go for the point under [focus] to stay put when zooming to [newScale]. */
    private fun offsetKeeping(focus: Offset, newScale: Float): Offset {
        val fromCenter = focus - containerSize.center
        return fromCenter - (fromCenter - offset) * (newScale / scale)
    }

    fun zoomBy(factor: Float, focus: Offset, pan: Offset) {
        val newScale = (scale * factor).coerceIn(1f, MAX_SCALE)
        offset = clamp(offsetKeeping(focus, newScale) + pan, newScale)
        scale = newScale
    }

    /** Zooms in on [tap], or back out if already zoomed. */
    fun toggle(tap: Offset, scope: CoroutineScope) {
        val targetScale = if (scale > 1f) 1f else DOUBLE_TAP_SCALE
        val targetOffset = clamp(offsetKeeping(tap, targetScale), targetScale)
        val startScale = scale
        val startOffset = offset
        animateWith(scope) {
            animate(0f, 1f) { t, _ ->
                scale = lerp(startScale, targetScale, t)
                // Clamped every frame: the screen may change size while it runs.
                offset = clamp(lerp(startOffset, targetOffset, t), scale)
            }
        }
    }

    fun settleDismiss(scope: CoroutineScope) {
        animateWith(scope) { animate(dismissOffset, 0f) { value, _ -> dismissOffset = value } }
    }

    /** Stops a running animation, so it does not fight the finger that just touched down. */
    fun stopAnimation() {
        animation?.cancel()
        animation = null
    }

    private fun animateWith(scope: CoroutineScope, block: suspend () -> Unit) {
        stopAnimation()
        animation = scope.launch { block() }
    }
}

/**
 * Pinch and drag to zoom and pan. Not zoomed, dragging one finger moves the image up or down, and
 * letting go past [dismissDistance] closes it, as in other apps. Taps are left to the tap detector.
 */
private suspend fun PointerInputScope.zoomAndDismiss(
    zoom: ZoomState,
    dismissDistance: Float,
    scope: CoroutineScope,
    onDismiss: () -> Unit,
) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false)
        zoom.stopAnimation()
        var moving = false
        var pinched = false
        var zoomSoFar = 1f
        var panSoFar = Offset.Zero
        do {
            val event = awaitPointerEvent()
            if (event.changes.any { it.isConsumed }) break
            val factor = event.calculateZoom()
            val pan = event.calculatePan()
            if (!moving) {
                zoomSoFar *= factor
                panSoFar += pan
                val zoomMotion = abs(1 - zoomSoFar) * event.calculateCentroidSize(useCurrent = false)
                moving = zoomMotion > viewConfiguration.touchSlop || panSoFar.getDistance() > viewConfiguration.touchSlop
            }
            // The release has no pressed pointer and so no centroid; it would turn the offset into NaN.
            val centroid = event.calculateCentroid()
            if (moving && centroid.isSpecified) {
                if (event.changes.count { it.pressed } > 1) pinched = true
                if (pinched) zoom.dismissOffset = 0f
                if (zoom.scale > 1f || pinched) zoom.zoomBy(factor, centroid, pan)
                else zoom.dismissOffset += pan.y
                event.changes.forEach { if (it.positionChanged()) it.consume() }
            }
        } while (event.changes.any { it.pressed })
        when {
            abs(zoom.dismissOffset) > dismissDistance -> onDismiss()
            zoom.dismissOffset != 0f -> zoom.settleDismiss(scope)
        }
    }
}

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private val DISMISS_DISTANCE = 96.dp
// Grey, not black: it has to stand out on the black around the image as well as on the image.
private val BUBBLE_COLOR = Color(0xD9333333)
