package tech.khana.reflekt.core

import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CaptureRequest

internal interface ReflektDevice {

    suspend fun open()

    suspend fun startPreview()

    suspend fun stopPreview()

    suspend fun capture()

    suspend fun release()
}

interface ReflektCamera {

    suspend fun open()

    suspend fun startPreview()

    suspend fun stopPreview()

    suspend fun stop()

    suspend fun getAvailableLenses(): List<Lens>
}

interface ReflektSurface {

    val format: ReflektFormat

    suspend fun acquireSurface(config: SurfaceConfig): TypedSurface

    suspend fun stop() {
    }
}

interface SettingsProvider {

    val currentSettings: UserSettings

    fun flash(flashMode: FlashMode)

    fun supportLevel(supportLevel: SupportLevel)
}

interface RequestFactory {

    fun CameraDevice.createPreviewRequest(block: CaptureRequest.Builder.() -> Unit): CaptureRequest

    fun CameraDevice.createStillRequest(block: CaptureRequest.Builder.() -> Unit): CaptureRequest
}