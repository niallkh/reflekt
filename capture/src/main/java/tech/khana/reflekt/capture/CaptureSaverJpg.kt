package tech.khana.reflekt.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.support.media.ExifInterface
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import tech.khana.reflekt.core.ReflektSurface
import tech.khana.reflekt.models.*
import tech.khana.reflekt.utils.REFLEKT_TAG
import java.io.File
import java.io.FileOutputStream

class CaptureSaverJpg(
    private val folder: File,
    private val handlerThread: HandlerThread = HandlerThread(REFLEKT_TAG).apply { start() },
    private val photoListener: (File) -> Unit
) : ReflektSurface, ImageReader.OnImageAvailableListener {

    private val captureDispatcher = Handler(handlerThread.looper).asCoroutineDispatcher(REFLEKT_TAG)

    private var imageReader: ImageReader? = null

    override val format = ReflektFormat.Image.Jpeg

    private var lensDirect = LensDirect.BACK

    override suspend fun acquireSurface(config: SurfaceConfig): CameraSurface = coroutineScope {
        withContext(captureDispatcher) {

            lensDirect = config.lensDirect

            val resolution = config.resolutions.chooseOptimalResolution(config.aspectRatio)

            imageReader?.close()

            val imageReader = ImageReader.newInstance(resolution.width, resolution.height, format.format, 2).apply {
                setOnImageAvailableListener(this@CaptureSaverJpg, Handler(handlerThread.looper))
            }

            this@CaptureSaverJpg.imageReader = imageReader

            CameraSurface(CameraMode.CAPTURE, imageReader.surface)
        }
    }

    private fun List<Resolution>.chooseOptimalResolution(aspectRatio: AspectRatio): Resolution =
        asSequence()
            .filter { it.ratio == aspectRatio.value }
            .sortedBy { it.area }
            .last()

    override fun onImageAvailable(reader: ImageReader) {
        reader.acquireLatestImage().use { image ->
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.remaining())
            buffer.get(bytes)

            val srcBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)

            val neededRotation = rotationOf(bytes.inputStream().use {
                ExifInterface(it).run {
                    rotationDegrees
                }
            })

            val matrix = Matrix().apply {
                setRotate(neededRotation.value.toFloat())
                if (lensDirect == LensDirect.FRONT) {
                    when (neededRotation) {
                        Rotation._0, Rotation._180 -> postScale(1f, -1f)
                        Rotation._90, Rotation._270 -> postScale(-1f, 1f)
                    }
                }
            }
            val rotatedBitmap = Bitmap.createBitmap(
                srcBitmap, 0, 0, srcBitmap.width, srcBitmap.height, matrix, false
            )

            val imageFile = File(folder, "${image.timestamp}.jpg")
            FileOutputStream(imageFile).use { fos ->
                rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, fos)
            }

            ExifInterface(imageFile.absolutePath).run {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
                saveAttributes()
            }

            photoListener(imageFile)
        }
    }

    override suspend fun release() = coroutineScope {
        withContext(captureDispatcher) {
            handlerThread.quit()
            imageReader?.close()
            Unit
        }
    }
}
