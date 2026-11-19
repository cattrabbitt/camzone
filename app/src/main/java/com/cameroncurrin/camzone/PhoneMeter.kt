package com.cameroncurrin.camzone

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.pow

/**
 * Turns the Pixel's camera into a reflected light meter.
 * Every frame we know the phone's own aperture, shutter time and ISO, so each pixel can be
 * converted to a scene EV. The tone curve is forced to a plain 2.2 gamma when the phone allows it,
 * so pixel values can be turned back into linear light.
 */
class PhoneMeter(private val context: Context) {

    private data class FrameExposure(val aperture: Double, val seconds: Double, val iso: Double)

    private val exposures = object : LinkedHashMap<Long, FrameExposure>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FrameExposure>?) = size > 12
    }
    private var lastExposure: FrameExposure? = null
    private var gamma = 2.2
    private var fallbackAperture = 1.7
    private val executor = Executors.newSingleThreadExecutor()

    private val grid = 72 // cells along the short side

    @androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()

            val previewBuilder = Preview.Builder().setResolutionSelector(selector)
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            val tonemap = chooseTonemap()
            for (builder in listOf(Camera2Interop.Extender(previewBuilder), Camera2Interop.Extender(analysisBuilder))) {
                when (tonemap) {
                    CaptureRequest.TONEMAP_MODE_GAMMA_VALUE -> {
                        builder.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                        builder.setCaptureRequestOption(CaptureRequest.TONEMAP_GAMMA, 2.2f)
                    }
                    else -> Unit
                }
            }
            gamma = 2.2 // gamma mode, or the standard curve treated as roughly 2.2
            Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                    val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
                    val ap = result.get(CaptureResult.LENS_APERTURE)?.toDouble() ?: fallbackAperture
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                    val e = FrameExposure(ap, t / 1e9, iso * boost / 100.0)
                    synchronized(exposures) {
                        exposures[ts] = e
                        lastExposure = e
                    }
                }
            })

            val preview = previewBuilder.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = analysisBuilder.build().also { it.setAnalyzer(executor) { img -> analyze(img) } }
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun chooseTonemap(): Int? {
        return try {
            val mgr = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = mgr.cameraIdList.first { id ->
                mgr.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            }
            val ch = mgr.getCameraCharacteristics(id)
            ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let { fallbackAperture = it.toDouble() }
            val modes = ch.get(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
            if (modes.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)) CaptureRequest.TONEMAP_MODE_GAMMA_VALUE else null
        } catch (e: Exception) {
            null
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private var lastPublish = 0L

    private fun analyze(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastPublish < 90) return // about 10 updates a second is plenty
            lastPublish = now
            val exp = synchronized(exposures) { exposures[img.imageInfo.timestamp] ?: lastExposure } ?: return
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = img.width
            val h = img.height
            val rot = img.imageInfo.rotationDegrees

            // Sensor-oriented grid (landscape): gw x gh
            val gh = grid
            val gw = (grid * w.toFloat() / h).toInt()
            val frameEv = Expo.ev100(exp.aperture, exp.seconds, exp.iso)
            val sensorEv = FloatArray(gw * gh)
            val sensorClip = BooleanArray(gw * gh)
            for (gy in 0 until gh) {
                for (gx in 0 until gw) {
                    // average a 3x3 sample inside the cell
                    var sum = 0.0
                    var clip = false
                    for (sy in 0 until 3) for (sx in 0 until 3) {
                        val px = ((gx + (sx + 0.5) / 3.0) * w / gw).toInt().coerceIn(0, w - 1)
                        val py = ((gy + (sy + 0.5) / 3.0) * h / gh).toInt().coerceIn(0, h - 1)
                        val v = buf.get(py * rowStride + px * pixStride).toInt() and 0xFF
                        if (v >= 250) clip = true
                        sum += (v / 255.0).pow(gamma)
                    }
                    val lin = max(sum / 9.0, 1e-5)
                    sensorEv[gy * gw + gx] = (frameEv + Expo.log2(lin / 0.18)).toFloat()
                    sensorClip[gy * gw + gx] = clip
                }
            }

            // Rotate to upright
            val (ow, oh) = if (rot == 90 || rot == 270) gh to gw else gw to gh
            val ev = FloatArray(ow * oh)
            val clip = BooleanArray(ow * oh)
            for (y in 0 until gh) for (x in 0 until gw) {
                val (nx, ny) = when (rot) {
                    90 -> (gh - 1 - y) to x
                    180 -> (gw - 1 - x) to (gh - 1 - y)
                    270 -> y to (gw - 1 - x)
                    else -> x to y
                }
                ev[ny * ow + nx] = sensorEv[y * gw + x]
                clip[ny * ow + nx] = sensorClip[y * gw + x]
            }
            AppState.frame.value = ZoneFrame(ow, oh, ev, clip)
        } finally {
            img.close()
        }
    }

    /** Average scene EV around a point (0..1 coordinates), in linear light. */
    fun sample(frame: ZoneFrame, x: Float, y: Float, radius: Int = 2): Double {
        val cx = (x * frame.width).toInt().coerceIn(0, frame.width - 1)
        val cy = (y * frame.height).toInt().coerceIn(0, frame.height - 1)
        var sum = 0.0
        var n = 0
        for (dy in -radius..radius) for (dx in -radius..radius) {
            val px = (cx + dx).coerceIn(0, frame.width - 1)
            val py = (cy + dy).coerceIn(0, frame.height - 1)
            sum += 2.0.pow(frame.ev[py * frame.width + px].toDouble())
            n++
        }
        return Expo.log2(sum / n)
    }
}
