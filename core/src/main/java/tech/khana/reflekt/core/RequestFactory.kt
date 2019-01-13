package tech.khana.reflekt.core

import android.graphics.Rect
import android.hardware.camera2.*
import android.os.Build
import android.support.annotation.RequiresApi

internal class RequestFactoryImpl(
    private val cameraManager: CameraManager,
    private val settingsProvider: SettingsProvider
) : RequestFactory {

    private val settings: ReflektSettings
        get() = settingsProvider.currentSettings

    override fun CameraDevice.createPreviewRequest(
        block: CaptureRequest.Builder.() -> Unit
    ): CaptureRequest = createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        .apply { block() }
        .apply {
            // val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
            val settings = settings
            when (settings.flashMode) {
                FlashMode.TORCH -> {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                }
                else -> {
                    set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                }
            }

            when (settings.zoom) {
                1f -> {
                }
                else -> {
                    val activeArraySize = settings.sensorRect
                    val width = Math.floor(activeArraySize.width().toDouble() / settings.zoom).toInt()
                    val left = (activeArraySize.width() - width) / 2
                    val height = Math.floor(activeArraySize.height().toDouble() / settings.zoom).toInt()
                    val top = (activeArraySize.height() - height) / 2
                    val croppedRect = Rect(left, top, left + width, top + height)
                    set(CaptureRequest.SCALER_CROP_REGION, croppedRect)
                }
            }

            set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
            set(CaptureRequest.CONTROL_AE_ANTIBANDING_MODE, CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_AUTO)
        }
        .build()

    override fun CameraDevice.createStillRequest(
        block: CaptureRequest.Builder.() -> Unit
    ): CaptureRequest = createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        .apply { block() }
        .apply {
            // val cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraDevice.id)
            val settings = settings
            set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
//            set(CaptureRequest.FLASH_MODE, settings.flashMode.value)
        }
        .build()

    private fun CaptureRequest.Builder.setPreviewSceneMode(cameraCharacteristics: CameraCharacteristics) {
//        val scenes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES)!!
//        val mode = ControlMode.values().first { modes.contains(it.value) }
//        Log.d(TAG, "AE: ${mode.desc}")
//        set(CaptureRequest.CONTROL_MODE, mode.value)
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun CaptureRequest.Builder.setPreviewControlMode(cameraCharacteristics: CameraCharacteristics) {
        val modes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AVAILABLE_MODES)!!
        val mode = ControlMode.values().first { modes.contains(it.value) }
        set(CaptureRequest.CONTROL_MODE, mode.value)
    }

    private fun CaptureRequest.Builder.setPreviewAfMode(cameraCharacteristics: CameraCharacteristics) {
        val afModes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)!!
        val afMode = AutoFocusMode.values().first { afModes.contains(it.value) }
        set(CaptureRequest.CONTROL_AF_MODE, afMode.value)
    }

    private fun CaptureRequest.Builder.setPreviewAeMode(cameraCharacteristics: CameraCharacteristics) {
        val aeModes = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)!!
        val aeMode = AutoExposureMode.values().first { aeModes.contains(it.value) }
        set(CaptureRequest.CONTROL_AE_MODE, aeMode.value)
    }

    private fun CaptureRequest.Builder.setPreviewAntibandingMode(cameraCharacteristics: CameraCharacteristics) {
        val aeAntiBandingModes = cameraCharacteristics
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_ANTIBANDING_MODES)!!
        val aeAntiBandingMode =
            AntiBandingMode.values().first { aeAntiBandingModes.contains(it.value) }
        set(CaptureRequest.CONTROL_AE_MODE, aeAntiBandingMode.value)
    }

    private enum class ControlMode(val value: Int, val desc: String) {
        AUTO(CameraMetadata.CONTROL_MODE_AUTO, "CONTROL_MODE_AUTO"),
        SCENE(CameraMetadata.CONTROL_MODE_USE_SCENE_MODE, "CONTROL_MODE_USE_SCENE_MODE"),
        KEEP_STATE(CameraMetadata.CONTROL_MODE_OFF_KEEP_STATE, "CONTROL_MODE_OFF_KEEP_STATE"),
        OFF(CameraMetadata.CONTROL_MODE_OFF, "CONTROL_MODE_OFF"),
    }

    private enum class AutoFocusMode(val value: Int, val desc: String) {
        PICTURE(
            CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE,
            "CONTROL_AF_MODE_CONTINUOUS_PICTURE"
        ),
        AUTO(CameraMetadata.CONTROL_AF_MODE_AUTO, "CONTROL_AF_MODE_AUTO"),
        OFF(CameraMetadata.CONTROL_AF_MODE_OFF, "CONTROL_AF_MODE_OFF"),
//        EDOF(CameraMetadata.CONTROL_AF_MODE_EDOF),
    }

    private enum class AutoExposureMode(val value: Int, val desc: String) {
        ON(CameraMetadata.CONTROL_AE_MODE_ON, "CONTROL_AE_MODE_ON"),
    }

    @Suppress("EnumEntryName")
    private enum class AntiBandingMode(val value: Int, val desc: String) {
        AUTO(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_AUTO, "CONTROL_AE_MODE_ON"),
        _50HZ(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_50HZ, "CONTROL_AE_ANTIBANDING_MODE_50HZ"),
        _60HZ(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_60HZ, "CONTROL_AE_ANTIBANDING_MODE_60HZ"),
        OFF(CameraMetadata.CONTROL_AE_ANTIBANDING_MODE_OFF, "CONTROL_AE_ANTIBANDING_MODE_OFF"),
    }
}