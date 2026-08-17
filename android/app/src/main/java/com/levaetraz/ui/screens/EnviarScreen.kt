package com.levaetraz.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.levaetraz.model.EnvioLocal
import com.levaetraz.model.tamanhoLegivel
import com.levaetraz.model.EstadoLocal
import com.levaetraz.ui.components.Chip
import com.levaetraz.ui.components.ProgressBar
import com.levaetraz.ui.components.SectionLabel
import com.levaetraz.ui.components.StatusDot
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Mono
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing
import com.levaetraz.vm.EnvioState

/**
 * Celular → PC.
 *
 * O seletor é o do próprio Android (SAF), então dá para pegar de qualquer app
 * que exponha arquivos — galeria, Drive, gerenciador — sem o levaetraz pedir
 * permissão de armazenamento nenhuma.
 */
@Composable
fun EnviarScreen(
    estado: EnvioState,
    onAbrindoSeletor: () -> Unit,
    onEscolher: (List<Uri>) -> Unit,
    onDestino: (String) -> Unit,
    onCancelar: () -> Unit,
    onLimpar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current

    val seletor = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris -> if (uris.isNotEmpty()) onEscolher(uris) }

    Column(modifier.fillMaxSize()) {

        // ── destino no PC ────────────────────────────────────
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        ) {
            SectionLabel("onde cai no PC")
            Row(
                Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            ) {
                Chip(
                    texto = "padrão",
                    selecionado = estado.destino.isBlank(),
                    onClick = { haptics.tick(); onDestino("") },
                )
                estado.pastasDoPc.forEach { pasta ->
                    Chip(
                        texto = pasta.nome,
                        selecionado = estado.destino == pasta.caminho,
                        onClick = { haptics.tick(); onDestino(pasta.caminho) },
                    )
                }
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        // ── botão de escolher ────────────────────────────────
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg)
                .border(1.dp, Palette.Border, RoundedCornerShape(12.dp))
                .clickable {
                    haptics.tick()
                    onAbrindoSeletor()
                    // "*/*" e não um filtro: o app manda qualquer coisa, e
                    // restringir aqui só esconderia arquivos do usuário.
                    seletor.launch(arrayOf("*/*"))
                }
                .padding(vertical = Spacing.xl),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Rounded.UploadFile, null,
                    tint = Palette.Accent,
                    modifier = Modifier.height(30.dp),
                )
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "ESCOLHER ARQUIVOS",
                    style = MaterialTheme.typography.labelLarge,
                    color = Palette.Text,
                )
                Text(
                    "ou compartilhe de qualquer app para o levaetraz",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))

        if (estado.fila.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "nada na fila",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                )
            }
            return@Column
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            if (estado.ativos > 0 || estado.pendentes > 0) {
                Chip("cancelar", false, { haptics.error(); onCancelar() })
            }
            Chip("limpar concluídos", false, { haptics.tick(); onLimpar() })
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(estado.fila, key = { it.uri }) { item -> CartaoEnvio(item) }
        }
    }
}

@Composable
private fun CartaoEnvio(item: EnvioLocal) {
    val (cor, rotulo) = when (item.estado) {
        EstadoLocal.NA_FILA -> Palette.TextMuted to "na fila"
        EstadoLocal.ENVIANDO -> Palette.Accent to "enviando"
        EstadoLocal.CONCLUIDO -> Palette.Ok to "no PC"
        EstadoLocal.DUPLICADO -> Palette.Warn to "já existia"
        EstadoLocal.ERRO -> Palette.Err to "erro"
        EstadoLocal.CANCELADO -> Palette.TextMuted to "cancelado"
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(10.dp))
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            when (item.estado) {
                EstadoLocal.CONCLUIDO ->
                    Icon(Icons.Rounded.Check, null, tint = cor, modifier = Modifier.height(16.dp))
                EstadoLocal.DUPLICADO ->
                    Icon(Icons.Rounded.ContentCopy, null, tint = cor, modifier = Modifier.height(16.dp))
                EstadoLocal.ERRO ->
                    Icon(Icons.Rounded.ErrorOutline, null, tint = cor, modifier = Modifier.height(16.dp))
                EstadoLocal.CANCELADO ->
                    Icon(Icons.Rounded.Close, null, tint = cor, modifier = Modifier.height(16.dp))
                else -> StatusDot(cor)
            }
            Spacer(Modifier.height(Spacing.sm))
            Text(
                item.nome,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.sm),
            )
            Text(
                rotulo,
                style = MaterialTheme.typography.labelSmall,
                color = cor,
            )
        }

        if (item.estado == EstadoLocal.ENVIANDO) {
            Spacer(Modifier.height(Spacing.sm))
            ProgressBar(percent = item.percent, cor = cor)
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            buildString {
                if (item.estado == EstadoLocal.ENVIANDO) {
                    append("${tamanhoLegivel(item.enviados)} de ${tamanhoLegivel(item.tamanho)}")
                    append(" · ${item.percent.toInt()}%")
                } else {
                    append(tamanhoLegivel(item.tamanho))
                }
                if (item.mensagem.isNotBlank()) append(" · ${item.mensagem}")
            },
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextMuted,
        )
    }
}
