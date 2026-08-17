package com.levaetraz.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Espelha os contratos do servidor (servidor/esquemas.py).
 *
 * Só os campos que a tela usa: `ignoreUnknownKeys` está ligado, então o
 * servidor pode ganhar campos novos sem quebrar uma versão antiga do app.
 */

// ── servidor ────────────────────────────────────────────────
@Serializable
data class Servidor(
    val host: String = "",
    val porta: Int = 8765,
    val token: String = "",
) {
    val base: String get() = "http://$host:$porta"
    val configurado: Boolean get() = host.isNotBlank() && token.isNotBlank()
}

@Serializable
data class Saude(val app: String = "", val version: String = "",
                 @SerialName("requires_auth") val exigeAuth: Boolean = true)

@Serializable
data class EstadoAuth(
    @SerialName("tem_senha") val temSenha: Boolean = false,
    @SerialName("pareamento_ativo") val pareamentoAtivo: Boolean = false,
    val version: String = "",
)

@Serializable
data class PedidoLogin(val senha: String, val dispositivo: String = "celular")

@Serializable
data class PedidoPareamento(val codigo: String, val dispositivo: String = "celular")

@Serializable
data class RespostaLogin(val token: String, val sessao: Sessao? = null)

@Serializable
data class Sessao(
    val id: String = "",
    val dispositivo: String = "",
    @SerialName("criado_em") val criadoEm: Double = 0.0,
    @SerialName("ultimo_uso") val ultimoUso: Double = 0.0,
)

@Serializable
data class RespostaSessoes(val sessoes: List<Sessao> = emptyList())

@Serializable
data class InfoServidor(
    val app: String = "levaetraz",
    val version: String = "",
    val hostname: String = "",
    val plataforma: String = "",
)

// ── arquivos no PC ──────────────────────────────────────────
@Serializable
data class ItemArquivo(
    val nome: String = "",
    val caminho: String = "",
    val tipo: String = "arquivo",          // pasta|imagem|video|audio|documento|arquivo
    val tamanho: Long = 0,
    val modificado: Double = 0.0,
    val thumb: Boolean = false,
) {
    val ehPasta: Boolean get() = tipo == "pasta"
}

@Serializable
data class Espaco(val total: Long = 0, val livre: Long = 0, val usado: Long = 0)

@Serializable
data class ListagemArquivos(
    val caminho: String = "",
    val pai: String? = null,
    val itens: List<ItemArquivo> = emptyList(),
    val arquivos: Int = 0,
    val bytes: Long = 0,
    val espaco: Espaco = Espaco(),
    val erro: String? = null,
)

@Serializable
data class PastaCompartilhada(
    val nome: String = "",
    val caminho: String = "",
    val existe: Boolean = true,
    val espaco: Espaco = Espaco(),
)

@Serializable
data class RespostaPastas(val pastas: List<PastaCompartilhada> = emptyList())

@Serializable
data class PedidoApagar(val caminhos: List<String>)

@Serializable
data class PedidoNovaPasta(val onde: String, val nome: String)

// ── envios (celular → PC) ───────────────────────────────────
@Serializable
data class PedidoAbrirEnvio(
    val nome: String,
    val tamanho: Long,
    val destino: String? = null,
    val sha256: String? = null,
    @SerialName("modificado_em") val modificadoEm: Double? = null,
)

@Serializable
data class AberturaEnvio(
    val id: String = "",
    val offset: Long = 0,
    val estado: String = "aguardando",
    @SerialName("caminho_final") val caminhoFinal: String = "",
    val mensagem: String = "",
) {
    val duplicado: Boolean get() = estado == "duplicado"
}

@Serializable
data class Envio(
    val id: String = "",
    val nome: String = "",
    val tamanho: Long = 0,
    val recebido: Long = 0,
    val estado: String = "aguardando",
    val destino: String = "",
    @SerialName("caminho_final") val caminhoFinal: String = "",
    val mensagem: String = "",
    val origem: String = "",
    @SerialName("criado_em") val criadoEm: Double = 0.0,
    val percent: Float = 0f,
) {
    val emCurso: Boolean get() = estado == "recebendo" || estado == "aguardando"
}

@Serializable
data class ResumoTransferencia(
    val ativos: Int = 0,
    val percent: Float = 0f,
    @SerialName("bytes_por_s") val bytesPorS: Double = 0.0,
    val texto: String = "",
)

@Serializable
data class RespostaEnvios(
    val envios: List<Envio> = emptyList(),
    val resumo: ResumoTransferencia = ResumoTransferencia(),
)

// ── preferências do servidor ────────────────────────────────
@Serializable
data class Preferencias(
    @SerialName("destino_padrao") val destinoPadrao: String = "",
    @SerialName("pular_duplicados") val pularDuplicados: Boolean = true,
    @SerialName("organizar_por_tipo") val organizarPorTipo: Boolean = false,
    @SerialName("manter_historico") val manterHistorico: Int = 200,
)

// ── estado local, nunca serializado para o servidor ─────────
/** Um arquivo do celular esperando (ou já) subir. */
data class EnvioLocal(
    val uri: String,
    val nome: String,
    val tamanho: Long,
    val enviados: Long = 0,
    val estado: EstadoLocal = EstadoLocal.NA_FILA,
    val mensagem: String = "",
) {
    val percent: Float get() = if (tamanho > 0) enviados * 100f / tamanho else 0f
}

enum class EstadoLocal { NA_FILA, ENVIANDO, CONCLUIDO, DUPLICADO, ERRO, CANCELADO }

/** Um arquivo do PC esperando (ou já) descer. */
data class BaixaLocal(
    val caminho: String,
    val nome: String,
    val tamanho: Long,
    val baixados: Long = 0,
    val estado: EstadoLocal = EstadoLocal.NA_FILA,
    val mensagem: String = "",
) {
    val percent: Float get() = if (tamanho > 0) baixados * 100f / tamanho else 0f
}

// ── formatação ──────────────────────────────────────────────
/** Único formatador de bytes do app — a UI inteira passa por aqui. */
fun tamanhoLegivel(n: Long): String {
    if (n < 1024) return "$n B"
    val unidades = listOf("KB", "MB", "GB", "TB")
    var valor = n.toDouble() / 1024
    var i = 0
    while (valor >= 1024 && i < unidades.lastIndex) {
        valor /= 1024
        i++
    }
    return "%.1f %s".format(valor, unidades[i])
}

/** Atalho de leitura: `item.tamanhoLegivel` em vez de `tamanhoLegivel(item.tamanho)`. */
val ItemArquivo.tamanhoLegivel: String
    get() = if (ehPasta || tamanho <= 0) "" else tamanhoLegivel(tamanho)

/** "agora", "há 12 min", "há 3 h" — para carimbar histórico e sessões. */
fun tempoRelativo(epochSegundos: Double): String {
    if (epochSegundos <= 0) return "—"
    val minutos = ((System.currentTimeMillis() / 1000.0) - epochSegundos) / 60
    return when {
        minutos < 1 -> "agora"
        minutos < 60 -> "há ${minutos.toInt()} min"
        minutos < 60 * 24 -> "há ${(minutos / 60).toInt()} h"
        else -> "há ${(minutos / 60 / 24).toInt()} d"
    }
}
