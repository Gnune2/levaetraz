package com.levaetraz.data

import com.levaetraz.model.AberturaEnvio
import com.levaetraz.model.Envio
import com.levaetraz.model.EstadoAuth
import com.levaetraz.model.InfoServidor
import com.levaetraz.model.ListagemArquivos
import com.levaetraz.model.PedidoAbrirEnvio
import com.levaetraz.model.PedidoApagar
import com.levaetraz.model.PedidoLogin
import com.levaetraz.model.PedidoNovaPasta
import com.levaetraz.model.PedidoPareamento
import com.levaetraz.model.Preferencias
import com.levaetraz.model.RespostaEnvios
import com.levaetraz.model.RespostaLogin
import com.levaetraz.model.RespostaPastas
import com.levaetraz.model.RespostaSessoes
import com.levaetraz.model.Saude
import com.levaetraz.model.Servidor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit

/** Erro vindo do servidor, já com a mensagem legível que a UI mostra. */
class ApiException(val code: Int, override val message: String) : IOException(message)

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

class ApiClient(val http: OkHttpClient = padrao()) {

    companion object {
        val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        val BINARIO = "application/octet-stream".toMediaType()

        fun padrao(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            // Escrita sem prazo: um vídeo de 2 GB numa rede ruim leva mais que
            // qualquer timeout razoável, e cortar no meio desperdiçaria tudo
            // que já subiu. Quem interrompe é o usuário ou a queda de conexão.
            .writeTimeout(0, TimeUnit.MILLISECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    // ── verbos ───────────────────────────────────────────────
    private suspend inline fun <reified T> get(
        s: Servidor,
        caminho: String,
        query: Map<String, String> = emptyMap(),
    ): T = executar(s) {
        val url = (s.base + caminho).toHttpUrl().newBuilder().apply {
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        Request.Builder().url(url).get()
    }

    private suspend inline fun <reified T, reified B> enviar(
        s: Servidor,
        caminho: String,
        corpo: B?,
        metodo: String = "POST",
    ): T = executar(s) {
        val payload = (corpo?.let { AppJson.encodeToString(it) } ?: "{}")
            .toRequestBody(JSON_MEDIA)
        Request.Builder().url(s.base + caminho).method(metodo, payload)
    }

    private suspend inline fun <reified T> executar(
        s: Servidor,
        crossinline construir: () -> Request.Builder,
    ): T = withContext(Dispatchers.IO) {
        val req = construir().header("X-Auth-Token", s.token).build()
        http.newCall(req).execute().use { resp ->
            val texto = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, extrairDetalhe(texto, resp.code))
            if (T::class == Unit::class) Unit as T else AppJson.decodeFromString<T>(texto)
        }
    }

    /** O FastAPI devolve {"detail": "..."} ou uma lista de erros do Pydantic. */
    fun extrairDetalhe(corpo: String, code: Int): String = runCatching {
        val raiz = AppJson.parseToJsonElement(corpo)
        val detalhe = (raiz as? kotlinx.serialization.json.JsonObject)?.get("detail")
            ?: return@runCatching "erro $code"

        when (detalhe) {
            is kotlinx.serialization.json.JsonPrimitive -> detalhe.content
            is kotlinx.serialization.json.JsonArray ->
                detalhe.mapNotNull { item ->
                    (item as? kotlinx.serialization.json.JsonObject)?.get("msg")
                        ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                }.joinToString("; ").ifBlank { "requisição inválida" }
            else -> "erro $code"
        }
    }.getOrElse {
        when (code) {
            401 -> "sessão inválida ou expirada"
            403 -> "sem permissão"
            404 -> "não encontrado"
            else -> "erro $code"
        }
    }

    // ── sem sessão ───────────────────────────────────────────
    suspend fun saude(host: String, porta: Int): Saude =
        semSessao("http://$host:$porta/api/health")

    suspend fun estadoAuth(host: String, porta: Int): EstadoAuth =
        semSessao("http://$host:$porta/api/auth/status")

    suspend fun login(host: String, porta: Int, senha: String, dispositivo: String): RespostaLogin =
        postSemSessao("http://$host:$porta/api/auth/login", PedidoLogin(senha, dispositivo))

    suspend fun parear(host: String, porta: Int, codigo: String, dispositivo: String): RespostaLogin =
        postSemSessao("http://$host:$porta/api/auth/pair", PedidoPareamento(codigo, dispositivo))

    private suspend inline fun <reified T> semSessao(url: String): T = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(url).get().build()).execute().use { resp ->
            val texto = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw ApiException(resp.code, extrairDetalhe(texto, resp.code))
            AppJson.decodeFromString<T>(texto)
        }
    }

    private suspend inline fun <reified T, reified B> postSemSessao(url: String, corpo: B): T =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .post(AppJson.encodeToString(corpo).toRequestBody(JSON_MEDIA))
                .build()
            http.newCall(req).execute().use { resp ->
                val texto = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) throw ApiException(resp.code, extrairDetalhe(texto, resp.code))
                AppJson.decodeFromString<T>(texto)
            }
        }

    // ── sessão ───────────────────────────────────────────────
    suspend fun verificarSenha(s: Servidor, senha: String): Unit =
        enviar<Unit, PedidoLogin>(s, "/api/auth/verify", PedidoLogin(senha, "trava"))

    suspend fun sair(s: Servidor): Unit = enviar<Unit, Unit>(s, "/api/auth/logout", null)

    suspend fun sessoes(s: Servidor): RespostaSessoes = get(s, "/api/auth/sessions")

    suspend fun revogar(s: Servidor, id: String): Unit =
        enviar<Unit, Unit>(s, "/api/auth/sessions/$id", null, metodo = "DELETE")

    suspend fun info(s: Servidor): InfoServidor = get(s, "/api/info")

    suspend fun preferencias(s: Servidor): Preferencias = get(s, "/api/preferencias")

    suspend fun gravarPreferencias(s: Servidor, p: Preferencias): Preferencias =
        enviar(s, "/api/preferencias", p, metodo = "PUT")

    // ── arquivos ─────────────────────────────────────────────
    suspend fun listar(s: Servidor, caminho: String?): ListagemArquivos =
        get(s, "/api/arquivos", caminho?.let { mapOf("caminho" to it) } ?: emptyMap())

    suspend fun pastas(s: Servidor): RespostaPastas = get(s, "/api/arquivos/pastas")

    suspend fun novaPasta(s: Servidor, onde: String, nome: String): Unit =
        enviar<Unit, PedidoNovaPasta>(s, "/api/arquivos/pasta", PedidoNovaPasta(onde, nome))

    suspend fun apagar(s: Servidor, caminhos: List<String>): Unit =
        enviar<Unit, PedidoApagar>(s, "/api/arquivos/apagar", PedidoApagar(caminhos))

    /** URL da miniatura. Exige o header de token — ver [ImageLoaders]. */
    fun urlThumb(s: Servidor, caminho: String): String =
        urlDe(s, "/api/arquivos/thumb", caminho)

    /** URL do arquivo original, com suporte a Range (seek de vídeo). */
    fun urlArquivo(s: Servidor, caminho: String): String =
        urlDe(s, "/api/arquivos/baixar", caminho)

    private fun urlDe(s: Servidor, rota: String, caminho: String): String =
        (s.base + rota).toHttpUrl().newBuilder()
            .addQueryParameter("caminho", caminho)   // o OkHttp cuida do escape
            .build()
            .toString()

    // ── PC → celular ─────────────────────────────────────────
    /**
     * Baixa para um stream (MediaStore), reportando o progresso.
     *
     * Não carrega nada na memória: copia bloco a bloco direto do socket para o
     * arquivo. Um vídeo de 3 GB passa sem o app crescer um megabyte.
     */
    suspend fun baixar(
        s: Servidor,
        caminho: String,
        saida: OutputStream,
        cancelado: () -> Boolean = { false },
        progresso: (Long) -> Unit = {},
    ) = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(urlArquivo(s, caminho))
            .header("X-Auth-Token", s.token)
            .build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw ApiException(resp.code, "erro ${resp.code} ao baixar")
            val corpo = resp.body?.byteStream() ?: throw ApiException(resp.code, "resposta vazia")
            corpo.use { entrada ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    if (cancelado()) throw IOException("cancelado")
                    val lidos = entrada.read(buffer)
                    if (lidos < 0) break
                    saida.write(buffer, 0, lidos)
                    total += lidos
                    progresso(total)
                }
                saida.flush()
            }
        }
    }

    // ── celular → PC ─────────────────────────────────────────
    suspend fun abrirEnvio(s: Servidor, pedido: PedidoAbrirEnvio): AberturaEnvio =
        enviar(s, "/api/envios", pedido)

    /**
     * Manda os bytes a partir de [offset].
     *
     * O corpo lê do stream sob demanda — o OkHttp puxa conforme a rede aceita,
     * então a memória fica constante e o progresso reflete o que de fato saiu
     * do aparelho, não o que foi bufferizado.
     */
    suspend fun mandarBytes(
        s: Servidor,
        envioId: String,
        offset: Long,
        entrada: InputStream,
        restante: Long,
        cancelado: () -> Boolean = { false },
        progresso: (Long) -> Unit = {},
    ): Unit = withContext(Dispatchers.IO) {
        val corpo = object : RequestBody() {
            override fun contentType() = BINARIO
            override fun contentLength() = restante
            override fun writeTo(sink: BufferedSink) {
                val buffer = ByteArray(256 * 1024)
                var enviados = offset
                entrada.use { fonte ->
                    while (true) {
                        if (cancelado()) throw IOException("cancelado")
                        val lidos = fonte.read(buffer)
                        if (lidos < 0) break
                        sink.write(buffer, 0, lidos)
                        enviados += lidos
                        progresso(enviados)
                    }
                }
                sink.flush()
            }
        }
        val url = (s.base + "/api/envios/$envioId").toHttpUrl().newBuilder()
            .addQueryParameter("offset", offset.toString())
            .build()
        val req = Request.Builder().url(url).put(corpo)
            .header("X-Auth-Token", s.token).build()
        http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw ApiException(resp.code,
                    extrairDetalhe(resp.body?.string().orEmpty(), resp.code))
            }
        }
    }

    suspend fun fecharEnvio(s: Servidor, envioId: String): Envio =
        enviar(s, "/api/envios/$envioId/fim", null as Unit?)

    suspend fun cancelarEnvio(s: Servidor, envioId: String): Unit =
        enviar<Unit, Unit>(s, "/api/envios/$envioId", null, metodo = "DELETE")

    suspend fun envios(s: Servidor): RespostaEnvios = get(s, "/api/envios")

    suspend fun limparEnvios(s: Servidor): Unit =
        enviar<Unit, Unit>(s, "/api/envios/limpar", null)
}
