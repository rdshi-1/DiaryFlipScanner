package com.example.diaryflip

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.Surface
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.TorchState
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.diaryflip.data.SessionRepository
import com.example.diaryflip.databinding.ActivityMainBinding
import com.example.diaryflip.network.TranscriptionWorker
import com.example.diaryflip.scanner.DocumentFingerprint
import com.example.diaryflip.scanner.ImageSplitter
import com.example.diaryflip.scanner.StabilityAnalyzer
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var processingExecutor: ExecutorService
    private lateinit var analyzer: StabilityAnalyzer
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var torchEnabled = false
    private var scanning = false
    private var sessionDirectory: File? = null
    private var capturedPages = 0
    private var nextPageNumber = 1
    private var nextSpreadNumber = 1
    private var lastFingerprint: DocumentFingerprint? = null
    private val captureInProgress = AtomicBoolean(false)
    private val tone by lazy { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 65) }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startCamera() else {
            binding.statusText.text = "Camera permission is required"
            Toast.makeText(this, "Please allow camera access to scan the diary.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        cameraExecutor = Executors.newSingleThreadExecutor()
        processingExecutor = Executors.newSingleThreadExecutor()

        analyzer = StabilityAnalyzer(
            onStatus = { status -> runOnUiThread { renderStatus(status) } },
            onCaptureReady = { runOnUiThread { captureSpread() } }
        )

        binding.startStopButton.setOnClickListener {
            if (scanning) stopScanning() else beginScanning()
        }
        binding.newDiaryButton.setOnClickListener { requestNewDiary() }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.reviewButton.setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java))
        }
        binding.libraryButton.setOnClickListener {
            startActivity(Intent(this, LibraryActivity::class.java))
        }
        binding.lightButton.setOnClickListener { toggleTorch() }
        binding.lightButton.isEnabled = false

        refreshCurrentDiarySummary()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::binding.isInitialized && !scanning) {
            refreshCurrentDiarySummary()
        }
    }

    private fun startCamera() {
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            val provider = providerFuture.get()
            val rotation = binding.previewView.display?.rotation ?: Surface.ROTATION_0

            val preview = Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()
                .also { it.setSurfaceProvider(binding.previewView.surfaceProvider) }

            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setTargetRotation(rotation)
                .build()

            val analysis = ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_4_3)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetRotation(rotation)
                .build()
                .also { it.setAnalyzer(cameraExecutor, analyzer) }

            try {
                provider.unbindAll()
                camera = provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_BACK_CAMERA,
                    preview,
                    imageCapture,
                    analysis
                )

                val activeCamera = camera ?: return@addListener
                if (activeCamera.cameraInfo.hasFlashUnit()) {
                    binding.lightButton.isEnabled = true
                    activeCamera.cameraInfo.torchState.observe(this) { state ->
                        torchEnabled = state == TorchState.ON
                        updateTorchButton()
                    }
                } else {
                    torchEnabled = false
                    binding.lightButton.isEnabled = false
                    binding.lightButton.text = getString(R.string.light_unavailable)
                }
                if (!scanning) {
                    binding.statusText.text = "Place both diary pages inside the guide"
                }
            } catch (error: Exception) {
                binding.statusText.text = "Could not start camera"
                Toast.makeText(this, error.message ?: "Camera error", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    /**
     * Starts or resumes scanning in the current diary folder. It never creates a new diary unless
     * there is no diary at all yet.
     */
    private fun beginScanning() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera is still starting.", Toast.LENGTH_SHORT).show()
            return
        }

        val session = SessionRepository.getOrCreateCurrentSession(this)
        sessionDirectory = session
        capturedPages = SessionRepository.pageCount(session)
        nextPageNumber = SessionRepository.nextPageNumber(session)
        nextSpreadNumber = SessionRepository.nextSpreadNumber(session)
        scanning = true
        captureInProgress.set(false)
        analyzer.setEnabled(false)

        binding.startStopButton.text = getString(R.string.stop_scanning)
        binding.settingsButton.isEnabled = false
        binding.reviewButton.isEnabled = false
        binding.libraryButton.isEnabled = false
        binding.newDiaryButton.isEnabled = false
        binding.pageCountText.text = pageCountLabel(capturedPages)
        binding.currentDiaryText.text = currentDiaryLabel(session)
        binding.statusText.text = if (capturedPages == 0) {
            "Preparing first spread…"
        } else {
            "Preparing to continue this diary…"
        }

        // Loading the final spread fingerprint prevents an unchanged spread being captured again
        // when scanning is stopped and later resumed.
        val latestSpread = SessionRepository.latestSpreadFile(session)
        processingExecutor.execute {
            val fingerprint = try {
                latestSpread?.let(ImageSplitter::fingerprintSpread)
            } catch (_: Exception) {
                null
            }
            runOnUiThread {
                if (!scanning || sessionDirectory?.absolutePath != session.absolutePath) {
                    return@runOnUiThread
                }
                lastFingerprint = fingerprint
                analyzer.setEnabled(true)
                binding.statusText.text = if (capturedPages == 0) {
                    "Hold the first spread still"
                } else {
                    "Continue scanning — hold the next spread still"
                }
            }
        }

        if (SessionRepository.endpoint(this).isBlank()) {
            Toast.makeText(
                this,
                "Scanning will work, but transcription is off until a server URL is added in Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /** Stops camera analysis but keeps the same diary selected for the next Start tap. */
    private fun stopScanning() {
        scanning = false
        analyzer.setEnabled(false)
        setTorch(false)
        binding.startStopButton.text = getString(R.string.start_scanning)
        binding.settingsButton.isEnabled = true
        binding.reviewButton.isEnabled = true
        binding.libraryButton.isEnabled = true
        binding.newDiaryButton.isEnabled = true
        binding.statusText.text = if (capturedPages == 0) {
            "Scanning stopped — this diary is still empty"
        } else {
            "Scanning stopped — ${pageCountLabel(capturedPages)} saved in this diary"
        }
        sessionDirectory?.let {
            binding.currentDiaryText.text = currentDiaryLabel(it)
        }
        if (capturedPages > 0) {
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        }
    }

    private fun requestNewDiary() {
        if (scanning) return
        val current = SessionRepository.currentSession(this)
        val existingPages = current?.let(SessionRepository::pageCount) ?: 0

        if (current == null || existingPages == 0) {
            createNewDiary()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("Start a new diary?")
            .setMessage(
                "Your current diary and its ${pageCountLabel(existingPages)} will be kept. " +
                    "Future scans will go into a new folder."
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("New diary") { _, _ -> createNewDiary() }
            .show()
    }

    private fun createNewDiary() {
        val newSession = SessionRepository.startNewSession(this)
        sessionDirectory = newSession
        capturedPages = 0
        nextPageNumber = 1
        nextSpreadNumber = 1
        lastFingerprint = null
        captureInProgress.set(false)
        binding.pageCountText.text = pageCountLabel(0)
        binding.currentDiaryText.text = currentDiaryLabel(newSession)
        binding.statusText.text = "New diary ready — tap Start scanning"
        Toast.makeText(this, "New diary created", Toast.LENGTH_SHORT).show()
    }

    private fun captureSpread() {
        if (!scanning || !captureInProgress.compareAndSet(false, true)) return
        val capture = imageCapture
        val session = sessionDirectory
        if (capture == null || session == null) {
            captureInProgress.set(false)
            analyzer.captureCompleted(false)
            return
        }

        binding.statusText.text = "Capturing…"
        val spreadNumber = nextSpreadNumber
        val spreadFile = File(session, "spread_${spreadNumber.toString().padStart(4, '0')}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(spreadFile).build()

        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    processingExecutor.execute { processCapturedSpread(spreadFile) }
                }

                override fun onError(exception: ImageCaptureException) {
                    captureInProgress.set(false)
                    analyzer.captureCompleted(false)
                    runOnUiThread {
                        binding.statusText.text = "Capture failed — hold still"
                        Toast.makeText(this@MainActivity, exception.message, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
    }

    private fun processCapturedSpread(spreadFile: File) {
        val session = sessionDirectory ?: return failCapture("Session unavailable")
        try {
            val leftNumber = nextPageNumber
            val result = ImageSplitter.splitSpread(
                spreadFile = spreadFile,
                outputDirectory = session,
                leftPageNumber = leftNumber,
                keepSpread = true
            )

            val duplicateDistance = lastFingerprint?.distance(result.fingerprint)
            if (duplicateDistance != null && duplicateDistance < 2.2) {
                result.leftPage.delete()
                result.rightPage.delete()
                spreadFile.delete()
                captureInProgress.set(false)
                analyzer.captureCompleted(true)
                runOnUiThread {
                    binding.statusText.text = "Same spread detected — turn the page"
                }
                return
            }

            lastFingerprint = result.fingerprint
            nextPageNumber += 2
            nextSpreadNumber += 1
            capturedPages = SessionRepository.pageCount(session)
            val endpoint = SessionRepository.endpoint(this)
            val token = SessionRepository.token(this)
            TranscriptionWorker.enqueue(this, result.leftPage, leftNumber, endpoint, token)
            TranscriptionWorker.enqueue(this, result.rightPage, leftNumber + 1, endpoint, token)

            captureInProgress.set(false)
            analyzer.captureCompleted(true)
            runOnUiThread {
                binding.pageCountText.text = pageCountLabel(capturedPages)
                binding.currentDiaryText.text = currentDiaryLabel(session)
                binding.statusText.text = if (scanning) {
                    "Next page ✓"
                } else {
                    "Scanning stopped — ${pageCountLabel(capturedPages)} saved in this diary"
                }
                acknowledgeCapture()
            }
        } catch (error: Exception) {
            spreadFile.delete()
            failCapture(error.message ?: "Could not process image")
        }
    }

    private fun failCapture(message: String) {
        captureInProgress.set(false)
        analyzer.captureCompleted(false)
        runOnUiThread {
            binding.statusText.text = if (scanning) {
                "Try again — hold the diary still"
            } else {
                "Scanning stopped"
            }
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshCurrentDiarySummary() {
        val current = SessionRepository.currentSession(this)
        sessionDirectory = current
        capturedPages = current?.let(SessionRepository::pageCount) ?: 0
        nextPageNumber = current?.let(SessionRepository::nextPageNumber) ?: 1
        nextSpreadNumber = current?.let(SessionRepository::nextSpreadNumber) ?: 1
        binding.pageCountText.text = pageCountLabel(capturedPages)
        binding.currentDiaryText.text = if (current == null) {
            "No diary yet — Start scanning or tap New diary"
        } else {
            currentDiaryLabel(current)
        }
    }

    private fun currentDiaryLabel(session: File): String =
        "Current diary: ${SessionRepository.diaryLabel(session)} — Start/Stop keeps using this diary"

    private fun pageCountLabel(count: Int): String =
        if (count == 1) "1 page" else "$count pages"

    private fun acknowledgeCapture() {
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, 90)
        val vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        vibrator.vibrate(VibrationEffect.createOneShot(70, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun toggleTorch() {
        val activeCamera = camera
        if (activeCamera == null) {
            Toast.makeText(this, "Camera is still starting.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!activeCamera.cameraInfo.hasFlashUnit()) {
            Toast.makeText(this, "This phone camera has no usable light.", Toast.LENGTH_SHORT).show()
            return
        }
        setTorch(!torchEnabled)
    }

    private fun setTorch(enabled: Boolean) {
        val activeCamera = camera ?: return
        if (!activeCamera.cameraInfo.hasFlashUnit()) return

        val request = activeCamera.cameraControl.enableTorch(enabled)
        request.addListener({
            try {
                request.get()
            } catch (error: Exception) {
                Toast.makeText(
                    this,
                    error.message ?: "Could not change the camera light.",
                    Toast.LENGTH_SHORT
                ).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun updateTorchButton() {
        binding.lightButton.text = getString(
            if (torchEnabled) R.string.light_on else R.string.light_off
        )
        binding.lightButton.isChecked = torchEnabled
    }

    private fun renderStatus(status: StabilityAnalyzer.ScanStatus) {
        if (!scanning || captureInProgress.get()) return
        binding.statusText.text = when (status) {
            StabilityAnalyzer.ScanStatus.IDLE -> "Place both diary pages inside the guide"
            StabilityAnalyzer.ScanStatus.PAGE_TURNING -> "Page turning…"
            StabilityAnalyzer.ScanStatus.HOLD_STILL -> "Hold still…"
            StabilityAnalyzer.ScanStatus.IMPROVING_FOCUS -> "Waiting for a sharper image…"
            StabilityAnalyzer.ScanStatus.READY_TO_CAPTURE -> "Capturing…"
            StabilityAnalyzer.ScanStatus.WAITING_FOR_NEXT_PAGE -> "Next page ✓"
        }
    }

    override fun onDestroy() {
        setTorch(false)
        analyzer.setEnabled(false)
        cameraExecutor.shutdown()
        processingExecutor.shutdown()
        tone.release()
        super.onDestroy()
    }
}
