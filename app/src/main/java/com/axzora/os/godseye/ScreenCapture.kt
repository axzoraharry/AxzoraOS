package com.axzora.os.godseye

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * ScreenCapture — real Android screen capture via MediaProjection.
 *
 * The OpenRoom bridge triggers it (after the user grants the system "Start
 * capturing" permission), it grabs a frame to a PNG on disk, and the bridge
 * serves that frame at GET /v1/screen-frame so God's Eye / Mr Happy perceive can
 * pull REAL pixels (not just accessibility text).
 *
 * Honest: this yields real screen frames. Interpreting those pixels into
 * semantics still needs a vision model, which this device does not have — so the
 * frame is surfaced as pixels, and "understanding" is labeled as vision-needed.
 */
object ScreenCapture {
    private const val TAG = "AxzoraScreenCap"
    @Volatile private var frameFile: File? = null
    @Volatile private var latestFrame: File? = null
    @Volatile private var running = false
    private var mProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var widthPx = 1080
    private var heightPx = 1920
    private var dpi = 420

    /** Where frames are written (the bridge reads this path). */
    fun framePath(): String = frameFile?.absolutePath ?: ".not-captured"

    fun captured(): Boolean = latestFrame?.exists() == true

    /** Start a projection from the activity's result data. */
    fun start(activity: Activity, resultCode: Int, data: android.content.Intent) {
        try {
            val mm = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val proj = mm.getMediaProjection(resultCode, data) ?: run { Log.w(TAG, "projection null"); return }
            mProjection = proj
            proj.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { running = false; teardown() }
            }, Handler(Looper.getMainLooper()))

            val wm = activity.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val metrics = DisplayMetrics()
            if (Build.VERSION.SDK_INT >= 30) activity.display?.getRealMetrics(metrics) ?: wm.defaultDisplay.getRealMetrics(metrics)
            else wm.defaultDisplay.getRealMetrics(metrics)
            widthPx = metrics.widthPixels; heightPx = metrics.heightPixels; dpi = metrics.densityDpi

            val dir = File("/data/data/com.dsharnessmobile.shell/files/home")
            dir.mkdirs()
            frameFile = File(dir, "godseye_latest.png")
            imageReader = ImageReader.newInstance(widthPx, heightPx, PixelFormat.RGBA_8888, 2)
            imageReader?.setOnImageAvailableListener({ imgReader ->
                var img: android.media.Image? = null
                try {
                    img = imgReader.acquireLatestImage() ?: return@setOnImageAvailableListener
                    val plane = img.planes[0]
                    val buffer = plane.buffer
                    val rowStride = plane.rowStride
                    val pixelStride = plane.pixelStride
                    buffer.rewind()
                    // Build a bitmap at the captured size and copy pixels.
                    val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
                    bmp.copyPixelsFromBuffer(buffer)
                    savePng(bmp)
                    bmp.recycle()
                } catch (e: Throwable) { Log.w(TAG, "frame err " + e.message) } finally { img?.close() }
            }, Handler(Looper.getMainLooper()))
            virtualDisplay = mProjection?.createVirtualDisplay(
                "axzora-cap", widthPx, heightPx, dpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, imageReader?.surface, null, null,
            )
            running = true
            Log.i(TAG, "screen capture started ${widthPx}x${heightPx}")
        } catch (e: Throwable) { Log.w(TAG, "start err " + e.message) }
    }

    private fun savePng(bitmap: Bitmap) {
        try {
            val f = frameFile ?: return
            FileOutputStream(f).use { bitmap.compress(Bitmap.CompressFormat.PNG, 90, it) }
            latestFrame = f
        } catch (e: Throwable) { Log.w(TAG, "save err " + e.message) }
    }

    /** Stop and release. */
    fun stop() { running = false; teardown() }

    private fun teardown() {
        virtualDisplay?.release(); virtualDisplay = null
        imageReader?.setOnImageAvailableListener(null, null); imageReader?.close(); imageReader = null
        try { mProjection?.stop() } catch (e: Throwable) { /* already stopped */ }
        mProjection = null
    }

    fun isRunning(): Boolean = running
}
