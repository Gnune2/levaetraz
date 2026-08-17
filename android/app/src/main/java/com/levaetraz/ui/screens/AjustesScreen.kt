package com.levaetraz.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.levaetraz.model.Servidor
import com.levaetraz.data.PrefsLocais
import com.levaetraz.model.Preferencias
import com.levaetraz.ui.components.Chip
import com.levaetraz.ui.components.RowItem
import com.levaetraz.ui.components.SectionLabel
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing
import com.levaetraz.vm.AjustesState

@Composable
fun AjustesScreen(
    estado: AjustesState,
    servidor: Servidor,
    travaLigada: Boolean,
    temSensor: Boolean,
    onTrava: (Boolean) -> Unit,
    onPrefsServidor: (Preferencias) -> Unit,
    onPrefsLocais: (PrefsLocais) -> Unit,
    onRevogar: (String) -> Unit,
    onEsquecerBaixados: () -> Unit,
    onDesconectar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val haptics = LocalHaptics.current

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Spacing.lg),
    ) {
        // ── servidor ─────────────────────────────────────────
        SectionLabel("servidor")
        Bloco {
            RowItem("endereço", subtitulo = "${servidor.host}:${servidor.porta}")
            if (estado.hostname.isNotBlank()) RowItem("máquina", subtitulo = estado.hostname)
            if (estado.versaoServidor.isNotBlank()) RowItem("versão", subtitulo = estado.versaoServidor)
        }

        Espaco()

        // ── segurança ────────────────────────────────────────
        SectionLabel("segurança")
        Bloco {
            Interruptor(
                titulo = "pedir a digital ao abrir",
                detalhe = if (temSensor) "e sempre que o app volta do segundo plano"
                          else "este aparelho não tem digital cadastrada",
                ligado = travaLigada && temSensor,
                habilitado = temSensor,
            ) { haptics.tick(); onTrava(it) }
        }
        Text(
            "A bandeja de apps recentes já mostra a tela em branco, e screenshot "
                + "está bloqueado — isso não é configurável.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
            modifier = Modifier.padding(top = Spacing.xs),
        )

        Espaco()

        // ── como o PC recebe ─────────────────────────────────
        SectionLabel("como o PC recebe")
        Bloco {
            Interruptor(
                titulo = "não receber o mesmo arquivo duas vezes",
                detalhe = "compara o conteúdo, não o nome",
                ligado = estado.preferencias.pularDuplicados,
            ) { haptics.tick(); onPrefsServidor(estado.preferencias.copy(pularDuplicados = it)) }

            Interruptor(
                titulo = "separar por tipo",
                detalhe = "joga em Imagens / Vídeos / Documentos",
                ligado = estado.preferencias.organizarPorTipo,
            ) { haptics.tick(); onPrefsServidor(estado.preferencias.copy(organizarPorTipo = it)) }
        }
        Text(
            "Estes dois valem para o servidor inteiro — mudam também para o painel "
                + "web e para qualquer outro aparelho.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
            modifier = Modifier.padding(top = Spacing.xs),
        )

        Espaco()

        // ── como o celular baixa ─────────────────────────────
        SectionLabel("como este celular baixa")
        Bloco {
            Interruptor(
                titulo = "pular o que já está aqui",
                detalhe = "${estado.totalBaixados} arquivo(s) no registro",
                ligado = estado.locais.pularJaBaixados,
            ) { haptics.tick(); onPrefsLocais(estado.locais.copy(pularJaBaixados = it)) }

            RowItem("pasta no celular", subtitulo = "Download/${estado.locais.pastaNoCelular}")
        }
        Row(Modifier.padding(top = Spacing.sm)) {
            Chip("esquecer o que já baixei", false, onClick = {
                haptics.error(); onEsquecerBaixados()
            })
        }

        Espaco()

        // ── dispositivos ─────────────────────────────────────
        SectionLabel("dispositivos conectados")
        Bloco {
            if (estado.sessoes.isEmpty()) {
                Text(
                    "nenhum",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextMuted,
                    modifier = Modifier.padding(vertical = Spacing.sm),
                )
            }
            estado.sessoes.forEach { s ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = Spacing.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.dispositivo,
                        style = MaterialTheme.typography.bodyLarge,
                        color = Palette.Text,
                        maxLines = 1,
                        overflow = TextOverflow.MiddleEllipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Chip("desconectar", false, onClick = { haptics.error(); onRevogar(s.id) })
                }
            }
        }

        Espaco()

        Chip(
            "DESCONECTAR ESTE APARELHO", false,
            onClick = { haptics.error(); onDesconectar() },
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            "Apaga o pareamento deste celular. Para voltar, leia um QR novo no painel.",
            style = MaterialTheme.typography.bodyMedium,
            color = Palette.TextMuted,
            modifier = Modifier.padding(top = Spacing.xs, bottom = Spacing.xxl),
        )
    }
}

@Composable
private fun Bloco(conteudo: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface, RoundedCornerShape(12.dp))
            .padding(horizontal = Spacing.md, vertical = Spacing.xs),
        content = conteudo,
    )
}

@Composable
private fun Espaco() = Spacer(Modifier.height(Spacing.xl))

@Composable
private fun Interruptor(
    titulo: String,
    detalhe: String,
    ligado: Boolean,
    habilitado: Boolean = true,
    onMudar: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                titulo,
                style = MaterialTheme.typography.bodyLarge,
                color = if (habilitado) Palette.Text else Palette.TextMuted,
            )
            Text(
                detalhe,
                style = MaterialTheme.typography.bodyMedium,
                color = Palette.TextMuted,
            )
        }
        Switch(
            checked = ligado,
            onCheckedChange = onMudar,
            enabled = habilitado,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Palette.Bg,
                checkedTrackColor = Palette.Accent,
                uncheckedThumbColor = Palette.TextMuted,
                uncheckedTrackColor = Palette.SurfaceHigh,
                uncheckedBorderColor = Palette.Border,
            ),
        )
    }
}
