package com.example.cognibridge

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Card
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.camera.core.ExperimentalGetImage
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke

class MainActivity : ComponentActivity() {

    private var hasCameraPermission by mutableStateOf(false)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            hasCameraPermission = isGranted
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        hasCameraPermission = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasCameraPermission) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            MaterialTheme {
                if (hasCameraPermission) {
                    CameraOcrScreen()
                } else {
                    Box(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "Brak dostępu do kamery",
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalGetImage::class)
@Composable
fun CameraOcrScreen() {
    val context = LocalContext.current
    val cameraProviderFuture = remember { ProcessCameraProvider.getInstance(context) }

    var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
    var recognizedText by remember { mutableStateOf("Tutaj pojawi się odczytany tekst...") }
    var isScanning by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.Top
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(380.dp)
        ) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx)
                    previewView.scaleType = PreviewView.ScaleType.FIT_CENTER

                    cameraProviderFuture.addListener({
                        val cameraProvider = cameraProviderFuture.get()

                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        val capture = ImageCapture.Builder().build()
                        imageCapture = capture

                        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                        try {
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(
                                ctx as ComponentActivity,
                                cameraSelector,
                                preview,
                                capture
                            )
                        } catch (e: Exception) {
                            recognizedText = "Błąd uruchamiania kamery: ${e.message}"
                        }

                    }, ContextCompat.getMainExecutor(ctx))

                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            ScanGuideOverlay(
                modifier = Modifier.fillMaxSize()
            )
        }

        Spacer(modifier = Modifier.size(12.dp))

        Button(
            onClick = {
                val capture = imageCapture ?: return@Button
                isScanning = true
                recognizedText = "Skanowanie dokumentu..."

                capture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(imageProxy: androidx.camera.core.ImageProxy) {
                            val mediaImage = imageProxy.image
                            if (mediaImage != null) {
                                val image = InputImage.fromMediaImage(
                                    mediaImage,
                                    imageProxy.imageInfo.rotationDegrees
                                )

                                val recognizer = TextRecognition.getClient(
                                    TextRecognizerOptions.DEFAULT_OPTIONS
                                )

                                recognizer.process(image)
                                    .addOnSuccessListener { visionText ->
                                        recognizedText = if (visionText.text.isBlank()) {
                                            "Nie znaleziono tekstu."
                                        } else {
                                            visionText.text
                                        }
                                    }
                                    .addOnFailureListener { e ->
                                        recognizedText = "Błąd OCR: ${e.message}"
                                    }
                                    .addOnCompleteListener {
                                        isScanning = false
                                        imageProxy.close()
                                    }
                            } else {
                                recognizedText = "Nie udało się pobrać obrazu."
                                isScanning = false
                                imageProxy.close()
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            recognizedText = "Błąd robienia zdjęcia: ${exception.message}"
                            isScanning = false
                        }
                    }
                )
            },
            enabled = !isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isScanning) "SCANNING..." else "SCAN DOCUMENT")
        }
        Spacer(modifier = Modifier.size(12.dp))

        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(
                modifier = Modifier
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = "OCR RESULT",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text(text = recognizedText)
            }
        }
    }
}

@Composable
fun ScanGuideOverlay(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val boxWidth = size.width * 0.82f
        val boxHeight = size.height * 0.28f

        val left = (size.width - boxWidth) / 2f
        val top = (size.height - boxHeight) / 2f
        val right = left + boxWidth
        val bottom = top + boxHeight

        val corner = 50f
        val strokeWidth = 6f

        drawLine(
            color = Color.White,
            start = Offset(left, top),
            end = Offset(left + corner, top),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(left, top),
            end = Offset(left, top + corner),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        drawLine(
            color = Color.White,
            start = Offset(right, top),
            end = Offset(right - corner, top),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(right, top),
            end = Offset(right, top + corner),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        drawLine(
            color = Color.White,
            start = Offset(left, bottom),
            end = Offset(left + corner, bottom),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(left, bottom),
            end = Offset(left, bottom - corner),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        drawLine(
            color = Color.White,
            start = Offset(right, bottom),
            end = Offset(right - corner, bottom),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.White,
            start = Offset(right, bottom),
            end = Offset(right, bottom - corner),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
    }
}