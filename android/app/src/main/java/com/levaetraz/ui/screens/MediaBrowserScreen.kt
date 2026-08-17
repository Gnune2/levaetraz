package com.levaetraz.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.levaetraz.data.ImageLoaders
import com.levaetraz.model.ItemArquivo
import com.levaetraz.model.tamanhoLegivel
import com.levaetraz.ui.components.Chip
import com.levaetraz.ui.components.SectionLabel
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing
import com.levaetraz.vm.BrowserState

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MediaBrowserScreen(
    estado: BrowserState,
    token: String,
    thumbUrl: (String) -> String,
    onNavegar: (String?) -> Unit,
    onRecarregar: () -> Unit,
    onAbrir: (ItemArquivo) -> Unit,
    onAlternarSelecao: (ItemArquivo) -> Unit,
    onSelecionarTodos: () -> Unit,
    onLimparSelecao: () -> Unit,
    onPuxar: () -> Unit,
    onAlternarForcar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current
    val context = LocalContext.current
    val loader = remember(token) { ImageLoaders.para(context, token) }

    val arquivos = estado.itens.filter { !it.ehPasta }
    val emSelecao = estado.selecionados.isNotEmpty()

    Column(modifier.fillMaxSize()) {

        // ── caminho + ações ──────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    SectionLabel(
                        if (emSelecao) "${estado.selecionados.size} selecionado(s)"
                        else "arquivos no PC"
                    )
                    Text(
                        estado.path.ifBlank { "…" },
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextDim,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
                if (emSelecao) {
                    IconeAcao(Icons.Rounded.Download, "puxar selecionados", Palette.Accent) {
                        haptics.confirm(); onPuxar()
                    }
                    IconeAcao(Icons.Rounded.Close, "limpar seleção", Palette.TextMuted) {
                        haptics.tick(); onLimparSelecao()
                    }
                } else {
                    IconeAcao(Icons.Rounded.Refresh, "recarregar", Palette.TextMuted) {
                        haptics.tick(); onRecarregar()
                    }
                }
            }

            Spacer(Modifier.height(Spacing.sm))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            ) {
                if (estado.parent != null) {
                    Chip("⬆ voltar", false, { haptics.tick(); onNavegar(estado.parent) })
                }
                if (arquivos.isNotEmpty()) {
                    val todos = estado.selecionados.size == arquivos.size
                    Chip(
                        if (todos) "nenhum" else "todos (${arquivos.size})",
                        false,
                        { haptics.tick(); if (todos) onLimparSelecao() else onSelecionarTodos() },
                    )
                    val jaTem = arquivos.count { it.caminho in estado.jaNoCelular }
                    if (jaTem > 0) {
                        Chip(
                            "baixar mesmo assim",
                            estado.forcarRebaixar,
                            { haptics.tick(); onAlternarForcar() },
                            corAtiva = Palette.Warn,
                        )
                    }
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(2.dp)) {
            if (estado.carregando) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.Accent,
                    trackColor = Palette.Surface,
                )
            }
        }

        // ── grade ────────────────────────────────────────────
        when {
            estado.erro != null -> Aviso(estado.erro, Palette.Err, Modifier.weight(1f))
            estado.itens.isEmpty() && !estado.carregando ->
                Aviso("pasta vazia", Palette.TextMuted, Modifier.weight(1f))
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 108.dp),
                modifier = Modifier.weight(1f).navigationBarsPadding(),
                contentPadding = PaddingValues(Spacing.sm),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                items(estado.itens, key = { it.caminho }) { item ->
                    ItemGrid(
                        item = item,
                        selecionado = item.caminho in estado.selecionados,
                        jaNoCelular = item.caminho in estado.jaNoCelular,
                        modoSelecao = emSelecao,
                        loader = loader,
                        thumbUrl = thumbUrl,
                        onClick = {
                            when {
                                item.ehPasta -> { haptics.tick(); onNavegar(item.caminho) }
                                emSelecao -> { haptics.tick(); onAlternarSelecao(item) }
                                else -> { haptics.tick(); onAbrir(item) }
                            }
                        },
                        onLongClick = {
                            if (!item.ehPasta) { haptics.confirm(); onAlternarSelecao(item) }
                        },
                    )
                }
            }
        }
    }

}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ItemGrid(
    item: ItemArquivo,
    selecionado: Boolean,
    jaNoCelular: Boolean,
    modoSelecao: Boolean,
    loader: coil.ImageLoader,
    thumbUrl: (String) -> String,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val borda by animateColorAsState(
        if (selecionado) Palette.Accent else Color.Transparent,
        tween(160), label = "bordaItem",
    )

    Box(
        Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.Surface)
            .border(2.dp, borda, RoundedCornerShape(8.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (item.thumb) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(thumbUrl(item.caminho))
                    .crossfade(true)
                    .build(),
                imageLoader = loader,
                contentDescription = item.nome,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Icon(
                imageVector = when {
                    item.ehPasta -> Icons.Rounded.Folder
                    item.tipo == "audio" -> Icons.Rounded.MusicNote
                    else -> Icons.Rounded.InsertDriveFile
                },
                contentDescription = null,
                tint = Palette.TextMuted,
                modifier = Modifier.align(Alignment.Center).size(34.dp),
            )
        }

        // vídeo ganha um play discreto por cima da miniatura
        if (item.tipo == "video") {
            Icon(
                Icons.Rounded.PlayCircle,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.align(Alignment.Center).size(34.dp),
            )
        }

        if (jaNoCelular) {
            Icon(
                Icons.Rounded.CloudDone,
                contentDescription = "já está no celular",
                tint = Palette.Ok,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(4.dp)
                    .size(18.dp),
            )
        }

        if (selecionado) {
            Box(Modifier.fillMaxSize().background(Palette.Bg.copy(alpha = 0.45f)))
        }
        if (modoSelecao && !item.ehPasta) {
            Icon(
                if (selecionado) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                contentDescription = if (selecionado) "selecionado" else "selecionar",
                tint = if (selecionado) Palette.Accent else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp),
            )
        }

        // rodapé com o nome só quando não há miniatura pra "estragar"
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Palette.Bg.copy(alpha = if (item.thumb) 0.62f else 0f))
                .padding(horizontal = 5.dp, vertical = 3.dp),
        ) {
            Column {
                Text(
                    item.nome,
                    style = MaterialTheme.typography.labelMedium,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.ehPasta && item.tamanhoLegivel.isNotEmpty()) {
                    Text(
                        item.tamanhoLegivel,
                        style = MaterialTheme.typography.labelSmall,
                        color = Palette.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun IconeAcao(
    icone: androidx.compose.ui.graphics.vector.ImageVector,
    descricao: String,
    cor: Color,
    onClick: () -> Unit,
) {
    Icon(
        icone, contentDescription = descricao, tint = cor,
        modifier = Modifier
            .size(38.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
    )
}

@Composable
private fun Aviso(texto: String, cor: Color, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            texto,
            style = MaterialTheme.typography.bodyMedium,
            color = cor,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(Spacing.xl),
        )
    }
}
