package com.levaetraz.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.levaetraz.model.Envio
import com.levaetraz.model.tamanhoLegivel
import com.levaetraz.ui.components.Chip
import com.levaetraz.ui.components.ProgressBar
import com.levaetraz.ui.components.SectionLabel
import com.levaetraz.ui.components.StatusDot
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing
import com.levaetraz.vm.HistoricoState

/**
 * O que o PC recebeu — de qualquer aparelho, não só deste.
 *
 * A lista vem do servidor, então ela sobrevive a desinstalar o app e mostra
 * também o que foi mandado do painel web ou de outro celular.
 */
@Composable
fun HistoricoScreen(
    estado: HistoricoState,
    onLimpar: () -> Unit,
    onCancelar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current

    Column(modifier.fillMaxSize()) {

        if (estado.resumo.ativos > 0) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.Surface)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md),
            ) {
                SectionLabel("agora")
                Text(
                    estado.resumo.texto,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Text,
                    modifier = Modifier.padding(vertical = Spacing.xs),
                )
                ProgressBar(percent = estado.resumo.percent, cor = Palette.Accent)
            }
        }

        if (estado.envios.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "nada recebido ainda",
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
        ) {
            Chip("limpar concluídos", false, { haptics.tick(); onLimpar() })
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(estado.envios, key = { it.id }) { e -> CartaoHistorico(e, onCancelar) }
        }
    }
}

@Composable
private fun CartaoHistorico(e: Envio, onCancelar: (String) -> Unit) {
    val (cor, rotulo) = when (e.estado) {
        "recebendo" -> Palette.Accent to "recebendo"
        "aguardando" -> Palette.TextMuted to "aguardando"
        "pausado" -> Palette.Warn to "pausado"
        "concluido" -> Palette.Ok to "no PC"
        "duplicado" -> Palette.Warn to "já existia"
        "erro" -> Palette.Err to "erro"
        else -> Palette.TextMuted to e.estado
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(10.dp))
            .padding(Spacing.md),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(cor)
            Text(
                e.nome,
                style = MaterialTheme.typography.bodyLarge,
                color = Palette.Text,
                maxLines = 1,
                overflow = TextOverflow.MiddleEllipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = Spacing.sm),
            )
            Text(rotulo, style = MaterialTheme.typography.labelSmall, color = cor)
            if (e.estado == "recebendo" || e.estado == "pausado") {
                IconButton(onClick = { onCancelar(e.id) }, modifier = Modifier.height(28.dp)) {
                    Icon(Icons.Rounded.Close, "cancelar", tint = Palette.TextMuted)
                }
            }
        }

        if (e.estado == "recebendo") {
            Spacer(Modifier.height(Spacing.sm))
            ProgressBar(percent = e.percent, cor = cor)
        }

        Spacer(Modifier.height(Spacing.xs))
        Text(
            buildString {
                if (e.estado == "recebendo") {
                    append("${tamanhoLegivel(e.recebido)} de ${tamanhoLegivel(e.tamanho)}")
                } else {
                    append(tamanhoLegivel(e.tamanho))
                }
                if (e.origem.isNotBlank()) append(" · de ${e.origem}")
                if (e.mensagem.isNotBlank()) append(" · ${e.mensagem}")
            },
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextMuted,
        )
    }
}
