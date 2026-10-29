package com.axzora.os.call

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Draws a WebRTC video track.
 *
 * The renderer is created once and reused; the track is attached and detached
 * around it, because releasing an initialised [SurfaceViewRenderer] and
 * building another one on every recomposition flickers badly and leaks EGL
 * surfaces.
 */
@Composable
fun VideoView(
    track: VideoTrack?,
    eglBase: EglBase,
    modifier: Modifier = Modifier,
    mirror: Boolean = false,
    fill: Boolean = true,
) {
    val renderer = remember(eglBase) { mutableRendererHolder() }

    DisposableEffect(track) {
        val view = renderer.view
        if (view != null && track != null) {
            runCatching { track.addSink(view) }
        }
        onDispose {
            if (view != null && track != null) runCatching { track.removeSink(view) }
        }
    }

    AndroidView(
        factory = { ctx ->
            SurfaceViewRenderer(ctx).apply {
                init(eglBase.eglBaseContext, null)
                setEnableHardwareScaler(true)
                // Sit above the window background rather than punching a hole
                // through it. Without this the video surface is composited
                // *below* the whole activity on some devices — most Samsung
                // and Xiaomi builds — and the far end shows as a black box
                // while everything else draws fine.
                setZOrderMediaOverlay(true)
                keepScreenOn = true
                applyScaling(this, fill)
                setMirror(mirror)
                renderer.view = this
                // Attach immediately when the track was ready before the view.
                track?.let { runCatching { it.addSink(this) } }
            }
        },
        update = {
            it.setMirror(mirror)
            // `fill` changes when the layout does — a self-view moving to
            // full screen, say — and only re-applying mirror left the old
            // scaling behind.
            applyScaling(it, fill)
        },
        onRelease = { view ->
            runCatching { track?.removeSink(view) }
            runCatching { view.release() }
            renderer.view = null
        },
        modifier = modifier,
    )
}

/**
 * How a frame is fitted to its box.
 *
 * The remote feed used to be cropped to fill the screen, which is fine when
 * both phones hold the camera the same way and disfiguring when they don't:
 * a portrait sender on a landscape-ish box loses most of their face off the
 * sides. Fitting shows the whole frame — letterboxed on mismatched aspect
 * ratios, which is the honest result, and what every serious call app does
 * for the far end. Only the small self-view still fills, because it is
 * square-ish and cropping it costs nothing.
 */
private fun applyScaling(view: SurfaceViewRenderer, fill: Boolean) {
    view.setScalingType(
        if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL
        else RendererCommon.ScalingType.SCALE_ASPECT_FIT
    )
}

/** Small mutable box so the composable can keep a handle on its renderer. */
class RendererHolder { var view: SurfaceViewRenderer? = null }

private fun mutableRendererHolder() = RendererHolder()
