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
            if (scanning) finishScanning() else beginScanning()
        }
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        binding.reviewButton.setOnClickListener {
            startActivity(Intent(this, ReviewActivity::class.java))
        }
        binding.lightButton.setOnClickListener {
            toggleTorch()
        }
        binding.lightButton.isEnabled = false

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
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
                binding.statusText.text = "Place both diary pages inside the guide"
            } catch (error: Exception) {
                binding.statusText.text = "Could not start camera"
                Toast.makeText(this, error.message ?: "Camera error", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun beginScanning() {
        if (imageCapture == null) {
            Toast.makeText(this, "Camera is still starting.", Toast.LENGTH_SHORT).show()
            return
        }

        sessionDirectory = SessionRepository.startNewSession(this)
        capturedPages = 0
        lastFingerprint = null
        scanning = true
        captureInProgress.set(false)
        analyzer.setEnabled(true)

        binding.startStopButton.text = getString(R.string.stop_scanning)
        binding.settingsButton.isEnabled = false
        binding.pageCountText.text = "0 pages"
        binding.statusText.text = "Hold the first spread still"

        if (SessionRepository.endpoint(this).isBlank()) {
            Toast.makeText(
                this,
                "Scanning will work, but transcription is off until a server URL is added in Settings.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun finishScanning() {
        scanning = false
        analyzer.setEnabled(false)
        setTorch(false)
        binding.startStopButton.text = getString(R.string.start_scanning)
        binding.settingsButton.isEnabled = true
        binding.statusText.text = if (capturedPages == 0) {
            "No pages captured"
        } else {
            "Finished — $capturedPages pages saved"
        }
        if (capturedPages > 0) {
            tone.startTone(ToneGenerator.TONE_PROP_ACK, 120)
        }
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
        val spreadNumber = capturedPages / 2 + 1
        val spreadFile = File(session, "spread_${spreadNumber.toString().padStart(4, '0')}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(spreadFile).build()

        capture.takePicture(
            options,
            cameraExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    processingExecutor.execute {
                        processCapturedSpread(spreadFile)
                    }
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
            val leftNumber = capturedPages + 1
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
            capturedPages += 2
            val endpoint = SessionRepository.endpoint(this)
            val token = SessionRepository.token(this)
            TranscriptionWorker.enqueue(this, result.leftPage, leftNumber, endpoint, token)
            TranscriptionWorker.enqueue(this, result.rightPage, leftNumber + 1, endpoint, token)

            captureInProgress.set(false)
            analyzer.captureCompleted(true)
            runOnUiThread {
                binding.pageCountText.text = "$capturedPages pages"
                binding.statusText.text = "Next page ✓"
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
            binding.statusText.text = "Try again — hold the diary still"
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
    }

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
        super.onDestroy()
        analyzer.setEnabled(false)
        cameraExecutor.shutdown()
        processingExecutor.shutdown()
        tone.release()
    }
}
