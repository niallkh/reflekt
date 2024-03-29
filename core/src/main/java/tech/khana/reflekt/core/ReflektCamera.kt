package tech.khana.reflekt.core

import android.Manifest.permission.CAMERA
import android.content.Context
import android.hardware.camera2.*
import android.hardware.camera2.CameraDevice.*
import android.hardware.camera2.CaptureResult.*
import android.media.CamcorderProfile
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.content.ContextCompat
import android.support.v4.content.PermissionChecker.PERMISSION_GRANTED
import android.view.Surface
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import tech.khana.reflekt.ext.*
import tech.khana.reflekt.models.*
import tech.khana.reflekt.models.CameraMode.*
import tech.khana.reflekt.preferences.DefaultPreference
import tech.khana.reflekt.preferences.JpegPreference
import tech.khana.reflekt.preferences.ZoomPreference
import tech.khana.reflekt.utils.Logger
import tech.khana.reflekt.utils.debug
import tech.khana.reflekt.utils.error
import tech.khana.reflekt.utils.warn
import java.util.concurrent.TimeoutException

class ReflektCameraImpl(
    private val ctx: Context,
    private val handlerThread: HandlerThread,
    private val cameraPreferences: List<CameraPreference> = listOf(DefaultPreference, JpegPreference)
) : ReflektCamera, Logger by Logger.defaultLogger {

    private val cameraManager = ctx.cameraManager

    private val cameraDispatcher = Handler(handlerThread.looper).asCoroutineDispatcher("")

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var currentSurfaces: Map<CameraMode, List<Surface?>> = emptyMap()
    private var currentReflekts: List<ReflektSurface> = emptyList()

    private var pendingException: CameraException? = null
    private val cameraOpenMutex = Mutex()

    private val repeatingState = PreviewState()

    private val repeatingCallback = object : CameraCaptureSession.CaptureCallback() {

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            repeatingState.focused = result.get(CONTROL_AF_STATE)?.let { it == CONTROL_AF_STATE_PASSIVE_FOCUSED }
                ?: repeatingState.focused
            repeatingState.exposured = result.get(CONTROL_AE_STATE)?.let { it == CONTROL_AE_STATE_CONVERGED }
                ?: repeatingState.exposured
            repeatingState.balanced = result.get(CONTROL_AWB_STATE)?.let { it == CONTROL_AWB_STATE_CONVERGED }
                ?: repeatingState.balanced
        }

        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            repeatingState.reset()
        }
    }

    private val cameraDeviceCallback = object : CameraDevice.StateCallback() {

        override fun onOpened(camera: CameraDevice) {
            debug { "#onOpened" }
            cameraDevice = camera
            if (cameraOpenMutex.isLocked) cameraOpenMutex.unlock()
        }

        override fun onDisconnected(camera: CameraDevice) {
            debug { "#onDisconnected" }
            camera.close()
        }

        override fun onError(camera: CameraDevice, error: Int) {
            warn { "#onError" }
            pendingException = cameraExceptionByErrorCode(error).also {
                warn { it.message ?: "" }
                error { it }
            }
            camera.close()
        }

        override fun onClosed(camera: CameraDevice) {
            debug { "#onClosed" }
            cameraDevice = null
            if (cameraOpenMutex.isLocked) cameraOpenMutex.unlock()
        }
    }

    override suspend fun open(lensDirect: LensDirect) = withContext(cameraDispatcher) {
        debug { "#open" }
        pendingException?.let { throw it }
        check(captureSession == null) { "session is not closed" }
        check(cameraDevice == null) { "camera already is opened" }

        val id = cameraManager.findCameraByLens(lensDirect)

        ZoomPreference.sensorRect = cameraManager.sensorRect(id)

        if (ContextCompat.checkSelfPermission(ctx, CAMERA) == PERMISSION_GRANTED) {
            try {
                cameraManager.openCamera(id, cameraDeviceCallback, Handler(handlerThread.looper))
            } catch (e: Exception) {
                warn { e.message ?: "" }
                error { e }
                throw e
            }
        } else {
            throw CameraException.CameraPermissionRequired()
        }

        cameraOpenMutex.lockSelf()
        pendingException?.let { throw it }
        Unit
    }

    override suspend fun startSession(
        reflektSurfaces: List<ReflektSurface>,
        displayRotation: Rotation,
        displayResolution: Resolution,
        aspectRatio: AspectRatio
    ) = withContext(cameraDispatcher) {
        debug { "#startSession" }
        val cameraDevice = cameraDevice

        pendingException?.let { throw it }
        require(reflektSurfaces.isNotEmpty()) { "reflektSurfaces is empty" }
        check(cameraDevice != null) { "camera is not opened" }
        check(captureSession == null) { "session is not closed" }

        val hardwareRotation = rotationOf(cameraManager.hardwareRotation(cameraDevice.id))
        JpegPreference.hardwareRotation = hardwareRotation
        JpegPreference.displayRotation = displayRotation

        val supportedLevel = cameraManager.supportedLevel(cameraDevice.id)
        debug { "#supported level: ${supportedLevel.description}" }
        val outputConfigurator = DefaultSurfaceOutputConfigurator(supportedLevel, reflektSurfaces)

        val surfacesByMode = reflektSurfaces.map { reflektSurface ->

            val outputResolutions = when (val format = reflektSurface.format) {
                is ReflektFormat.Image -> cameraManager.outputResolutions(
                    cameraDevice.id, format.format
                )
                is ReflektFormat.Priv -> cameraManager.outputResolutions(
                    cameraDevice.id, format.klass
                )
                is ReflektFormat.None -> return@map null
            }

            val maxRecordResolution = CamcorderProfile.get(cameraDevice.id.toInt(), CamcorderProfile.QUALITY_HIGH).let {
                Resolution(it.videoFrameWidth, it.videoFrameHeight)
            }

            val filteredOutputResolutionsByOutputType = when (outputConfigurator.defineOutputType(reflektSurface)) {
                OutputType.MAXIMUM -> outputResolutions
                OutputType.RECORD -> outputResolutions.asSequence()
                    .filter { it.width <= maxRecordResolution.width }
                    .filter { it.height <= maxRecordResolution.height }
                    .toList()
                OutputType.PREVIEW -> outputResolutions.asSequence()
                    .filter { it.width <= displayResolution.width }
                    .filter { it.height <= displayResolution.height }
                    .filter { it.width <= MAX_PREVIEW_WIDTH }
                    .filter { it.height <= MAX_PREVIEW_HEIGHT }
                    .toList()
            }

            val surfaceConfig = SurfaceConfig(
                filteredOutputResolutionsByOutputType,
                aspectRatio,
                displayRotation,
                hardwareRotation,
                cameraManager.directCamera(cameraDevice.id)
            )

            async {
                withTimeoutOrNull(500) {
                    reflektSurface to reflektSurface.acquireSurface(surfaceConfig)
                } ?: throw TimeoutException("can't get surface from $reflektSurface")
            }
        }
            .mapNotNull { it }
            .map { it.await() }
            .flatMap { (reflekt, surface) ->
                reflekt.supportedModes.map { it to surface }
            }
            .groupBy(keySelector = { it.first }, valueTransform = { it.second })

        val surfaces = surfacesByMode.values.flatten().mapNotNull { it }.distinct()
        captureSession = cameraDevice.createCaptureSession(surfaces, handlerThread)
        currentSurfaces = surfacesByMode
        currentReflekts = reflektSurfaces
    }

    override suspend fun startPreview() = withContext(cameraDispatcher) {
        debug { "#startPreview" }
        val session = captureSession
        val surfaces = currentSurfaces
        pendingException?.let { throw it }
        check(cameraDevice != null) { "camera is not opened" }
        check(session != null) { "session is not started" }

        val previewSurfaces = surfaces[PREVIEW]?.mapNotNull { it }
        check(previewSurfaces != null && previewSurfaces.isNotEmpty()) { "preview surfaces is empty" }

        val request = session.device.createCaptureRequest(TEMPLATE_PREVIEW).run {
            addAllSurfaces(previewSurfaces)
            cameraPreferences.forEach { with(it) { apply(PREVIEW) } }
            build()
        }

        session.setRepeatingRequest(request, handlerThread, repeatingCallback)
        delay(150)

        currentReflekts.filter { PREVIEW in it.supportedModes }.forEach { it.onStart(PREVIEW) }
    }

    override suspend fun capture() = withContext(cameraDispatcher) {
        debug { "#capture" }
        val session = captureSession
        val surfaces = currentSurfaces
        pendingException?.let { throw it }
        check(cameraDevice != null) { "camera is not opened" }
        check(session != null) { "session is not started" }
        val previewSurfaces = surfaces[PREVIEW]?.mapNotNull { it }
        val captureSurfaces = surfaces[CAPTURE]?.mapNotNull { it }
        check(previewSurfaces != null && previewSurfaces.isNotEmpty()) { "preview surfaces is empty" }
        check(captureSurfaces != null && captureSurfaces.isNotEmpty()) { "capture surfaces is empty" }

        val request = session.device.createCaptureRequest(TEMPLATE_STILL_CAPTURE).run {
            addAllSurfaces(captureSurfaces)
            cameraPreferences.forEach { with(it) { apply(CAPTURE) } }
            build()
        }

        currentReflekts.filter { CAPTURE in it.supportedModes }.forEach { it.onStart(CAPTURE) }

        session.capture(request, handlerThread)

        currentReflekts.filter { CAPTURE in it.supportedModes }.forEach { it.onStop(CAPTURE) }
    }

    override suspend fun trigger3A() = withContext(cameraDispatcher) {
        debug { "#trigger3A" }
        val session = captureSession
        val surfaces = currentSurfaces
        pendingException?.let { throw it }
        check(cameraDevice != null) { "camera is not opened" }
        check(session != null) { "session is not started" }
        val previewSurfaces = surfaces[PREVIEW]?.mapNotNull { it }
        check(previewSurfaces != null && previewSurfaces.isNotEmpty()) { "preview surfaces is empty" }

        var focusComplete = cameraManager.supportFocus(session.device.id).not() || repeatingState.focused
        var exposureComplete = cameraManager.supportExposure(session.device.id).not() || repeatingState.exposured
        var awbStateComplete = cameraManager.supportAwb(session.device.id).not() || repeatingState.balanced

        trigger@ do {
            val channel = session.trigger3A(this, handlerThread, previewSurfaces)
            if (focusComplete && exposureComplete && awbStateComplete) break@trigger
            for ((key, result) in channel) {
                when (key) {
                    CONTROL_AF_STATE -> {
                        if (result != CONTROL_AF_STATE_INACTIVE)
                            focusComplete = result == CONTROL_AF_STATE_FOCUSED_LOCKED
                    }
                    CONTROL_AE_STATE -> {
                        if (result != CONTROL_AE_STATE_INACTIVE)
                            exposureComplete = result == CONTROL_AE_STATE_CONVERGED
                    }
                    CONTROL_AWB_STATE -> {
                        if (result != CONTROL_AWB_STATE_INACTIVE)
                            awbStateComplete = result == CONTROL_AWB_STATE_CONVERGED
                    }
                    else -> error("unknown key")
                }

                if (focusComplete && exposureComplete && awbStateComplete) break@trigger
            }
        } while (focusComplete.not() || exposureComplete.not() || awbStateComplete.not())
    }

    override suspend fun lock3A() = withContext(cameraDispatcher) {
        debug { "#lock3A" }
        val session = captureSession
        val surfaces = currentSurfaces
        pendingException?.let { throw it }
        check(cameraDevice != null) { "camera is not opened" }
        check(session != null) { "session is not started" }
        val previewSurfaces = surfaces[PREVIEW]?.mapNotNull { it }
        check(previewSurfaces != null && previewSurfaces.isNotEmpty()) { "preview surfaces is empty" }

        session.lock3A(previewSurfaces)
    }

    override suspend fun unlock3A() = withContext(cameraDispatcher) {
        debug { "#unlock3A" }
        val session = captureSession
        val surfaces = currentSurfaces
        pendingException?.let { throw it }
        check(cameraDevice != null) { "camera is not opened" }
        check(session != null) { "session is not started" }
        val previewSurfaces = surfaces[PREVIEW]?.mapNotNull { it }
        check(previewSurfaces != null && previewSurfaces.isNotEmpty()) { "preview surfaces is empty" }

        session.unlock3A(previewSurfaces)
    }

    override suspend fun startRecord() = withContext(cameraDispatcher) {
        debug { "#startRecord" }
        val session = captureSession
        val surfaces = currentSurfaces
        pendingException?.let { throw it }
        check(cameraDevice != null) { "camera is not opened" }
        check(session != null) { "session is not started" }
        val recordSurfaces = surfaces[RECORD]?.mapNotNull { it }
        check(recordSurfaces != null && recordSurfaces.isNotEmpty()) { "record surfaces is empty" } // FIXME

        val request = session.device.createCaptureRequest(TEMPLATE_RECORD).run {
            addAllSurfaces(recordSurfaces)
            cameraPreferences.forEach { with(it) { apply(RECORD) } }
            build()
        }

        session.setRepeatingRequest(request, handlerThread, repeatingCallback)

        currentReflekts.filter { RECORD in it.supportedModes }.forEach { it.onStart(RECORD) }

        Unit
    }

    override suspend fun stopRecord() = withContext(cameraDispatcher) {
        debug { "#stopRecord" }
        repeatingState.reset()
        captureSession?.abortCaptures()
        captureSession?.stopRepeating()
        currentReflekts.filter { RECORD in it.supportedModes }.forEach { it.onStop(RECORD) }
    }


    override suspend fun stopPreview() = withContext(cameraDispatcher) {
        debug { "#stopPreview" }
        repeatingState.reset()
        captureSession?.abortCaptures()
        captureSession?.stopRepeating()
        currentReflekts.filter { PREVIEW in it.supportedModes }.forEach { it.onStop(PREVIEW) }
    }


    override suspend fun stopSession() = withContext(cameraDispatcher) {
        debug { "#stopSession" }
        stopPreview()
        stopRecord()
        captureSession?.close()
        captureSession = null
    }

    override suspend fun close() = withContext(cameraDispatcher) {
        debug { "#close" }
        stopSession()
        cameraDevice?.close()
        cameraDevice = null
        Unit
    }
}

data class PreviewState(
    var focused: Boolean = false,
    var exposured: Boolean = false,
    var balanced: Boolean = false
) {
    fun reset() {
        focused = false
        exposured = false
        balanced = false
    }
}