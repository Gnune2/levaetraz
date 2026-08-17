package com.levaetraz.model

/** Estado do WebSocket, para a UI dizer se está falando com o PC ou não. */
enum class EstadoConexao { DESCONECTADO, CONECTANDO, CONECTADO, ERRO }

/** O que chega pelo WebSocket. */
sealed interface EventoWs {
    /** Snapshot completo, mandado a cada (re)conexão. */
    data class Tudo(
        val envios: List<Envio>,
        val resumo: ResumoTransferencia,
        val hostname: String,
        val versao: String,
    ) : EventoWs

    data class UmEnvio(val envio: Envio) : EventoWs
    data class Resumo(val resumo: ResumoTransferencia) : EventoWs

    /** Algo mudou nas pastas do PC. Quem estiver listando que recarregue. */
    data object ListaMudou : EventoWs

    data class Status(val texto: String, val nivel: String) : EventoWs
}
