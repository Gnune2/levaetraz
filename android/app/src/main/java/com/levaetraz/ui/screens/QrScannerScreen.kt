package com.levaetraz.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing
import java.util.concurrent.Executors

/**
 * Leitor do QR que o `main.py` imprime no terminal.
 * O payload é `levaetraz://<ip>:<porta>/<código>`.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun QrScannerScreen(
    onLido: (String) -> Unit,
    onCancelar: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val haptics = LocalHaptics.current

    var temPermissao by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var jaLeu by remember { mutableStateOf(false) }

    val pedirPermissao = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedida -> temPermissao = concedida }

    LaunchedEffect(Unit) {
        if (!temPermissao) pedirPermissao.launch(Manifest.permission.CAMERA)
    }

    val executor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Box(Modifier.fillMaxSize().background(Palette.Bg)) {

        if (temPermissao) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val futuro = ProcessCameraProvider.getInstance(ctx)
                    futuro.addListener({
                        val provider = futuro.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                        val scanner = BarcodeScanning.getClient()
                        val analise = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()

                        analise.setAnalyzer(executor) { proxy ->
                            val media = proxy.image
                            if (media == null || jaLeu) {
                                proxy.close()
                                return@setAnalyzer
                            }
                            val imagem = InputImage.fromMediaImage(
                                media, proxy.imageInfo.rotationDegrees
                            )
                            scanner.process(imagem)
                                .addOnSuccessListener { codigos ->
                                    val texto = codigos.firstOrNull { c ->
                                        c.valueType == Barcode.TYPE_TEXT || c.rawValue != null
                                    }?.rawValue
                                    if (!texto.isNullOrBlank() && !jaLeu) {
                                        jaLeu = true
                                        haptics.success()
                                        onLido(texto)
                                    }
                                }
                                .addOnCompleteListener { proxy.close() }
                        }

                        runCatching {
                            provider.unbindAll()
                            provider.bindToLifecycle(
                                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA,
                                preview, analise,
                            )
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
            )

            // moldura de mira
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(240.dp)
                        .border(2.dp, Palette.Accent, RoundedCornerShape(16.dp))
                )
            }
        } else {
            Column(
                Modifier.fillMaxSize().padding(Spacing.xl),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "preciso da câmera para ler o QR code do terminal",
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.TextDim,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(Spacing.lg))
                Button(
                    onClick = { pedirPermissao.launch(Manifest.permission.CAMERA) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Palette.Accent, contentColor = Palette.Bg,
                    ),
                ) { Text("PERMITIR", style = MaterialTheme.typography.labelLarge) }
            }
        }

        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(Spacing.xl),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "aponte para o QR code impresso no terminal do PC",
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextDim,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(Spacing.md))
            Button(
                onClick = onCancelar,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.SurfaceHigh, contentColor = Palette.Text,
                ),
            ) { Text("CANCELAR", style = MaterialTheme.typography.labelLarge) }
        }
    }
}
