package com.levaetraz.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing

/** Rótulo de seção: mono, pequeno, apagado. Substitui divisores e caixas. */
@Composable
fun SectionLabel(texto: String, modifier: Modifier = Modifier) {
    Text(
        text = texto.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = Palette.TextMuted,
        modifier = modifier.padding(bottom = Spacing.sm),
    )
}

/** Selo colorido do modo (VIDEO, ER, GEL…). */
@Composable
fun ModeBadge(texto: String, cor: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(cor.copy(alpha = 0.14f))
            .border(1.dp, cor.copy(alpha = 0.35f), RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(texto, style = MaterialTheme.typography.labelSmall, color = cor)
    }
}

/** Chip de opção/seleção. Um só componente para toggles e escolha de modo. */
@Composable
fun Chip(
    texto: String,
    selecionado: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    corAtiva: Color = Palette.Accent,
    habilitado: Boolean = true,
) {
    val alvoFundo = when {
        !habilitado -> Palette.Surface
        selecionado -> corAtiva.copy(alpha = 0.16f)
        else -> Palette.Surface
    }
    val alvoBorda = when {
        !habilitado -> Palette.BorderSoft
        selecionado -> corAtiva.copy(alpha = 0.55f)
        else -> Palette.Border
    }
    val alvoTexto = when {
        !habilitado -> Palette.TextMuted
        selecionado -> corAtiva
        else -> Palette.TextDim
    }

    val fundo by animateColorAsState(alvoFundo, tween(160), label = "chipBg")
    val borda by animateColorAsState(alvoBorda, tween(160), label = "chipBorder")
    val texto0 by animateColorAsState(alvoTexto, tween(160), label = "chipFg")

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(fundo)
            .border(1.dp, borda, RoundedCornerShape(8.dp))
            .clickable(enabled = habilitado, onClick = onClick)
            .padding(horizontal = Spacing.md, vertical = Spacing.sm),
    ) {
        Text(
            texto,
            style = MaterialTheme.typography.labelLarge,
            color = texto0,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Barra de progresso fina e animada.
 *
 * A interpolação é o detalhe que faz o progresso parecer fluido em vez de
 * saltar de 6% para 100% conforme os eventos chegam do servidor.
 */
@Composable
fun ProgressBar(
    percent: Float,
    cor: Color,
    modifier: Modifier = Modifier,
    altura: androidx.compose.ui.unit.Dp = 3.dp,
    indeterminado: Boolean = false,
) {
    val alvo = (percent / 100f).coerceIn(0f, 1f)
    val animado by animateFloatAsState(
        targetValue = alvo,
        animationSpec = tween(durationMillis = 380),
        label = "progresso",
    )
    val corAnimada by animateColorAsState(cor, tween(300), label = "progressoCor")

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(altura)
            .clip(CircleShape)
            .background(Palette.SurfaceHigh),
    ) {
        Box(
            Modifier
                .fillMaxWidth(if (indeterminado) 0.28f else animado)
                .height(altura)
                .clip(CircleShape)
                .background(corAnimada),
        )
    }
}

/** Indicador de conexão: um ponto que muda de cor. */
@Composable
fun StatusDot(cor: Color, tamanho: androidx.compose.ui.unit.Dp = 7.dp) {
    val animada by animateColorAsState(cor, tween(300), label = "dot")
    Box(Modifier.size(tamanho).clip(CircleShape).background(animada))
}

/** Linha clicável usada em listas de configuração e no picker de pastas. */
@Composable
fun RowItem(
    titulo: String,
    modifier: Modifier = Modifier,
    subtitulo: String? = null,
    icone: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = Spacing.md, vertical = Spacing.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        icone?.invoke()
        Box(Modifier.weight(1f)) {
            androidx.compose.foundation.layout.Column {
                Text(
                    titulo,
                    style = MaterialTheme.typography.bodyLarge,
                    color = Palette.Text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitulo != null) {
                    Text(
                        subtitulo,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Palette.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                    )
                }
            }
        }
        trailing?.invoke()
    }
}
