package tech.khana.reflekt.preview

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.View.MeasureSpec.getSize
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import android.widget.TextView
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import tech.khana.reflekt.core.*
import tech.khana.reflekt.core.AspectRatio.AR_16X9
import tech.khana.reflekt.preview.Side.HEIGHT
import tech.khana.reflekt.preview.Side.WIDTH


const val MAX_PREVIEW_WIDTH = 1920
const val MAX_PREVIEW_HEIGHT = 1080

class ReflektPreview constructor(
    ctx: Context,
    attrs: AttributeSet? = null
) : TextureView(ctx, attrs), ReflektSurface {

    private var previewRotation = Rotation._0
    private var previewAspectRatio: AspectRatio = AR_16X9

    override val format: ReflektFormat = ReflektFormat.Clazz.Texture

    private val layoutMutex = Mutex()

    private val textureMatrix = Matrix()

    private val textureView = TextureView(context).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }
    private val placeHolder = View(context).apply {
        layoutParams = ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)
    }

//    init {
//        addView(textureView)
//    }

    override suspend fun acquireSurface(config: SurfaceConfig): TypedSurface = coroutineScope {
        previewLogger.debug { "#acquireSurface" }
        withContext(Dispatchers.Main) {
            val previewResolution = config.resolutions
                .chooseOptimalResolution(config.previewAspectRatio)
            this@ReflektPreview.previewAspectRatio = config.previewAspectRatio
            previewRotation = config.rotation

            requestLayout()
            layoutMutex.twiceLock()

            val surfaceTexture = onSurfaceTextureAvailable()
            surfaceTexture.setDefaultBufferSize(
                previewResolution.width, previewResolution.height
            )

            previewLogger.debug { "#acquireSurface acquired" }
            TypedSurface(SurfaceType.PREVIEW, Surface(surfaceTexture))
        }
    }

    private fun List<Resolution>.chooseOptimalResolution(aspectRatio: AspectRatio): Resolution =
        asSequence()
            .filter { it.ratio == aspectRatio.value }
            .filter { it.width <= MAX_PREVIEW_WIDTH }
            .filter { it.height <= MAX_PREVIEW_HEIGHT }
            .sortedBy { it.area }
            .last()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        previewLogger.debug { "#onMeasure" }

        val width = getSize(widthMeasureSpec)
        val height = getSize(heightMeasureSpec)

        val previewAspectRatio = when (previewRotation) {
            Rotation._0, Rotation._180 -> previewAspectRatio.value
            Rotation._90, Rotation._270 -> 1f / previewAspectRatio.value
        }

        when {
            layoutParams.width == MATCH_PARENT && layoutParams.height == MATCH_PARENT -> {
                val viewAspectRation = width.toFloat() / height.toFloat()
                if (viewAspectRation <= previewAspectRatio) {
                    onMeasureByMatchParent(width, height, previewAspectRatio, HEIGHT)
                } else {
                    onMeasureByMatchParent(width, height, previewAspectRatio, WIDTH)
                }
            }
            layoutParams.height == MATCH_PARENT ->
                onMeasureByMatchParent(width, height, previewAspectRatio, HEIGHT)
            layoutParams.width == MATCH_PARENT ->
                onMeasureByMatchParent(width, height, previewAspectRatio, WIDTH)
            else -> throw IllegalStateException("unknown layout settings")
        }
    }

    private fun onMeasureByMatchParent(
        width: Int, height: Int, aspectRatio: Float, side: Side
    ) = when (side) {
        HEIGHT -> {
            val newWidth = (height / aspectRatio).toInt()
            setTransform(textureMatrix.apply {
                reset()
                if (newWidth > width) {
                    postTranslate(-(newWidth - width) / 2f, 0f)
//                    postScale(0.5f, 0.5f, width / 2f, height / 2f)
                }
            })

            previewLogger.debug {
                "#setMeasuredDimension(newWidth=$newWidth, height=$height)"
            }
            setMeasuredDimension(newWidth, height)
        }
        WIDTH -> {
            val newHeight = (width * aspectRatio).toInt()
            setTransform(textureMatrix.apply {
                reset()
                if (newHeight > height) {
                    postTranslate(0f, -(newHeight - height) / 2f)
//                    postScale(0.5f, 0.5f, width / 2f, height / 2f)
                }
            })

            previewLogger.debug {
                "#setMeasuredDimension(width=$width, newHeight=$newHeight)"
            }
            setMeasuredDimension(width, newHeight)
        }
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        previewLogger.debug { "#onLayout" }
        super.onLayout(changed, left, top, right, bottom)
        if (layoutMutex.isLocked) {
            layoutMutex.unlock()
        }
    }


//    private fun configureTransform(
//        screenResolution: Resolution,
//        previewResolution: Resolution,
//        rotation: Rotation
//    ) {
//        textureView.setTransform(Matrix().apply {
//
//            val centerX = screenResolution.width * 0.5f
//            val centerY = screenResolution.height * 0.5f
//
//            when (rotation) {
//                Rotation._0 -> {
//                }
//                Rotation._90 -> {
//                    val scale =
//                        previewResolution.width.toFloat() / previewResolution.height.toFloat()
//                    postScale(1 / scale, scale, centerX, centerY)
//                    postRotate(270f, centerX, centerY)
//                }
//                Rotation._180 -> {
//                    postRotate(180f, centerX, centerY)
//                }
//                Rotation._270 -> {
//                    val scale =
//                        previewResolution.width.toFloat() / previewResolution.height.toFloat()
//                    postScale(1 / scale, scale, centerX, centerY)
//                    postRotate(90f, centerX, centerY)
//                }
//            }
//
//            val tempRatio = screenResolution.width.toFloat() / screenResolution.height
//            val screenWidth = if (tempRatio > 1) screenResolution.width else screenResolution.height
//            val screenHeight =
//                if (tempRatio > 1) screenResolution.height else screenResolution.width
//            val screenAspectRatio: Float = screenWidth.toFloat() / screenHeight.toFloat()
//            val previewAspectRatio: Float =
//                previewResolution.width.toFloat() / previewResolution.height.toFloat()
//
//            val scaleFactor: Float = screenAspectRatio / previewAspectRatio
//
//            val heightCorrection = when (rotation) {
//                Rotation._0, Rotation._180 -> (screenHeight.toFloat() * scaleFactor -
//                        screenHeight.toFloat()) / 2f
//                Rotation._90, Rotation._270 -> (screenWidth.toFloat() * scaleFactor -
//                        screenWidth.toFloat()) / 2f
//            }
//
//            postScale(scaleFactor, 1f)
//            postTranslate(-heightCorrection, 0f)
//
//            if (scaleFactor < 1f) {
//                postScale(1f / scaleFactor, 1f / scaleFactor)
//                postTranslate(heightCorrection, heightCorrection)
//            }
//        })
//    }

//    private inner class TextureListener(
//        val listener: SurfaceListener,
//        val previewResolution: Resolution,
//        val rotation: Rotation
//    ) : TextureView.SurfaceTextureListener {
//
//        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
////            Log.d(TAG, "#onSurfaceTextureAvailable")
//            val screenResolution = Resolution(width, height)
//            surface.setDefaultBufferSize(previewResolution.width, previewResolution.height)
//            configureTransform(screenResolution, previewResolution, rotation)
//            listener(TypedSurface(SurfaceType.PREVIEW, Surface(surface)))
//        }
//
//        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
////            Log.d(TAG, "#onSurfaceTextureSizeChanged")
//            val screenResolution = Resolution(width, height)
//            configureTransform(screenResolution, previewResolution, rotation)
//        }
//
//        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
////            Log.d(TAG, "#onSurfaceTextureUpdated")
//        }
//
//        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
////            Log.d(TAG, "#onSurfaceTextureDestroyed")
//            return false
//        }
//    }
}

internal val previewLogger = object : Tag {

    override val tag: String = "ReflektPreview"

    override val level: LogLevel = LogLevel.DEFAULT
}

private suspend fun Mutex.twiceLock() {
    lock()
    lock()
}

private enum class Side {
    HEIGHT,
    WIDTH
}