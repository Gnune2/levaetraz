package com.levaetraz.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Fingerprint
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing

/**
 * Trava do app. Aparece a cada abertura enquanto a digital não passar.
 *
 * Se a biometria falhar (dedo molhado, sensor sujo, digital removida), o
 * usuário pode destravar com a senha do servidor — sem isso ficaria preso do
 * lado de fora do próprio app.
 */
@Composable
fun LockScreen(
    recusado: Boolean,
    verificandoSenha: Boolean,
    erroSenha: String?,
    onTentar: () -> Unit,
    onSenha: (String) -> Unit,
) {
    val haptics = LocalHaptics.current
    val transicao = rememberInfiniteTransition(label = "trava")
    val pulso by transicao.animateFloat(
        initialValue = 0.45f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "pulso",
    )

    var usandoSenha by remember { mutableStateOf(false) }
    var senha by remember { mutableStateOf("") }
    var verSenha by remember { mutableStateOf(false) }

    Box(
        Modifier.fillMaxSize().background(Palette.Bg).imePadding(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(Spacing.xl),
        ) {
            Icon(
                Icons.Rounded.Fingerprint,
                contentDescription = null,
                tint = if (recusado) Palette.Err else Palette.Accent,
                modifier = Modifier.size(72.dp).alpha(if (recusado) 1f else pulso),
            )

            Spacer(Modifier.height(Spacing.xl))
            Text(
                "levaetraz",
                style = MaterialTheme.typography.titleLarge,
                color = Palette.Text,
            )
            Spacer(Modifier.height(Spacing.sm))
            Text(
                when {
                    usandoSenha -> "digite a senha do servidor"
                    recusado -> "não deu pra confirmar sua identidade"
                    else -> "confirme sua digital para continuar"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (recusado && !usandoSenha) Palette.Err else Palette.TextMuted,
                textAlign = TextAlign.Center,
            )

            // ── alternativa por senha ────────────────────────
            AnimatedVisibility(
                visible = usandoSenha,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut(),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Spacer(Modifier.height(Spacing.lg))
                    CampoEscuro(
                        valor = senha,
                        onValorMudou = { senha = it },
                        rotulo = "senha do servidor",
                        senha = !verSenha,
                        teclado = KeyboardType.Password,
                        modifier = Modifier.fillMaxWidth(),
                        trailing = {
                            Icon(
                                if (verSenha) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility,
                                contentDescription = if (verSenha) "esconder" else "mostrar",
                                tint = Palette.TextDim,
                                modifier = Modifier
                                    .width(40.dp)
                                    .clickable { verSenha = !verSenha }
                                    .padding(10.dp),
                            )
                        },
                    )
                    if (erroSenha != null) {
                        Spacer(Modifier.height(Spacing.sm))
                        Text(
                            erroSenha,
                            style = MaterialTheme.typography.bodyMedium,
                            color = Palette.Err,
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xl))
            Button(
                onClick = {
                    haptics.confirm()
                    if (usandoSenha) onSenha(senha) else onTentar()
                },
                enabled = !verificandoSenha && (!usandoSenha || senha.isNotBlank()),
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Palette.Accent,
                    contentColor = Palette.Bg,
                    disabledContainerColor = Palette.SurfaceHigh,
                    disabledContentColor = Palette.TextMuted,
                ),
            ) {
                if (verificandoSenha) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Palette.Bg, strokeWidth = 2.dp,
                    )
                } else {
                    Text(
                        when {
                            usandoSenha -> "DESBLOQUEAR"
                            recusado -> "TENTAR A DIGITAL DE NOVO"
                            else -> "USAR DIGITAL"
                        },
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            Spacer(Modifier.height(Spacing.md))
            Text(
                if (usandoSenha) "voltar para a digital" else "usar a senha do servidor",
                style = MaterialTheme.typography.labelLarge,
                color = Palette.TextDim,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable {
                        haptics.tick()
                        usandoSenha = !usandoSenha
                        senha = ""
                        if (!usandoSenha) onTentar()
                    }
                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
            )

            if (usandoSenha) {
                Spacer(Modifier.height(Spacing.sm))
                Text(
                    "a senha é conferida no PC — precisa estar conectado",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
