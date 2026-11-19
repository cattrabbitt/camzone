package com.cameroncurrin.camzone

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.TonemapCurve
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
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
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Turns the Pixel's camera into a reflected light meter that behaves like a manual camera.
 *
 * - Auto exposure is OFF. Shutter and ISO are set from the PYXIS settings, so the phone image
 *   only changes when the light (or the PYXIS settings) change, never because the phone moved.
 * - The phone sits [HEADROOM] stops darker than the PYXIS so its smaller sensor still sees
 *   highlights up to the PYXIS clip point.
 * - The tone curve is a plain 2.2 gamma, so pixel values turn back into linear light exactly.
 */
@androidx.annotation.OptIn(markerClass = [ExperimentalCamera2Interop::class])
class PhoneMeter(private val context: Context) {

    companion object {
        const val HEADROOM = 11.0 / 3.0
        const val GAMMA = 2.2
    }

    private data class FrameExposure(val aperture: Double, val seconds: Double, val iso: Double)

    /** Shown under the camera view so it's clear what the phone is doing. */
    val status = MutableStateFlow("Starting camera…")

    private val exposures = object : LinkedHashMap<Long, FrameExposure>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, FrameExposure>?) = size > 12
    }
    private var lastExposure: FrameExposure? = null
    private val executor = Executors.newSingleThreadExecutor()
    private var camera: Camera? = null
    private var owner: LifecycleOwner? = null
    private var previewView: PreviewView? = null
    private var boundLens: String? = null
    private var minFocusDiopters = 0f

    /** Back lenses on this phone: camera id to a readable label. */
    val lenses = MutableStateFlow<List<Pair<String, String>>>(emptyList())

    // Phone camera limits, read once
    private var aperture = 1.7
    private var minExposureNs = 100_000L
    private var maxExposureNs = 100_000_000L
    private var minIso = 50
    private var maxIso = 3200
    private var curveSupported = false
    private var gammaSupported = false
    private var maxCurvePoints = 32

    private val shortSide = 240 // zone map cells along the short side

    fun bind(owner: LifecycleOwner, previewView: PreviewView) {
        this.owner = owner
        this.previewView = previewView
        boundLens = AppState.phone.value.lensId
        rebind()
    }

    /** Binds the camera, using the lens chosen in the phone controls. */
    fun rebind() {
        val owner = owner ?: return
        val pv = previewView ?: return
        val providerFuture = ProcessCameraProvider.getInstance(context)
        providerFuture.addListener({
            val provider = providerFuture.get()
            listLenses(provider)
            val wanted = AppState.phone.value.lensId
            boundLens = wanted
            val camSelector = if (wanted == null) CameraSelector.DEFAULT_BACK_CAMERA else
                CameraSelector.Builder().addCameraFilter { infos ->
                    infos.filter { Camera2CameraInfo.from(it).cameraId == wanted }.ifEmpty { infos.take(1) }
                }.requireLensFacing(CameraSelector.LENS_FACING_BACK).build()

            val selector = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .build()
            val previewBuilder = Preview.Builder().setResolutionSelector(selector)
            val analysisBuilder = ImageAnalysis.Builder()
                .setResolutionSelector(selector)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)

            Camera2Interop.Extender(analysisBuilder).setSessionCaptureCallback(object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val t = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
                    val iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
                    val boost = result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST) ?: 100
                    val ap = result.get(CaptureResult.LENS_APERTURE)?.toDouble() ?: aperture
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: 0L
                    val manual = result.get(CaptureResult.CONTROL_AE_MODE) == CaptureResult.CONTROL_AE_MODE_OFF
                    val e = FrameExposure(ap, t / 1e9, iso * boost / 100.0)
                    synchronized(exposures) {
                        exposures[ts] = e
                        lastExposure = e
                    }
                    status.value = (if (manual) "Phone: manual" else "Phone: auto (manual not accepted)") +
                        " · 1/${(1e9 / t).roundToInt()} s · ISO ${(iso * boost / 100.0).roundToInt()} · f/${"%.2f".format(ap)}" +
                        if (curveSupported || gammaSupported) " · linear curve" else " · standard curve"
                }
            })

            val preview = previewBuilder.build().also { it.setSurfaceProvider(pv.surfaceProvider) }
            val analysis = analysisBuilder.build().also { it.setAnalyzer(executor) { img -> analyze(img) } }
            provider.unbindAll()
            val cam = provider.bindToLifecycle(owner, camSelector, preview, analysis)
            camera = cam
            readLimits(Camera2CameraInfo.from(cam.cameraInfo))
            applyManual(AppState.settings.value, AppState.calibration.value, AppState.phone.value)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun listLenses(provider: ProcessCameraProvider) {
        try {
            lenses.value = provider.availableCameraInfos
                .filter { it.lensFacing == CameraSelector.LENS_FACING_BACK }
                .map { info ->
                    val c = Camera2CameraInfo.from(info)
                    val f = c.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
                    val size = c.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
                    val eq = if (size != null && f > 0) (f * 43.27 / kotlin.math.hypot(size.width.toDouble(), size.height.toDouble())).roundToInt() else 0
                    c.cameraId to (if (eq > 0) "${eq}mm" else "Lens ${c.cameraId}")
                }
                .distinctBy { it.second }
                .sortedBy { it.second.removeSuffix("mm").toIntOrNull() ?: 999 }
        } catch (_: Exception) { }
    }

    private fun readLimits(info: Camera2CameraInfo) {
        try {
            info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)?.firstOrNull()?.let { aperture = it.toDouble() }
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let { minExposureNs = it.lower; maxExposureNs = it.upper }
            info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)?.let { minIso = it.lower; maxIso = it.upper }
            val modes = info.getCameraCharacteristic(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES) ?: intArrayOf()
            curveSupported = modes.contains(CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
            gammaSupported = modes.contains(CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
            maxCurvePoints = info.getCameraCharacteristic(CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS) ?: 32
            minFocusDiopters = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
        } catch (_: Exception) { }
    }

    /**
     * Sets the phone's shutter and ISO so it tracks the PYXIS, HEADROOM stops darker.
     * Call again whenever the PYXIS settings or the calibration change.
     */
    fun applyManual(s: CamSettings, calibration: Double, pc: PhoneControls) {
        if (pc.lensId != boundLens) { rebind(); return }
        val cam = camera ?: return
        // EV the phone must be set to: PYXIS middle gray lands at 0.18 * 2^-HEADROOM
        val evSet = Expo.grayEv(s) - calibration + HEADROOM
        val factor = aperture * aperture / 2.0.pow(evSet) // = t * ISO / 100
        // Prefer a clean ISO, then adjust the shutter; cap the shutter at 1/30 so the preview stays live
        val maxT = minOf(maxExposureNs / 1e9, 1.0 / 30)
        val minT = minExposureNs / 1e9
        var iso: Double
        var t: Double
        if (pc.matchPyxis) {
            iso = minIso.toDouble()
            t = factor * 100 / iso
            if (t > maxT) { t = maxT; iso = (factor * 100 / t).coerceIn(minIso.toDouble(), maxIso.toDouble()) }
            if (t < minT) t = minT
        } else {
            iso = pc.iso.toDouble().coerceIn(minIso.toDouble(), maxIso.toDouble())
            t = (pc.shutterAngle / 360.0 / pc.fps).coerceIn(minT, maxExposureNs / 1e9)
        }

        val b = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, (t * 1e9).toLong())
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso.roundToInt())
            .setCaptureRequestOption(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, 100)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, pc.wb.mode)
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_LOCK, pc.wb != Wb.AUTO)
        if (pc.autoFocus) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        } else {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, pc.focus * minFocusDiopters)
        }
        when {
            curveSupported -> {
                val n = maxCurvePoints.coerceIn(2, 64)
                val pts = FloatArray(n * 2)
                for (i in 0 until n) {
                    val x = i / (n - 1f)
                    pts[2 * i] = x
                    pts[2 * i + 1] = x.toDouble().pow(1.0 / GAMMA).toFloat()
                }
                b.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_CONTRAST_CURVE)
                b.setCaptureRequestOption(CaptureRequest.TONEMAP_CURVE, TonemapCurve(pts, pts, pts))
            }
            gammaSupported -> {
                b.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_GAMMA_VALUE)
                b.setCaptureRequestOption(CaptureRequest.TONEMAP_GAMMA, GAMMA.toFloat())
            }
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
    }

    private var lastPublish = 0L

    @SuppressLint("UnsafeOptInUsageError")
    private fun analyze(img: ImageProxy) {
        try {
            val now = System.currentTimeMillis()
            if (now - lastPublish < 90) return // about 10 updates a second
            lastPublish = now
            val exp = synchronized(exposures) { exposures[img.imageInfo.timestamp] ?: lastExposure } ?: return
            val plane = img.planes[0]
            val buf = plane.buffer
            val rowStride = plane.rowStride
            val pixStride = plane.pixelStride
            val w = img.width
            val h = img.height
            val rot = img.imageInfo.rotationDegrees

            val gh = shortSide
            val gw = (shortSide * w.toFloat() / h).roundToInt()
            val frameEv = Expo.ev100(exp.aperture, exp.seconds, exp.iso)
            // lookup: 8-bit code -> linear light relative to 0.18
            val lut = DoubleArray(256) { v -> max((v / 255.0).pow(GAMMA), 1e-6) / 0.18 }
            val sensorEv = FloatArray(gw * gh)
            val sensorClip = BooleanArray(gw * gh)
            val stepX = w.toFloat() / gw
            val stepY = h.toFloat() / gh
            for (gy in 0 until gh) {
                val py0 = (gy * stepY).toInt()
                val py1 = ((gy + 0.5f) * stepY).toInt().coerceAtMost(h - 1)
                for (gx in 0 until gw) {
                    val px0 = (gx * stepX).toInt()
                    val px1 = ((gx + 0.5f) * stepX).toInt().coerceAtMost(w - 1)
                    // 2x2 sample per cell, averaged in linear light
                    val a = buf.get(py0 * rowStride + px0 * pixStride).toInt() and 0xFF
                    val b = buf.get(py0 * rowStride + px1 * pixStride).toInt() and 0xFF
                    val c = buf.get(py1 * rowStride + px0 * pixStride).toInt() and 0xFF
                    val d = buf.get(py1 * rowStride + px1 * pixStride).toInt() and 0xFF
                    val lin = (lut[a] + lut[b] + lut[c] + lut[d]) / 4
                    sensorEv[gy * gw + gx] = (frameEv + Expo.log2(lin)).toFloat()
                    sensorClip[gy * gw + gx] = maxOf(a, b, c, d) >= 250
                }
            }

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
    fun sample(frame: ZoneFrame, x: Float, y: Float, radius: Int = 5): Double {
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
