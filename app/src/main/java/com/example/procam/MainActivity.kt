package com.example.procam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.provider.MediaStore
import android.provider.Settings
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.OrientationEventListener
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.View
import android.view.WindowManager
import android.widget.SeekBar
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.ZoomState
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Observer
import com.example.procam.databinding.ActivityMainBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private enum class Mode { PHOTO, VIDEO }

    private lateinit var binding: ActivityMainBinding
    private val mainExecutor by lazy { ContextCompat.getMainExecutor(this) }
    private val ioExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // CameraX objects
    private var cameraProvider: ProcessCameraProvider? = null
    private var extensionsManager: ExtensionsManager? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null

    // State
    private var mode = Mode.PHOTO
    private var lensFacing = CameraSelector.LENS_FACING_BACK
    private val flashModes = intArrayOf(
        ImageCapture.FLASH_MODE_OFF,
        ImageCapture.FLASH_MODE_AUTO,
        ImageCapture.FLASH_MODE_ON
    )
    private var flashIndex = 0
    private var torchOn = false
    private var timerSeconds = 0
    private var gridOn = false
    private var maxQuality = false
    private var extMode = ExtensionMode.NONE
    private var availableExt: List<Int> = listOf(ExtensionMode.NONE)
    private var preferredQuality: Quality = Quality.FHD
    private var supportedQualities: List<Quality> = emptyList()
    private var countdown: CountDownTimer? = null
    private var isRecording = false
    private var isPaused = false
    private var focusLocked = false
    private var currentRotation = Surface.ROTATION_0
    private var lastUri: Uri? = null
    private var lastIsVideo = false

    private val zoomObserver = Observer<ZoomState> { state ->
        binding.zoomLabel.text = String.format(Locale.US, "%.1fx", state.zoomRatio)
    }

    private val orientationListener by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                currentRotation = rotation
                imageCapture?.targetRotation = rotation
                videoCapture?.targetRotation = rotation
            }
        }
    }

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            if (coreGranted()) {
                startCamera()
            } else {
                binding.permissionHint.visibility = View.VISIBLE
            }
        }

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setupTouch()
        setupButtons()
        updateUi()

        if (coreGranted()) startCamera() else permissionLauncher.launch(requiredPermissions())
    }

    override fun onStart() {
        super.onStart()
        if (orientationListener.canDetectOrientation()) orientationListener.enable()
    }

    override fun onStop() {
        super.onStop()
        orientationListener.disable()
        cancelCountdown()
    }

    override fun onDestroy() {
        super.onDestroy()
        ioExecutor.shutdown()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (camera != null) {
                if (event?.repeatCount == 0) onShutter()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // -------------------------------------------------------------- permissions

    private fun requiredPermissions(): Array<String> {
        val list = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            list += Manifest.permission.WRITE_EXTERNAL_STORAGE
        }
        return list.toTypedArray()
    }

    private fun has(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun coreGranted() = has(Manifest.permission.CAMERA) &&
            (Build.VERSION.SDK_INT > Build.VERSION_CODES.P ||
                    has(Manifest.permission.WRITE_EXTERNAL_STORAGE))

    // ------------------------------------------------------------------- camera

    private fun startCamera() {
        binding.permissionHint.visibility = View.GONE
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Exception) {
                toast(getString(R.string.camera_error))
                return@addListener
            }
            cameraProvider = provider
            val extFuture = ExtensionsManager.getInstanceAsync(this, provider)
            extFuture.addListener({
                extensionsManager = try {
                    extFuture.get()
                } catch (e: Exception) {
                    null
                }
                bindCamera()
            }, mainExecutor)
        }, mainExecutor)
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        camera?.cameraInfo?.zoomState?.removeObserver(zoomObserver)
        provider.unbindAll()
        focusLocked = false

        val baseSelector = CameraSelector.Builder().requireLensFacing(lensFacing).build()

        // Which special modes (HDR / Night / ...) does this camera support?
        val em = extensionsManager
        val candidates = listOf(
            ExtensionMode.HDR, ExtensionMode.NIGHT, ExtensionMode.AUTO,
            ExtensionMode.BOKEH, ExtensionMode.FACE_RETOUCH
        )
        availableExt = listOf(ExtensionMode.NONE) +
                (if (em != null) candidates.filter { em.isExtensionAvailable(baseSelector, it) }
                else emptyList())
        if (extMode !in availableExt) extMode = ExtensionMode.NONE

        val activeExt = if (mode == Mode.PHOTO) extMode else ExtensionMode.NONE
        val selector = if (activeExt != ExtensionMode.NONE && em != null) {
            em.getExtensionEnabledCameraSelector(baseSelector, activeExt)
        } else {
            baseSelector
        }

        val ratio = if (mode == Mode.PHOTO) AspectRatio.RATIO_4_3 else AspectRatio.RATIO_16_9
        val resolution = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(ratio, AspectRatioStrategy.FALLBACK_RULE_AUTO)
            )
            .build()
        binding.gridOverlay.contentRatio = if (mode == Mode.PHOTO) 3f / 4f else 9f / 16f

        val newPreview = Preview.Builder()
            .setResolutionSelector(resolution)
            .build()
            .also { it.setSurfaceProvider(binding.viewFinder.surfaceProvider) }
        preview = newPreview

        val useCases = mutableListOf<UseCase>(newPreview)

        if (mode == Mode.PHOTO) {
            val capture = ImageCapture.Builder()
                .setResolutionSelector(resolution)
                .setCaptureMode(
                    if (maxQuality) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY
                    else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY
                )
                .setFlashMode(flashModes[flashIndex])
                .setTargetRotation(currentRotation)
                .build()
            imageCapture = capture
            videoCapture = null
            useCases += capture
        } else {
            val info = selector.filter(provider.availableCameraInfos).firstOrNull()
            val supported = info?.let { QualitySelector.getSupportedQualities(it) } ?: emptyList()
            supportedQualities =
                listOf(Quality.UHD, Quality.FHD, Quality.HD, Quality.SD).filter { it in supported }
            if (supportedQualities.isNotEmpty() && preferredQuality !in supportedQualities) {
                preferredQuality = supportedQualities.first()
            }
            val recorder = Recorder.Builder()
                .setQualitySelector(
                    QualitySelector.from(
                        preferredQuality,
                        FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)
                    )
                )
                .build()
            val video = VideoCapture.withOutput(recorder)
            video.targetRotation = currentRotation
            videoCapture = video
            imageCapture = null
            useCases += video
        }

        try {
            camera = provider.bindToLifecycle(this, selector, *useCases.toTypedArray())
        } catch (e: Exception) {
            toast(getString(R.string.camera_error))
            return
        }

        camera?.let { cam ->
            cam.cameraInfo.zoomState.observe(this, zoomObserver)
            setupExposure(cam)
            if (mode == Mode.VIDEO && torchOn && cam.cameraInfo.hasFlashUnit()) {
                cam.cameraControl.enableTorch(true)
            }
        }
        updateUi()
    }

    private fun setupExposure(cam: Camera) {
        val state = cam.cameraInfo.exposureState
        if (!state.isExposureCompensationSupported) {
            binding.exposureGroup.visibility = View.GONE
            return
        }
        val range = state.exposureCompensationRange
        val step = state.exposureCompensationStep.toFloat()
        binding.exposureGroup.visibility = View.VISIBLE
        binding.exposureSeek.setOnSeekBarChangeListener(null)
        binding.exposureSeek.max = range.upper - range.lower
        binding.exposureSeek.progress = -range.lower
        binding.evLabel.text = "EV 0.0"
        binding.exposureSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val index = progress + range.lower
                cam.cameraControl.setExposureCompensationIndex(index)
                binding.evLabel.text = String.format(Locale.US, "EV %+.1f", index * step)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
    }

    // -------------------------------------------------------------------- input

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouch() {
        val scaleDetector = ScaleGestureDetector(
            this,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    val cam = camera ?: return true
                    val current = cam.cameraInfo.zoomState.value?.zoomRatio ?: 1f
                    cam.cameraControl.setZoomRatio(current * detector.scaleFactor)
                    return true
                }
            })
        val tapDetector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onSingleTapUp(e: MotionEvent): Boolean {
                    focusAt(e.x, e.y, lock = false)
                    return true
                }

                override fun onLongPress(e: MotionEvent) {
                    binding.viewFinder.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    focusAt(e.x, e.y, lock = true)
                }
            })
        binding.viewFinder.setOnTouchListener { v, event ->
            scaleDetector.onTouchEvent(event)
            if (!scaleDetector.isInProgress) tapDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_UP) v.performClick()
            true
        }
    }

    private fun setupButtons() {
        binding.btnFlash.setOnClickListener { onFlashClick() }
        binding.btnTimer.setOnClickListener {
            timerSeconds = when (timerSeconds) {
                0 -> 3
                3 -> 10
                else -> 0
            }
            updateUi()
        }
        binding.btnGrid.setOnClickListener {
            gridOn = !gridOn
            updateUi()
        }
        binding.btnFx.setOnClickListener { cycleExtension() }
        binding.btnQuality.setOnClickListener { onQualityClick() }
        binding.btnFlip.setOnClickListener { flipCamera() }
        binding.tabPhoto.setOnClickListener { switchMode(Mode.PHOTO) }
        binding.tabVideo.setOnClickListener { switchMode(Mode.VIDEO) }
        binding.shutter.setOnClickListener { onShutter() }
        binding.btnPause.setOnClickListener { togglePause() }
        binding.thumb.setOnClickListener { openLast() }
        binding.zoomLabel.setOnClickListener { camera?.cameraControl?.setZoomRatio(1f) }
        binding.permissionHint.setOnClickListener {
            startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", packageName, null)
                )
            )
        }
    }

    private fun focusAt(x: Float, y: Float, lock: Boolean) {
        val cam = camera ?: return
        val point = binding.viewFinder.meteringPointFactory.createPoint(x, y)
        val builder = FocusMeteringAction.Builder(
            point,
            FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE or FocusMeteringAction.FLAG_AWB
        )
        if (lock) builder.disableAutoCancel() else builder.setAutoCancelDuration(4, TimeUnit.SECONDS)
        cam.cameraControl.startFocusAndMetering(builder.build())
        focusLocked = lock
        if (lock) toast(getString(R.string.af_locked))
        showFocusRing(x, y, keep = lock)
    }

    private fun showFocusRing(x: Float, y: Float, keep: Boolean) {
        val ring = binding.focusRing
        val half = ring.width / 2f
        ring.animate().cancel()
        ring.translationX = x - half
        ring.translationY = y - half
        ring.alpha = 1f
        ring.scaleX = 1.4f
        ring.scaleY = 1.4f
        ring.visibility = View.VISIBLE
        ring.animate()
            .scaleX(1f).scaleY(1f)
            .setStartDelay(0)
            .setDuration(250)
            .withEndAction {
                if (!keep) {
                    ring.animate().alpha(0f).setStartDelay(800).setDuration(300).start()
                }
            }
            .start()
    }

    // ----------------------------------------------------------------- controls

    private fun onFlashClick() {
        val cam = camera ?: return
        if (!cam.cameraInfo.hasFlashUnit()) return
        if (mode == Mode.PHOTO) {
            flashIndex = (flashIndex + 1) % flashModes.size
            imageCapture?.flashMode = flashModes[flashIndex]
        } else {
            torchOn = !torchOn
            cam.cameraControl.enableTorch(torchOn)
        }
        updateUi()
    }

    private fun cycleExtension() {
        if (mode != Mode.PHOTO || availableExt.size <= 1) {
            toast(getString(R.string.fx_unavailable))
            return
        }
        val index = availableExt.indexOf(extMode)
        extMode = availableExt[(index + 1) % availableExt.size]
        bindCamera()
    }

    private fun onQualityClick() {
        if (mode == Mode.VIDEO) {
            if (isRecording || supportedQualities.size <= 1) return
            val index = supportedQualities.indexOf(preferredQuality)
            preferredQuality = supportedQualities[(index + 1) % supportedQualities.size]
        } else {
            maxQuality = !maxQuality
        }
        bindCamera()
    }

    private fun flipCamera() {
        if (isRecording) return
        val target = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        val selector = CameraSelector.Builder().requireLensFacing(target).build()
        val ok = try {
            cameraProvider?.hasCamera(selector) == true
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            toast(getString(R.string.no_other_camera))
            return
        }
        lensFacing = target
        torchOn = false
        bindCamera()
    }

    private fun switchMode(newMode: Mode) {
        if (mode == newMode || isRecording) return
        cancelCountdown()
        mode = newMode
        torchOn = false
        bindCamera()
    }

    private fun onShutter() {
        if (countdown != null) {
            cancelCountdown()
            return
        }
        if (mode == Mode.VIDEO && isRecording) {
            stopRecording()
            return
        }
        if (timerSeconds > 0) startCountdown(timerSeconds) else performCapture()
    }

    private fun performCapture() {
        if (mode == Mode.PHOTO) takePhoto() else startRecording()
    }

    private fun startCountdown(seconds: Int) {
        binding.countdownText.visibility = View.VISIBLE
        binding.countdownText.text = seconds.toString()
        countdown = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                binding.countdownText.text = ((millisUntilFinished + 999) / 1000).toString()
            }

            override fun onFinish() {
                binding.countdownText.visibility = View.GONE
                countdown = null
                performCapture()
            }
        }.start()
    }

    private fun cancelCountdown() {
        countdown?.cancel()
        countdown = null
        binding.countdownText.visibility = View.GONE
    }

    // -------------------------------------------------------------------- photo

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "IMG_${timestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/ProCam")
            }
        }
        val metadata = ImageCapture.Metadata().apply {
            isReversedHorizontal = lensFacing == CameraSelector.LENS_FACING_FRONT
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values
        ).setMetadata(metadata).build()

        binding.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        flashScreen()
        capture.takePicture(options, mainExecutor, object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                output.savedUri?.let { setLastCapture(it, video = false) }
            }

            override fun onError(exception: ImageCaptureException) {
                toast(getString(R.string.photo_failed, exception.message ?: ""))
            }
        })
    }

    private fun flashScreen() {
        binding.flashOverlay.animate().cancel()
        binding.flashOverlay.alpha = 0.7f
        binding.flashOverlay.animate().alpha(0f).setDuration(180).start()
    }

    // -------------------------------------------------------------------- video

    @SuppressLint("MissingPermission")
    private fun startRecording() {
        val capture = videoCapture ?: return
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "VID_${timestamp()}")
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "Movies/ProCam")
            }
        }
        val options = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        var pending = capture.output.prepareRecording(this, options)
        if (has(Manifest.permission.RECORD_AUDIO)) pending = pending.withAudioEnabled()

        binding.shutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        recording = pending.start(mainExecutor) { event -> handleRecordEvent(event) }
    }

    private fun handleRecordEvent(event: VideoRecordEvent) {
        when (event) {
            is VideoRecordEvent.Start -> {
                isRecording = true
                isPaused = false
                binding.recTime.text = "● 00:00"
                updateUi()
            }
            is VideoRecordEvent.Status -> {
                val seconds = TimeUnit.NANOSECONDS.toSeconds(event.recordingStats.recordedDurationNanos)
                binding.recTime.text = String.format(
                    Locale.US, "● %02d:%02d", seconds / 60, seconds % 60
                )
            }
            is VideoRecordEvent.Pause -> {
                isPaused = true
                updateUi()
            }
            is VideoRecordEvent.Resume -> {
                isPaused = false
                updateUi()
            }
            is VideoRecordEvent.Finalize -> {
                isRecording = false
                isPaused = false
                recording = null
                if (event.hasError()) {
                    toast(getString(R.string.video_failed, event.error))
                } else {
                    toast(getString(R.string.video_saved))
                    setLastCapture(event.outputResults.outputUri, video = true)
                }
                updateUi()
            }
            else -> Unit
        }
    }

    private fun stopRecording() {
        recording?.stop()
    }

    private fun togglePause() {
        val rec = recording ?: return
        if (isPaused) rec.resume() else rec.pause()
    }

    // ------------------------------------------------------------ last capture

    private fun setLastCapture(uri: Uri, video: Boolean) {
        lastUri = uri
        lastIsVideo = video
        ioExecutor.execute {
            val bitmap = loadThumbnail(uri, video)
            runOnUiThread {
                if (!isDestroyed && bitmap != null) binding.thumb.setImageBitmap(bitmap)
            }
        }
    }

    private fun loadThumbnail(uri: Uri, video: Boolean): Bitmap? = try {
        if (video) {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(this, uri)
                retriever.getFrameAtTime(0)
            } finally {
                retriever.release()
            }
        } else {
            val options = BitmapFactory.Options().apply { inSampleSize = 8 }
            val bitmap = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            val orientation = contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (bitmap != null && degrees != 0f) {
                Bitmap.createBitmap(
                    bitmap, 0, 0, bitmap.width, bitmap.height,
                    Matrix().apply { postRotate(degrees) }, true
                )
            } else {
                bitmap
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun openLast() {
        val uri = lastUri ?: return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, if (lastIsVideo) "video/*" else "image/*")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.no_gallery))
        }
    }

    // ----------------------------------------------------------------------- UI

    private fun updateUi() {
        val photo = mode == Mode.PHOTO
        binding.tabPhoto.isSelected = photo
        binding.tabVideo.isSelected = !photo

        val hasFlash = camera?.cameraInfo?.hasFlashUnit() == true
        binding.btnFlash.alpha = if (hasFlash) 1f else 0.4f
        binding.btnFlash.text = if (photo) {
            when (flashIndex) {
                0 -> "⚡ OFF"
                1 -> "⚡ AUTO"
                else -> "⚡ ON"
            }
        } else {
            if (torchOn) "🔦 ON" else "🔦 OFF"
        }
        binding.btnFlash.isSelected = hasFlash && (if (photo) flashIndex != 0 else torchOn)

        binding.btnTimer.text = if (timerSeconds == 0) "⏱ OFF" else "⏱ ${timerSeconds}s"
        binding.btnTimer.isSelected = timerSeconds != 0

        binding.btnGrid.isSelected = gridOn
        binding.gridOverlay.visibility = if (gridOn) View.VISIBLE else View.GONE

        binding.btnFx.text = if (photo) "✨ ${extLabel(extMode)}" else "✨ —"
        binding.btnFx.isSelected = photo && extMode != ExtensionMode.NONE
        binding.btnFx.alpha = if (photo && availableExt.size > 1) 1f else 0.4f

        binding.btnQuality.text = qualityLabel()
        binding.btnQuality.isSelected = photo && maxQuality
        binding.btnQuality.alpha = if (isRecording) 0.4f else 1f

        binding.recTime.visibility = if (isRecording) View.VISIBLE else View.GONE
        binding.btnPause.visibility = if (isRecording) View.VISIBLE else View.GONE
        binding.btnPause.text = if (isPaused) "▶" else "⏸"
        binding.thumb.visibility = if (isRecording) View.INVISIBLE else View.VISIBLE
        binding.modeTabs.visibility = if (isRecording) View.INVISIBLE else View.VISIBLE
        binding.btnFlip.isEnabled = !isRecording
        binding.btnFlip.alpha = if (isRecording) 0.3f else 1f

        binding.shutter.setBackgroundResource(
            when {
                photo -> R.drawable.shutter_photo
                isRecording -> R.drawable.shutter_recording
                else -> R.drawable.shutter_video
            }
        )
    }

    private fun extLabel(mode: Int) = when (mode) {
        ExtensionMode.HDR -> "HDR"
        ExtensionMode.NIGHT -> "Night"
        ExtensionMode.AUTO -> "Auto"
        ExtensionMode.BOKEH -> "Portrait"
        ExtensionMode.FACE_RETOUCH -> "Retouch"
        else -> "—"
    }

    private fun qualityLabel(): String = if (mode == Mode.PHOTO) {
        if (maxQuality) "HQ" else "FAST"
    } else {
        when (preferredQuality) {
            Quality.UHD -> "4K"
            Quality.FHD -> "1080p"
            Quality.HD -> "720p"
            else -> "480p"
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    private fun toast(message: String) =
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
