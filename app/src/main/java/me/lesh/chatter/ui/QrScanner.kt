package me.lesh.chatter.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.view.CameraController
import androidx.camera.view.LifecycleCameraController
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import me.lesh.chatter.R
import me.lesh.chatter.util.QrDecoder
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A card with a frame to tap. Tapped, the camera fills the card and the frame shows where the code
 * goes; the first QR code it sees is handed to [onScanned]. The camera is only on while the card
 * shows it, and goes off once a code is read. [scanned] says the text now in use came from here.
 */
@Composable
fun QrScanCard(
    caption: String,
    scanned: Boolean,
    onScanned: (String) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onCameraOn: () -> Unit = {},
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val haptics = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    var cameraOn by remember { mutableStateOf(false) }
    var access by remember { mutableStateOf(CameraAccess.Unasked) }
    val askForCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) onCameraOn()
        cameraOn = granted
        access = when {
            granted -> CameraAccess.Unasked
            // Android stops asking after the second no; only the app's settings can allow it then.
            activity?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false -> CameraAccess.Blocked
            else -> CameraAccess.Refused
        }
    }
    // The app's settings may have allowed it meanwhile.
    LifecycleResumeEffect(Unit) {
        if (cameraAllowed(context)) access = CameraAccess.Unasked
        onPauseOrDispose { }
    }

    fun turnOn() {
        focus.clearFocus()
        when {
            cameraAllowed(context) -> {
                onCameraOn()
                cameraOn = true
            }
            access == CameraAccess.Blocked -> context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri())
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            else -> askForCamera.launch(Manifest.permission.CAMERA)
        }
    }

    val showCamera = cameraOn && enabled
    // Back closes the camera first, keeping the screen and what was typed.
    NavigationBackHandler(
        state = rememberNavigationEventState(currentInfo = NavigationEventInfo.None),
        isBackEnabled = showCamera,
        onBackCompleted = { cameraOn = false },
    )
    val idleLabel = stringResource(
        when {
            access == CameraAccess.Blocked -> R.string.login_token_camera_settings
            access == CameraAccess.Refused -> R.string.login_token_camera_allow
            scanned -> R.string.login_token_scan_again
            else -> R.string.login_token_scan_tap
        },
    )
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .clip(RoundedCornerShape(28.dp))
            .background(
                Brush.radialGradient(
                    listOf(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.surfaceContainerLow),
                ),
            )
            // Not clickable at all while the camera shows, so TalkBack reaches its own off button.
            .then(if (showCamera) Modifier else Modifier.clickable(enabled = enabled, onClickLabel = idleLabel, role = Role.Button, onClick = ::turnOn)),
    ) {
        if (showCamera) {
            CameraPreview(
                onScanned = { code ->
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    cameraOn = false
                    onScanned(code)
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Keeps the caption readable on a bright picture.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))),
            )
            IconButton(
                onClick = { cameraOn = false },
                // Backed, so it stays visible on a bright picture.
                colors = IconButtonDefaults.iconButtonColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.login_token_camera_stop), tint = Color.White)
            }
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 20.dp),
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(160.dp)
                    .border(3.dp, if (showCamera) Color.White else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), RoundedCornerShape(32.dp)),
            ) {
                if (!showCamera) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            painterResource(R.drawable.ic_qr_code),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(36.dp),
                        )
                        Spacer(Modifier.height(10.dp))
                        Text(
                            idleLabel,
                            style = MaterialTheme.typography.labelLarge,
                            textAlign = TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(horizontal = 8.dp),
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                when {
                    showCamera -> stringResource(R.string.login_token_scan_aim)
                    access != CameraAccess.Unasked -> stringResource(R.string.login_token_camera_refused)
                    scanned -> stringResource(R.string.login_token_scanned)
                    else -> caption
                },
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = if (showCamera) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
}

private enum class CameraAccess { Unasked, Refused, Blocked }

private fun cameraAllowed(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

/** The back camera, passing each frame to [QrDecoder] on a thread of its own until one holds a code. */
@Composable
private fun CameraPreview(onScanned: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnScanned by rememberUpdatedState(onScanned)
    val controller = remember {
        LifecycleCameraController(context).apply {
            cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
            setEnabledUseCases(CameraController.IMAGE_ANALYSIS)
            imageAnalysisBackpressureStrategy = ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
        }
    }

    DisposableEffect(lifecycleOwner) {
        val decoding = Executors.newSingleThreadExecutor()
        val found = AtomicBoolean(false)
        val main = ContextCompat.getMainExecutor(context)
        controller.setImageAnalysisAnalyzer(decoding) { frame ->
            val code = frame.use { if (found.get()) null else it.readQrCode() }
            if (code != null && found.compareAndSet(false, true)) main.execute { currentOnScanned(code) }
        }
        controller.bindToLifecycle(lifecycleOwner)
        onDispose {
            controller.unbind()
            controller.clearImageAnalysisAnalyzer()
            decoding.shutdown()
        }
    }

    AndroidView(
        factory = {
            PreviewView(it).apply {
                // A TextureView, which follows the rounded clip; a SurfaceView would draw over it.
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                scaleType = PreviewView.ScaleType.FILL_CENTER
                this.controller = controller
            }
        },
        modifier = modifier,
    )
}

/** Reads the brightness plane, which is all a QR code needs. */
private fun ImageProxy.readQrCode(): String? {
    val plane = planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining()).also(buffer::get)
    return QrDecoder.decode(bytes, plane.rowStride, width, height)
}
