package com.levaetraz.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.levaetraz.model.EstadoConexao
import com.levaetraz.ui.components.StatusDot
import com.levaetraz.ui.theme.Palette

/**
 * Bolinha + rótulo do estado do WebSocket, no canto da barra.
 *
 * Clicar força uma tentativa imediata: o backoff pode estar em 30 segundos
 * quando o usuário acabou de religar o Wi-Fi, e esperar por nada irrita.
 */
@Composable
fun IndicadorConexao(conexao: EstadoConexao, onReconectar: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clickable(enabled = conexao != EstadoConexao.CONECTADO) { onReconectar() }
            .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        StatusDot(corConexao(conexao))
        Spacer(Modifier.padding(horizontal = 3.dp))
        Text(
            rotuloConexao(conexao),
            style = MaterialTheme.typography.labelMedium,
            color = Palette.TextMuted,
            maxLines = 1,
        )
    }
}

fun corConexao(c: EstadoConexao) = when (c) {
    EstadoConexao.CONECTADO -> Palette.Ok
    EstadoConexao.CONECTANDO -> Palette.Warn
    EstadoConexao.ERRO -> Palette.Err
    EstadoConexao.DESCONECTADO -> Palette.TextMuted
}

fun rotuloConexao(c: EstadoConexao) = when (c) {
    EstadoConexao.CONECTADO -> "conectado"
    EstadoConexao.CONECTANDO -> "conectando…"
    EstadoConexao.ERRO -> "sem conexão · tocar para tentar"
    EstadoConexao.DESCONECTADO -> "desconectado"
}
