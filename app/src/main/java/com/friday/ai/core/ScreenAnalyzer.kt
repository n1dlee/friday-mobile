package com.friday.ai.core

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.DisplayMetrics
import java.io.ByteArrayOutputStream

class ScreenAnalyzer(private val context: Context) {

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null

    fun getProjectionIntent(activity: Activity): Intent {
        val manager = activity.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        return manager.createScreenCaptureIntent()
    }

    fun captureScreen(
        resultCode: Int,
        data: Intent,
        onCaptured: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        val manager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        try {
            mediaProjection = manager.getMediaProjection(resultCode, data)
        } catch (e: Exception) {
            onError("Failed to get media projection: ${e.message}")
            return
        }

        val metrics = context.resources.displayMetrics
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "FridayScreenCapture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader.surface, null, null
        )

        Handler(Looper.getMainLooper()).postDelayed({
            try {
                val image = imageReader.acquireLatestImage()
                if (image != null) {
                    val planes = image.planes
                    val buffer = planes[0].buffer
                    val pixelStride = planes[0].pixelStride
                    val rowStride = planes[0].rowStride
                    val rowPadding = rowStride - pixelStride * width

                    val bitmap = Bitmap.createBitmap(
                        width + rowPadding / pixelStride, height,
                        Bitmap.Config.ARGB_8888
                    )
                    bitmap.copyPixelsFromBuffer(buffer)
                    image.close()

                    val croppedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height)
                    if (croppedBitmap !== bitmap) bitmap.recycle()

                    val base64 = bitmapToBase64(croppedBitmap)
                    croppedBitmap.recycle()
                    release()
                    onCaptured(base64)
                } else {
                    release()
                    onError("Failed to capture screen image")
                }
            } catch (e: Exception) {
                release()
                onError("Screen capture error: ${e.message}")
            } finally {
                imageReader.close()
            }
        }, 300)
    }

    fun release() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    companion object {
        fun bitmapToBase64(bitmap: Bitmap): String {
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 70, stream)
            return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        }
    }
}
