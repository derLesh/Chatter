package me.lesh.chatter.ui.chat

import android.content.ClipData
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionOnScreen
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
import coil3.BitmapImage
import coil3.Image
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.size.Size as CoilSize
import coil3.toBitmap
import me.lesh.chatter.R
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where a shown image sits in the chat, so the viewer can grow out of it and shrink back into it.
 * Plain fields: set on every placement and load, read only when the viewer opens or closes.
 */
class ImageOrigin {
    var coordinates: LayoutCoordinates? = null

    /** The thumbnail as loaded, drawn while the full image loads; it may come from another loader's cache. */
    var image: Image? = null

    /** Null once the message has left the list; the thumbnail clears [coordinates] then. */
    fun boundsOnScreen(): Rect? =
        coordinates?.takeIf { it.isAttached }?.let { Rect(it.positionOnScreen(), it.size.toSize()) }
}

/** Shows a linked image full screen in the app; provided by [ImageViewerHost]. */
interface ImageViewer {
    fun show(url: String, title: String?, origin: ImageOrigin?)

    /** Whether the viewer is drawn over [origin]; its thumbnail hides then, so the image seems to move. */
    fun covers(origin: ImageOrigin): Boolean
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
    // Not saved: after a rotation the thumbnail is a different one, and the viewer fades instead.
    var shownOrigin by remember { mutableStateOf<ImageOrigin?>(null) }
    var covering by remember { mutableStateOf(false) }
    val viewer = remember {
        object : ImageViewer {
            override fun show(url: String, title: String?, origin: ImageOrigin?) {
                shownUrl = url
                shownTitle = title
                shownOrigin = origin
                covering = false
            }

            override fun covers(origin: ImageOrigin) = covering && shownOrigin === origin
        }
    }
    CompositionLocalProvider(LocalImageViewer provides viewer) { content() }
    // Keyed, so a new image starts afresh and an old closing animation cannot close it.
    shownUrl?.let { url ->
        key(url) {
            ImageViewerDialog(
                url = url,
                title = shownTitle,
                origin = shownOrigin,
                loader = loader,
                onCover = { covering = true },
                onClosed = {
                    shownUrl = null
                    shownOrigin = null
                    covering = false
                },
            )
        }
    }
}

/**
 * Material's container transform: the image grows out of its thumbnail and shrinks back into it, or
 * fades when the thumbnail is gone.
 */
@Composable
private fun ImageViewerDialog(
    url: String,
    title: String?,
    origin: ImageOrigin?,
    loader: ImageLoader,
    onCover: () -> Unit,
    onClosed: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val motion = MaterialTheme.motionScheme
    val spatial = motion.defaultSpatialSpec<Float>()
    val effects = motion.defaultEffectsSpec<Float>()
    val zoom = remember(url) { ZoomState() }
    // 0: drawn over the thumbnail, 1: full screen. A spring may pass either end a little.
    val reveal = remember(url) { Animatable(0f) }
    // The thumbnail in the viewer's own coordinates; null fades instead of moving.
    var thumbnail by remember(url) { mutableStateOf<Rect?>(null) }
    var closing by remember(url) { mutableStateOf(false) }
    val viewerOnScreen = remember { ViewerPosition() }
    fun thumbnailNow() = origin?.boundsOnScreen()?.translate(-viewerOnScreen.topLeft)

    fun close() {
        if (closing) return
        closing = true
        zoom.stopAnimation()
        thumbnail = thumbnailNow()
        scope.launch {
            try {
                if (thumbnail != null) {
                    launch { zoom.reset() }
                    launch { zoom.dropDismiss(spatial) }
                    reveal.animateTo(0f, spatial)
                } else {
                    reveal.animateTo(0f, effects)
                }
            } finally {
                // Also when another animation cut it short, or the viewer would stay open with no way
                // out; not when the viewer itself is gone, as a new image may have replaced it.
                if (isActive) onClosed()
            }
        }
    }

    Dialog(
        onDismissRequest = ::close,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        val view = LocalView.current
        SideEffect {
            val window = (view.parent as? DialogWindowProvider)?.window ?: return@SideEffect
            // No dim and no window animation of its own: the transform below is the whole transition.
            window.setDimAmount(0f)
            window.setWindowAnimations(0)
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
        LaunchedEffect(url) {
            // One frame first, so the viewer is laid out and knows where it sits on screen.
            withFrameNanos { }
            if (closing) return@LaunchedEffect
            thumbnail = thumbnailNow()
            thumbnail?.let { if (zoom.imageSize.isUnspecified) zoom.imageSize = it.size }
            onCover()
            reveal.animateTo(1f, if (thumbnail != null) spatial else effects)
        }
        val uriHandler = LocalUriHandler.current
        val clipboard = LocalClipboard.current
        val density = LocalDensity.current
        val dismissDistance = with(density) { DISMISS_DISTANCE.toPx() }
        val cornerRadius = with(density) { LINKED_IMAGE_CORNER.toPx() }
        var controlsShown by remember { mutableStateOf(true) }
        var loading by remember(url) { mutableStateOf(true) }
        var failed by remember(url) { mutableStateOf(false) }
        // Animated values are read while drawing; these only flip once, so composing again is rare.
        val opened by remember { derivedStateOf { reveal.value >= 1f } }
        val dragging by remember { derivedStateOf { zoom.dismissOffset != 0f } }

        // The gestures cover the whole screen, below the image and without its graphicsLayer, so they
        // see the screen, not the moved image.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val dragged = (abs(zoom.dismissOffset) / dismissDistance).coerceIn(0f, 1f)
                    alpha = reveal.value.coerceIn(0f, 1f) * (1f - dragged * 0.7f)
                }
                .background(Color.Black)
                .onGloballyPositioned { viewerOnScreen.topLeft = it.positionOnScreen() }
                .onSizeChanged { zoom.resize(it.toSize()) }
                // Let go while closing, so no finger fights the way back.
                .then(
                    if (closing) Modifier else Modifier
                        .pointerInput(zoom) {
                            detectTapGestures(
                                onTap = { controlsShown = !controlsShown },
                                onDoubleTap = { tap -> zoom.toggle(tap, scope) },
                            )
                        }
                        .pointerInput(zoom) { zoomAndDismiss(zoom, dismissDistance, scope, ::close) },
                ),
        )
        Box(Modifier.fillMaxSize()) {
            val context = LocalPlatformContext.current
            val request = remember(url) {
                ImageRequest.Builder(context)
                    .data(url)
                    // Up to the loader's size cap rather than screen size, so zooming in stays sharper.
                    .size(CoilSize.ORIGINAL)
                    .placeholder(origin?.image?.let(::stillOf))
                    .build()
            }
            val fitted = zoom.fittedSize()
            AsyncImage(
                model = request,
                contentDescription = title?.removeSurrounding("[", "]") ?: stringResource(R.string.linked_image),
                imageLoader = loader,
                contentScale = ContentScale.Fit,
                onLoading = { loading = it.painter == null },
                onSuccess = {
                    loading = false
                    zoom.imageSize = it.painter.intrinsicSize
                },
                onError = {
                    loading = false
                    failed = true
                },
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(with(density) { fitted.toDpSize() })
                    .graphicsLayer {
                        val p = reveal.value.coerceAtLeast(0f)
                        // Followed while closing, as new messages may move the thumbnail meanwhile.
                        val from = thumbnail?.let { if (closing) thumbnailNow() ?: it else it }
                        val growX: Float
                        val growY: Float
                        if (from != null && fitted.width > 0f && fitted.height > 0f) {
                            growX = lerp(from.width / fitted.width, 1f, p)
                            growY = lerp(from.height / fitted.height, 1f, p)
                            val shift = (from.center - zoom.containerSize.center) * (1f - p)
                            translationX = shift.x + zoom.offset.x
                            translationY = shift.y + zoom.offset.y + zoom.dismissOffset
                            // Clipped before scaling, so the radius is divided to keep the thumbnail's corners.
                            shape = RoundedCornerShape(cornerRadius * (1f - p).coerceIn(0f, 1f) / growX)
                            clip = true
                        } else {
                            growX = lerp(FADE_SCALE, 1f, p)
                            growY = growX
                            translationX = zoom.offset.x
                            translationY = zoom.offset.y + zoom.dismissOffset
                            alpha = p.coerceAtMost(1f)
                        }
                        scaleX = growX * zoom.scale
                        scaleY = growY * zoom.scale
                    },
            )
            if (loading && opened) CircularProgressIndicator(color = Color.White, modifier = Modifier.align(Alignment.Center))
            if (failed) {
                Text(
                    stringResource(R.string.image_viewer_failed),
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center).padding(24.dp),
                )
            }
            val controlsVisible = controlsShown && !dragging && opened && !closing
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                    .padding(12.dp),
            ) {
                Surface(onClick = ::close, shape = CircleShape, color = BUBBLE_COLOR, contentColor = Color.White) {
                    Icon(Icons.Default.Close, stringResource(R.string.image_viewer_close), Modifier.padding(10.dp))
                }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(motion.defaultEffectsSpec()),
                exit = fadeOut(motion.fastEffectsSpec()),
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
                        // Closed at once: the browser covers it anyway, and the link check may ask
                        // in a dialog that would sit below this one.
                        onClosed()
                        uriHandler.openUri(url)
                    }
                }
            }
        }
    }
}

/**
 * [image] if it is a plain bitmap, else a still copy of its current frame: an animated drawable plays
 * in one place only, and drawn here too it would stop moving in the chat.
 */
private fun stillOf(image: Image): Image? =
    if (image is BitmapImage) image else runCatching { image.toBitmap().asImage() }.getOrNull()

/** Where the viewer's window starts on screen; a plain field, read only when the image moves. */
private class ViewerPosition {
    var topLeft = Offset.Zero
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
    var containerSize by mutableStateOf(Size.Zero)
        private set
    var imageSize by mutableStateOf(Size.Unspecified)
    private var animation: Job? = null

    /** The image as [ContentScale.Fit] draws it at scale 1; panning stops at its edges, not the screen's. */
    fun fittedSize(): Size {
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

    suspend fun reset() {
        val startScale = scale
        val startOffset = offset
        animate(0f, 1f) { t, _ ->
            scale = lerp(startScale, 1f, t)
            offset = lerp(startOffset, Offset.Zero, t)
        }
    }

    suspend fun dropDismiss(spec: AnimationSpec<Float>) {
        animate(dismissOffset, 0f, animationSpec = spec) { value, _ -> dismissOffset = value }
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
/** The corners of a linked image in the chat, which the viewer grows out of. */
internal val LINKED_IMAGE_CORNER = 8.dp
/** How small the image starts when it fades in without a thumbnail to grow out of. */
private const val FADE_SCALE = 0.9f
// Grey, not black: it has to stand out on the black around the image as well as on the image.
private val BUBBLE_COLOR = Color(0xD9333333)
