package com.levaetraz.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import com.levaetraz.model.EstadoAuth
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.levaetraz.ui.components.SectionLabel
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing

/**
 * Tela de pareamento — o primeiro contato com o servidor.
 *
 * O caminho rápido é ler o QR do painel web (aba celular); o campo manual
 * existe para quando o PC está longe ou o QR não aparece.
 */
@Composable
fun ConnectScreen(
    pareando: Boolean,
    servidorSondado: EstadoAuth?,
    erro: String?,
    onSondar: (host: String, porta: Int) -> Unit,
    onEntrar: (host: String, porta: Int, senha: String) -> Unit,
    onAbrirScanner: () -> Unit,
) {
    val haptics = LocalHaptics.current
    var host by remember { mutableStateOf("") }
    var porta by remember { mutableStateOf("8765") }
    var senha by remember { mutableStateOf("") }
    var verSenha by remember { mutableStateOf(false) }

    val enderecoOk = host.isNotBlank() && porta.isNotBlank()
    val achou = servidorSondado != null
    val podeEntrar = achou && senha.isNotBlank() && !pareando

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(Spacing.xl),
        verticalArrangement = Arrangement.Center,
    ) {
        LogoPulsante()

        Spacer(Modifier.height(Spacing.sm))
        Text(
            "conecte ao servidor rodando no seu PC",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
        )

        Spacer(Modifier.height(Spacing.xxl))

        Button(
            onClick = { haptics.confirm(); onAbrirScanner() },
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.Accent, contentColor = Palette.Bg,
            ),
        ) {
            Icon(Icons.Rounded.QrCodeScanner, contentDescription = null)
            Spacer(Modifier.width(Spacing.sm))
            Text("LER QR CODE", style = MaterialTheme.typography.labelLarge)
        }

        Spacer(Modifier.height(Spacing.lg))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(Palette.Border))
            Text(
                "  ou digite  ",
                style = MaterialTheme.typography.labelSmall,
                color = Palette.TextMuted,
            )
            Box(Modifier.weight(1f).height(1.dp).background(Palette.Border))
        }
        Spacer(Modifier.height(Spacing.lg))

        SectionLabel("servidor")
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            CampoEscuro(
                valor = host,
                onValorMudou = { host = it.trim() },
                rotulo = "IP do PC",
                placeholder = "192.168.0.10",
                teclado = KeyboardType.Uri,
                modifier = Modifier.weight(2f),
            )
            CampoEscuro(
                valor = porta,
                onValorMudou = { porta = it.filter(Char::isDigit).take(5) },
                rotulo = "porta",
                placeholder = "8765",
                teclado = KeyboardType.Number,
                modifier = Modifier.weight(1f),
            )
        }

        AnimatedVisibility(visible = achou, enter = fadeIn(), exit = fadeOut()) {
            Column {
                Spacer(Modifier.height(Spacing.md))
                CampoEscuro(
                    valor = senha,
                    onValorMudou = { senha = it },
                    rotulo = "senha",
                    placeholder = "a senha que você definiu no PC",
                    senha = !verSenha,
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
            }
        }

        AnimatedVisibility(visible = erro != null, enter = fadeIn(), exit = fadeOut()) {
            Text(
                erro.orEmpty(),
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.Err,
                modifier = Modifier.padding(top = Spacing.md),
            )
        }

        Spacer(Modifier.height(Spacing.xl))
        Button(
            onClick = {
                haptics.confirm()
                val p = porta.toIntOrNull() ?: 8765
                if (achou) onEntrar(host, p, senha) else onSondar(host, p)
            },
            enabled = (if (achou) podeEntrar else enderecoOk) && !pareando,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Palette.Accent,
                contentColor = Palette.Bg,
                disabledContainerColor = Palette.SurfaceHigh,
                disabledContentColor = Palette.TextMuted,
            ),
        ) {
            if (pareando) {
                CircularProgressIndicator(
                    modifier = Modifier.height(18.dp).width(18.dp),
                    color = Palette.Bg, strokeWidth = 2.dp,
                )
            } else {
                Text(
                    if (achou) "ENTRAR" else "PROCURAR SERVIDOR",
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }

        Spacer(Modifier.height(Spacing.lg))
        Text(
            when {
                achou && servidorSondado?.temSenha == false ->
                    "servidor encontrado, mas sem senha — rode no PC:\npython main.py --senha"
                achou -> "servidor encontrado (v${servidorSondado?.version})"
                else -> "no PC: ./instalar.sh · fora de casa, use o IP do Tailscale"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (achou) Palette.Accent else Palette.TextMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun LogoPulsante() {
    val transicao = rememberInfiniteTransition(label = "logo")
    val brilho by transicao.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "brilho",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "leva",
            style = MaterialTheme.typography.displaySmall,
            color = Palette.Text,
        )
        Text(
            "etraz",
            style = MaterialTheme.typography.displaySmall,
            color = Palette.Accent,
            modifier = Modifier.alpha(brilho),
        )
    }
}

@Composable
fun CampoEscuro(
    valor: String,
    onValorMudou: (String) -> Unit,
    rotulo: String,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    teclado: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    senha: Boolean = false,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = onValorMudou,
        modifier = modifier,
        label = { Text(rotulo, style = MaterialTheme.typography.labelMedium) },
        placeholder = {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = Palette.TextMuted)
        },
        singleLine = singleLine,
        trailingIcon = trailing,
        keyboardOptions = KeyboardOptions(
            keyboardType = if (senha) KeyboardType.Password else teclado,
        ),
        visualTransformation =
            if (senha) PasswordVisualTransformation() else VisualTransformation.None,
        textStyle = MaterialTheme.typography.bodyLarge,
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Palette.Surface,
            unfocusedContainerColor = Palette.Surface,
            focusedBorderColor = Palette.Accent,
            unfocusedBorderColor = Palette.Border,
            focusedTextColor = Palette.Text,
            unfocusedTextColor = Palette.Text,
            cursorColor = Palette.Accent,
            focusedLabelColor = Palette.Accent,
            unfocusedLabelColor = Palette.TextMuted,
        ),
    )
}