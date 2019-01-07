package tech.khana.reflekt.core

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest

internal interface ReflektDevice {

    val cameraId: String

    suspend fun startSession(surfaces: List<TypedSurface>)

    suspend fun startPreview()

    suspend fun stopPreview()

    suspend fun capture()

    suspend fun stopSession()

    suspend fun release()
}

interface ReflektCamera {

    suspend fun open()

    suspend fun startSession()

    suspend fun stopSession()

    suspend fun startPreview()

    suspend fun stopPreview()

    suspend fun close()

    suspend fun previewAspectRatio(aspectRatio: AspectRatio)

    suspend fun availablePreviewAspectRatios(): List<AspectRatio>

    suspend fun getAvailableLenses(): List<Lens>
}

interface ReflektSurface {

    val format: ReflektFormat

    suspend fun acquireSurface(config: SurfaceConfig): TypedSurface
}

interface SettingsProvider {

    val currentSettings: UserSettings

    suspend fun flash(flashMode: FlashMode)

    suspend fun supportLevel(supportLevel: SupportLevel)

    suspend fun sessionActive(active: Boolean)

    suspend fun previewActive(active: Boolean)

    suspend fun previewAspectRation(aspectRatio: AspectRatio)
}

interface RequestFactory {

    fun CameraDevice.createPreviewRequest(block: CaptureRequest.Builder.() -> Unit): CaptureRequest

    fun CameraDevice.createStillRequest(block: CaptureRequest.Builder.() -> Unit): CaptureRequest
}