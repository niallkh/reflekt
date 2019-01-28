package tech.khana.reflekt

import android.graphics.BitmapFactory
import android.os.Bundle
import android.support.v4.app.Fragment
import android.support.v7.app.AlertDialog
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.SeekBar
import kotlinx.android.synthetic.main.fragment_camera.*
import kotlinx.coroutines.*
import tech.khana.reflekt.capture.CaptureSaverJpg
import tech.khana.reflekt.core.SimpleReflekt
import tech.khana.reflekt.models.LensDirect
import tech.khana.reflekt.models.Settings
import tech.khana.reflekt.models.displayRotationOf
import tech.khana.reflekt.preview.ReflektPreview
import kotlin.coroutines.CoroutineContext

class CameraFragment : Fragment(), CoroutineScope {

    private val job = Job()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    private lateinit var preview: ReflektPreview

    private lateinit var camera: SimpleReflekt

    private var toggle = false

    private lateinit var settings: Settings

    private val captureSaver by lazy {
        CaptureSaverJpg(context!!.cacheDir) {
            AlertDialog.Builder(context!!).run {
                setView(ImageView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(WRAP_CONTENT, WRAP_CONTENT)
                    setImageBitmap(BitmapFactory.decodeFile(it.absolutePath))
                })
                show()
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_camera, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        preview = reflektCamera
//        preview.setOnClickListener {
//            launch {
//                toggle = toggle.not()
//                when (toggle) {
//                    true -> {
//                        cameraControls.animate()
//                            .alpha(1f)
//                            .setDuration(300)
//                            .withStartAction { cameraControls.visibility = View.VISIBLE }
//                    }
//                    false -> {
//                        cameraControls.animate()
//                            .alpha(0f)
//                            .setDuration(300)
//                            .withEndAction { cameraControls.visibility = View.INVISIBLE }
//                    }
//                }
//            }
//        }

        val rotation = displayRotationOf(requireActivity().windowManager.defaultDisplay.rotation)
        settings = Settings(
            surfaces = listOf(preview, captureSaver),
            displayRotation = rotation,
            lensDirect = LensDirect.FRONT
        )
        camera = SimpleReflekt(context!!, settings)

//        aspectRatioButton.setOnClickListener {
//            launch {
//                val aspectRatios = camera.availablePreviewAspectRatios()
//                AlertDialog.Builder(requireActivity()).apply {
//                    setTitle(R.string.pick_aspect_ratio)
//                    setItems(aspectRatios.map { it.name }.toTypedArray()) { _, id ->
//                        this@CameraFragment.launch {
//                            camera.previewAspectRatio(aspectRatios[id])
//                        }
//                    }
//                    show()
//                }
//            }
//        }

        switchButton.setOnClickListener {
            launch {
                camera.switchLens()
            }
        }

//        flashButton.setOnClickListener {
//            launch {
//                val flashModes = camera.availableFlashModes()
//                AlertDialog.Builder(requireActivity()).apply {
//                    setTitle(R.string.pick_lens_direct)
//                    setItems(flashModes.map { it.name }.toTypedArray()) { _, id ->
//                        this@CameraFragment.launch {
//                            camera.flash(flashModes[id])
//                        }
//                    }
//                    show()
//                }
//            }
//        }

//        runBlocking {
//            val maxZoom = camera.maxZoom()
//            zoomSeekBar.onProgressChanged {
//                if (it % 5 == 0) {
//                    this@CameraFragment.launch {
//                        camera.zoom(maxZoom * it / zoomSeekBar.max)
//                    }
//                }
//            }
//        }

        shootButton.setOnClickListener {
            launch {
                camera.capture()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        launch {
            camera.start()
        }
    }

    override fun onPause() {
        super.onPause()
        runBlocking {
            camera.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        runBlocking {
            camera.release()
        }
        job.cancel()
    }

    companion object {

        const val TAG = "CameraFragment"

        fun newInstance() = CameraFragment()
    }
}

private fun SeekBar.onProgressChanged(f: (progress: Int) -> Unit) {
    setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
            f(progress)
        }

        override fun onStartTrackingTouch(seekBar: SeekBar?) {
        }

        override fun onStopTrackingTouch(seekBar: SeekBar?) {
        }
    })
}