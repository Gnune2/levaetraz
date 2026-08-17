package com.levaetraz.data

import android.util.Log
import com.levaetraz.model.Envio
import com.levaetraz.model.EstadoConexao
import com.levaetraz.model.EventoWs
import com.levaetraz.model.ResumoTransferencia
import com.levaetraz.model.Servidor
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.min

private const val TAG = "levaetraz.ws"

/**
 * WebSocket de eventos com reconexão automática.
 *
 * O servidor manda um snapshot completo a cada conexão, então reconectar já
 * ressincroniza tudo — não há replay de deltas para gerenciar.
 */
class WsClient(private val http: OkHttpClient) {

    private val _estado = MutableStateFlow(EstadoConexao.DESCONECTADO)
    val estado: StateFlow<EstadoConexao> = _estado.asStateFlow()

    private val _eventos = MutableSharedFlow<EventoWs>(
        replay = 0, extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val eventos: SharedFlow<EventoWs> = _eventos.asSharedFlow()

    private val _ultimoErro = MutableStateFlow<String?>(null)
    val ultimoErro: StateFlow<String?> = _ultimoErro.asStateFlow()

    private var socket: WebSocket? = null
    private var alvo: Servidor? = null
    private val ativo = AtomicBoolean(false)
    private var tentativas = 0
    private var reconector: Thread? = null

    fun conectar(novoAlvo: Servidor) {
        if (alvo == novoAlvo && ativo.get()) return
        desconectar()
        alvo = novoAlvo
        ativo.set(true)
        tentativas = 0
        abrir()
    }

    fun desconectar() {
        ativo.set(false)
        reconector?.interrupt()
        reconector = null
        socket?.close(1000, "fechando")
        socket = null
        _estado.value = EstadoConexao.DESCONECTADO
    }

    /** Força uma tentativa imediata, ignorando o backoff (botão "tentar de novo"). */
    fun reconectarAgora() {
        val a = alvo ?: return
        tentativas = 0
        conectar(a)
    }

    private fun abrir() {
        val a = alvo ?: return
        if (!ativo.get()) return

        _estado.value = EstadoConexao.CONECTANDO
        val req = Request.Builder().url("${a.base}/ws?token=${a.token}").build()
        socket = http.newWebSocket(req, Ouvinte())
    }

    private fun agendarReconexao() {
        if (!ativo.get()) return
        // backoff exponencial 1s -> 30s
        val espera = min(30_000L, 1_000L shl min(tentativas, 5))
        tentativas++
        reconector?.interrupt()
        reconector = thread(isDaemon = true, name = "ws-reconnect") {
            try {
                Thread.sleep(espera)
                abrir()
            } catch (_: InterruptedException) {
                // desconexão pedida enquanto esperava
            }
        }
    }

    private inner class Ouvinte : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            tentativas = 0
            _ultimoErro.value = null
            _estado.value = EstadoConexao.CONECTADO
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val ev = runCatching { parse(text) }.getOrElse {
                Log.w(TAG, "evento ilegível: ${it.message}")
                null
            } ?: return
            _eventos.tryEmit(ev)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            socket = null
            if (!ativo.get()) return

            // 4401 = token recusado. Reconectar não resolve, então para aqui.
            if (response?.code == 401 || response?.code == 4401) {
                _ultimoErro.value = "token inválido — refaça o pareamento"
                _estado.value = EstadoConexao.ERRO
                ativo.set(false)
                return
            }

            _ultimoErro.value = t.message ?: "falha de conexão"
            _estado.value = EstadoConexao.ERRO
            agendarReconexao()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            socket = null
            if (!ativo.get()) {
                _estado.value = EstadoConexao.DESCONECTADO
                return
            }
            if (code == 4401) {
                _ultimoErro.value = "token inválido — refaça o pareamento"
                _estado.value = EstadoConexao.ERRO
                ativo.set(false)
                return
            }
            _estado.value = EstadoConexao.ERRO
            agendarReconexao()
        }
    }

    private fun parse(texto: String): EventoWs? {
        val raiz = AppJson.parseToJsonElement(texto).jsonObject
        return when (raiz["type"]?.jsonPrimitive?.content) {
            "snapshot" -> EventoWs.Tudo(
                envios = raiz["envios"]
                    ?.let { AppJson.decodeFromJsonElement<List<Envio>>(it) }.orEmpty(),
                resumo = raiz["resumo"]
                    ?.let { AppJson.decodeFromJsonElement<ResumoTransferencia>(it) }
                    ?: ResumoTransferencia(),
                hostname = (raiz["server"] as? JsonObject)?.get("hostname")
                    ?.jsonPrimitive?.content.orEmpty(),
                versao = (raiz["server"] as? JsonObject)?.get("version")
                    ?.jsonPrimitive?.content.orEmpty(),
            )

            "envio" -> raiz["envio"]?.let {
                EventoWs.UmEnvio(AppJson.decodeFromJsonElement<Envio>(it))
            }

            "resumo" -> EventoWs.Resumo(
                AppJson.decodeFromJsonElement<ResumoTransferencia>(raiz))

            // O servidor avisa que a pasta mudou sem dizer o quê: quem lista
            // é a tela, que sabe onde o usuário está navegando agora.
            "lista" -> EventoWs.ListaMudou

            "status" -> EventoWs.Status(
                texto = raiz["text"]?.jsonPrimitive?.content.orEmpty(),
                nivel = raiz["level"]?.jsonPrimitive?.content ?: "ok",
            )

            else -> null
        }
    }
}
