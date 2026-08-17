package com.levaetraz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem as Media3Item
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import com.levaetraz.data.ImageLoaders
import com.levaetraz.model.ItemArquivo
import com.levaetraz.model.tamanhoLegivel
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing

/**
 * Preview em tela cheia: imagem ampliada ou vídeo/áudio tocando direto do PC.
 *
 * O `/api/arquivos/baixar` responde a Range, então o ExoPlayer consegue buscar
 * posição sem baixar o arquivo inteiro antes.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PreviewScreen(
    item: ItemArquivo,
    token: String,
    rawUrl: (String) -> String,
    onPuxar: () -> Unit,
    onFechar: () -> Unit,
) {
    val haptics = LocalHaptics.current
    val context = LocalContext.current

    Box(Modifier.fillMaxSize().background(Color.Black)) {

        when (item.tipo) {
            "imagem" -> {
                val loader = remember(token) { ImageLoaders.para(context, token) }
                var estado by remember { mutableStateOf<AsyncImagePainter.State?>(null) }

                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(rawUrl(item.caminho))
                        .crossfade(true)
                        .build(),
                    imageLoader = loader,
                    contentDescription = item.nome,
                    contentScale = ContentScale.Fit,
                    onState = { estado = it },
                    modifier = Modifier.fillMaxSize(),
                )
                if (estado is AsyncImagePainter.State.Loading) {
                    CircularProgressIndicator(
                        color = Palette.Accent,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }

            "video", "audio" -> {
                val player = remember(item.caminho) {
                    // O token vai por header: o /api/arquivos/baixar é autenticado
                    // como todas as outras rotas.
                    val fonte = DefaultHttpDataSource.Factory()
                        .setDefaultRequestProperties(mapOf("X-Auth-Token" to token))
                        .setAllowCrossProtocolRedirects(true)

                    ExoPlayer.Builder(context)
                        .setMediaSourceFactory(DefaultMediaSourceFactory(fonte))
                        .build()
                        .apply {
                            setMediaItem(Media3Item.fromUri(rawUrl(item.caminho)))
                            prepare()
                            playWhenReady = true
                        }
                }

                DisposableEffect(player) { onDispose { player.release() } }

                if (item.tipo == "audio") {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = Palette.Accent.copy(alpha = 0.35f),
                        modifier = Modifier.align(Alignment.Center).size(96.dp),
                    )
                }

                AndroidView(
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            this.player = player
                            useController = true
                            setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
                            setBackgroundColor(android.graphics.Color.BLACK)
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }

            else -> {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        "sem preview para este tipo",
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.TextDim,
                    )
                    Text(
                        item.tamanhoLegivel,
                        style = MaterialTheme.typography.labelMedium,
                        color = Palette.TextMuted,
                    )
                }
            }
        }

        // ── barra superior ───────────────────────────────────
        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.55f))
                .statusBarsPadding()
                .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.Close,
                contentDescription = "fechar",
                tint = Palette.Text,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { haptics.tick(); onFechar() }
                    .padding(8.dp),
            )
            Spacer(Modifier.width(Spacing.sm))
            Column(Modifier.weight(1f)) {
                Text(
                    item.nome,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text(
                    item.tamanhoLegivel,
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.TextMuted,
                )
            }
            Icon(
                Icons.Rounded.Download,
                contentDescription = "puxar pro celular",
                tint = Palette.Accent,
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { haptics.confirm(); onPuxar() }
                    .padding(8.dp),
            )
        }

        Spacer(Modifier.align(Alignment.BottomCenter).navigationBarsPadding())
    }
}
