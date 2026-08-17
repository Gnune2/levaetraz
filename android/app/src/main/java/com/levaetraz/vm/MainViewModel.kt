package com.levaetraz.vm

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.levaetraz.data.ApiClient
import com.levaetraz.data.IndiceBaixados
import com.levaetraz.data.Prefs
import com.levaetraz.data.PrefsLocais
import com.levaetraz.data.WsClient
import com.levaetraz.model.Envio
import com.levaetraz.model.EnvioLocal
import com.levaetraz.model.EstadoAuth
import com.levaetraz.model.EstadoConexao
import com.levaetraz.model.EstadoLocal
import com.levaetraz.model.EventoWs
import com.levaetraz.model.ItemArquivo
import com.levaetraz.model.PastaCompartilhada
import com.levaetraz.model.Preferencias
import com.levaetraz.model.ResumoTransferencia
import com.levaetraz.model.Servidor
import com.levaetraz.model.Sessao
import com.levaetraz.util.Envios
import com.levaetraz.util.MediaSaver
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

// ────────────────────────────────────────────────────────────
// ESTADOS DE TELA
// ────────────────────────────────────────────────────────────
data class BrowserState(
    val carregando: Boolean = false,
    val path: String = "",
    val parent: String? = null,
    val itens: List<ItemArquivo> = emptyList(),
    val selecionados: Set<String> = emptySet(),
    val erro: String? = null,
    val preview: ItemArquivo? = null,
    /** Caminhos que o índice diz que já estão neste celular. */
    val jaNoCelular: Set<String> = emptySet(),
    /** Quando ligado, ignora o índice e baixa de novo o que for selecionado. */
    val forcarRebaixar: Boolean = false,
    val espacoLivre: Long = 0,
    /** A pasta aberta aceita escrita? Muda o que a tela pode oferecer. */
    val gravavel: Boolean = false,
)

data class EnvioState(
    val fila: List<EnvioLocal> = emptyList(),
    val destino: String = "",
    val pastasDoPc: List<PastaCompartilhada> = emptyList(),
) {
    val ativos: Int get() = fila.count { it.estado == EstadoLocal.ENVIANDO }
    val pendentes: Int get() = fila.count { it.estado == EstadoLocal.NA_FILA }
}

data class HistoricoState(
    val envios: List<Envio> = emptyList(),
    val resumo: ResumoTransferencia = ResumoTransferencia(),
)

data class AjustesState(
    val sessoes: List<Sessao> = emptyList(),
    val preferencias: Preferencias = Preferencias(),
    val locais: PrefsLocais = PrefsLocais(),
    val totalBaixados: Int = 0,
    val hostname: String = "",
    val versaoServidor: String = "",
)

/** Mensagem efêmera para a snackbar. */
data class Recado(val texto: String, val erro: Boolean = false)

// ────────────────────────────────────────────────────────────
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)
    private val api = ApiClient()
    private val ws = WsClient(api.http)
    private val indice = IndiceBaixados.de(app)

    private val _servidor = MutableStateFlow<Servidor?>(null)
    val servidor: StateFlow<Servidor?> = _servidor.asStateFlow()

    val conexao: StateFlow<EstadoConexao> = ws.estado

    private val _navegador = MutableStateFlow(BrowserState())
    val navegador: StateFlow<BrowserState> = _navegador.asStateFlow()

    private val _envio = MutableStateFlow(EnvioState())
    val envio: StateFlow<EnvioState> = _envio.asStateFlow()

    private val _historico = MutableStateFlow(HistoricoState())
    val historico: StateFlow<HistoricoState> = _historico.asStateFlow()

    private val _ajustes = MutableStateFlow(AjustesState())
    val ajustes: StateFlow<AjustesState> = _ajustes.asStateFlow()

    private val _pareando = MutableStateFlow(false)
    val pareando: StateFlow<Boolean> = _pareando.asStateFlow()

    private val _sondado = MutableStateFlow<EstadoAuth?>(null)
    val sondado: StateFlow<EstadoAuth?> = _sondado.asStateFlow()

    private val _erroPareamento = MutableStateFlow<String?>(null)
    val erroPareamento: StateFlow<String?> = _erroPareamento.asStateFlow()

    private val _recados = MutableSharedFlow<Recado>(extraBufferCapacity = 16)
    val recados: SharedFlow<Recado> = _recados.asSharedFlow()

    private val _travaAtiva = MutableStateFlow(true)
    val travaAtiva: StateFlow<Boolean> = _travaAtiva.asStateFlow()

    private var tarefaEnvio: Job? = null
    private var cancelarTudo = false

    /**
     * Ligada logo antes de abrirmos o seletor de arquivos do Android.
     *
     * Sem isso, escolher um arquivo cobraria a digital toda vez: o seletor é
     * outra Activity, o app vai para segundo plano e a trava dispara na volta.
     * A bandeira vale só para saídas que nós mesmos provocamos — trocar de app
     * pela bandeja continua trancando normalmente.
     */
    var saindoParaSeletor: Boolean = false
        private set

    fun vouAbrirSeletor() { saindoParaSeletor = true }

    fun voltouDoSeletor() { saindoParaSeletor = false }

    init {
        viewModelScope.launch {
            prefs.travaBiometrica.collect { _travaAtiva.value = it }
        }
        viewModelScope.launch {
            prefs.locais.collect { l ->
                _ajustes.value = _ajustes.value.copy(locais = l)
                if (_envio.value.destino.isBlank() && l.destinoNoPc.isNotBlank()) {
                    _envio.value = _envio.value.copy(destino = l.destinoNoPc)
                }
            }
        }
        viewModelScope.launch {
            prefs.servidor.collect { s ->
                _servidor.value = s
                if (s != null) {
                    ws.conectar(s)
                    carregarTudo()
                } else {
                    ws.desconectar()
                }
            }
        }
        viewModelScope.launch { ws.eventos.collect(::aoReceberEvento) }
    }

    private fun recado(texto: String, erro: Boolean = false) {
        _recados.tryEmit(Recado(texto, erro))
    }

    private fun aoReceberEvento(ev: EventoWs) {
        when (ev) {
            is EventoWs.Tudo -> {
                _historico.value = HistoricoState(ev.envios, ev.resumo)
                _ajustes.value = _ajustes.value.copy(
                    hostname = ev.hostname, versaoServidor = ev.versao)
            }
            is EventoWs.UmEnvio -> {
                val lista = _historico.value.envios.toMutableList()
                val i = lista.indexOfFirst { it.id == ev.envio.id }
                if (i >= 0) lista[i] = ev.envio else lista.add(0, ev.envio)
                _historico.value = _historico.value.copy(envios = lista)
            }
            is EventoWs.Resumo -> _historico.value = _historico.value.copy(resumo = ev.resumo)
            // Só recarrega se a tela de arquivos já tiver algo: senão o app
            // faria uma requisição por evento mesmo com o usuário em outra aba.
            EventoWs.ListaMudou -> if (_navegador.value.path.isNotBlank()) recarregar()
            is EventoWs.Status -> recado(ev.texto, ev.nivel == "erro")
        }
    }

    // ── pareamento ───────────────────────────────────────────
    fun sondar(host: String, porta: Int) = viewModelScope.launch {
        _erroPareamento.value = null
        runCatching { api.estadoAuth(host, porta) }
            .onSuccess { _sondado.value = it }
            .onFailure {
                _sondado.value = null
                _erroPareamento.value = "não achei um servidor em $host:$porta"
            }
    }

    fun entrarComSenha(host: String, porta: Int, senha: String) = viewModelScope.launch {
        _pareando.value = true
        _erroPareamento.value = null
        runCatching { api.login(host, porta, senha, nomeDoAparelho()) }
            .onSuccess { prefs.salvarServidor(Servidor(host, porta, it.token)) }
            .onFailure { _erroPareamento.value = it.message ?: "não consegui entrar" }
        _pareando.value = false
    }

    /** Lê o `levaetraz://host:porta/codigo` do QR que o painel mostra. */
    fun entrarComQr(uri: String) = viewModelScope.launch {
        _pareando.value = true
        _erroPareamento.value = null
        val m = Regex("""^levaetraz://([^/:]+):(\d+)/(.+)$""").find(uri.trim())
        if (m == null) {
            _erroPareamento.value = "esse QR não é do levaetraz"
            _pareando.value = false
            return@launch
        }
        val (host, porta, codigo) = m.destructured
        runCatching { api.parear(host, porta.toInt(), codigo, nomeDoAparelho()) }
            .onSuccess { prefs.salvarServidor(Servidor(host, porta.toInt(), it.token)) }
            .onFailure { _erroPareamento.value = it.message ?: "pareamento recusado" }
        _pareando.value = false
    }

    fun desconectar() = viewModelScope.launch {
        _servidor.value?.let { runCatching { api.sair(it) } }
        prefs.esquecerServidor()
        _navegador.value = BrowserState()
        _historico.value = HistoricoState()
    }

    private fun nomeDoAparelho(): String =
        "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()

    // ── trava ────────────────────────────────────────────────
    suspend fun conferirSenhaDoServidor(senha: String): Boolean {
        val s = _servidor.value ?: return false
        return runCatching { api.verificarSenha(s, senha) }.isSuccess
    }

    fun salvarTrava(ativa: Boolean) = viewModelScope.launch { prefs.salvarTrava(ativa) }

    // ── navegador de arquivos do PC ──────────────────────────
    fun navegar(caminho: String?) = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        _navegador.value = _navegador.value.copy(carregando = true, erro = null)
        runCatching { api.listar(s, caminho) }
            .onSuccess { r ->
                val jaTem = indice.quaisJaTem(r.itens.map { it.caminho })
                _navegador.value = BrowserState(
                    path = r.caminho,
                    parent = r.pai,
                    itens = r.itens,
                    jaNoCelular = jaTem,
                    forcarRebaixar = _navegador.value.forcarRebaixar,
                    espacoLivre = r.espaco.livre,
                    gravavel = r.gravavel,
                    erro = r.erro,
                )
            }
            .onFailure {
                _navegador.value = _navegador.value.copy(
                    carregando = false, erro = it.message ?: "não consegui listar")
            }
    }

    fun recarregar() = navegar(_navegador.value.path.ifBlank { null })

    fun alternarSelecao(item: ItemArquivo) {
        val atual = _navegador.value.selecionados
        _navegador.value = _navegador.value.copy(
            selecionados = if (item.caminho in atual) atual - item.caminho
                           else atual + item.caminho,
        )
    }

    fun selecionarTodos() {
        val arquivos = _navegador.value.itens.filter { !it.ehPasta }.map { it.caminho }
        _navegador.value = _navegador.value.copy(selecionados = arquivos.toSet())
    }

    fun limparSelecao() {
        _navegador.value = _navegador.value.copy(selecionados = emptySet())
    }

    fun alternarForcar() {
        _navegador.value = _navegador.value.copy(
            forcarRebaixar = !_navegador.value.forcarRebaixar)
    }

    fun abrirPreview(item: ItemArquivo) {
        _navegador.value = _navegador.value.copy(preview = item)
    }

    fun fecharPreview() {
        _navegador.value = _navegador.value.copy(preview = null)
    }

    // ── PC → celular ─────────────────────────────────────────
    fun baixarSelecionados() = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        val estado = _navegador.value
        val alvos = estado.itens.filter {
            !it.ehPasta && it.caminho in estado.selecionados &&
                (estado.forcarRebaixar || it.caminho !in estado.jaNoCelular)
        }
        if (alvos.isEmpty()) {
            recado("nada para baixar — o que você marcou já está no celular")
            limparSelecao()
            return@launch
        }

        limparSelecao()
        recado("baixando ${alvos.size} arquivo(s)…")
        var ok = 0
        for (item in alvos) {
            runCatching { MediaSaver.salvar(getApplication(), api, s, item) }
                .onSuccess { ok++ }
                .onFailure { recado("${item.nome}: ${it.message}", erro = true) }
        }
        recado(if (ok == alvos.size) "$ok arquivo(s) salvos no celular"
               else "$ok de ${alvos.size} salvos")
        recarregar()
    }

    // ── celular → PC ─────────────────────────────────────────
    fun enfileirarParaEnvio(uris: List<Uri>) = viewModelScope.launch {
        val ctx = getApplication<Application>()
        val novos = uris.mapNotNull { uri ->
            val info = Envios.descrever(ctx, uri) ?: return@mapNotNull null
            EnvioLocal(uri = uri.toString(), nome = info.first, tamanho = info.second)
        }
        if (novos.isEmpty()) {
            recado("não consegui ler esses arquivos", erro = true)
            return@launch
        }
        _envio.value = _envio.value.copy(fila = _envio.value.fila + novos)
        recado("${novos.size} arquivo(s) na fila")
        dispararFila()
    }

    fun escolherDestino(caminho: String) = viewModelScope.launch {
        _envio.value = _envio.value.copy(destino = caminho)
        prefs.salvarLocais(prefs.locais.first().copy(destinoNoPc = caminho))
    }

    fun carregarPastasDoPc() = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        runCatching { api.pastas(s) }
            // Só as graváveis viram chip de destino: oferecer uma pasta de
            // leitura seria oferecer um envio que o servidor vai recusar.
            .onSuccess { r ->
                _envio.value = _envio.value.copy(
                    pastasDoPc = r.pastas.filter { it.gravavel })
            }
    }

    fun cancelarEnvios() {
        cancelarTudo = true
        tarefaEnvio?.cancel()
        _envio.value = _envio.value.copy(
            fila = _envio.value.fila.map {
                if (it.estado == EstadoLocal.NA_FILA || it.estado == EstadoLocal.ENVIANDO)
                    it.copy(estado = EstadoLocal.CANCELADO) else it
            },
        )
        recado("envios cancelados")
    }

    fun limparFilaDeEnvio() {
        _envio.value = _envio.value.copy(
            fila = _envio.value.fila.filter {
                it.estado == EstadoLocal.NA_FILA || it.estado == EstadoLocal.ENVIANDO
            },
        )
    }

    private fun dispararFila() {
        if (tarefaEnvio?.isActive == true) return
        cancelarTudo = false
        tarefaEnvio = viewModelScope.launch {
            // Espera o servidor em vez de desistir. Um arquivo compartilhado
            // para o app fechado chega antes do DataStore carregar o
            // pareamento; lendo `_servidor.value` na hora, a fila travava em
            // "na fila" para sempre, sem erro nenhum na tela.
            val s = _servidor.filterNotNull().first()
            val ctx = getApplication<Application>()
            while (true) {
                val proximo = _envio.value.fila.firstOrNull { it.estado == EstadoLocal.NA_FILA }
                    ?: break
                if (cancelarTudo) break
                atualizarLocal(proximo.uri) { it.copy(estado = EstadoLocal.ENVIANDO) }

                runCatching {
                    Envios.subir(
                        contexto = ctx,
                        api = api,
                        servidor = s,
                        uri = Uri.parse(proximo.uri),
                        nome = proximo.nome,
                        tamanho = proximo.tamanho,
                        destino = _envio.value.destino.ifBlank { null },
                        cancelado = { cancelarTudo },
                        progresso = { n -> atualizarLocal(proximo.uri) { it.copy(enviados = n) } },
                    )
                }.onSuccess { resultado ->
                    atualizarLocal(proximo.uri) {
                        it.copy(
                            estado = if (resultado.duplicado) EstadoLocal.DUPLICADO
                                     else EstadoLocal.CONCLUIDO,
                            enviados = proximo.tamanho,
                            mensagem = resultado.mensagem,
                        )
                    }
                }.onFailure { erro ->
                    atualizarLocal(proximo.uri) {
                        it.copy(estado = EstadoLocal.ERRO,
                                mensagem = erro.message ?: "falhou")
                    }
                }
            }
            val f = _envio.value.fila
            val enviados = f.count { it.estado == EstadoLocal.CONCLUIDO }
            val duplicados = f.count { it.estado == EstadoLocal.DUPLICADO }
            if (enviados > 0 || duplicados > 0) {
                recado(buildString {
                    if (enviados > 0) append("$enviados enviado(s)")
                    if (duplicados > 0) {
                        if (enviados > 0) append(" · ")
                        append("$duplicados já estava(m) no PC")
                    }
                })
            }
        }
    }

    private fun atualizarLocal(uri: String, transformar: (EnvioLocal) -> EnvioLocal) {
        _envio.value = _envio.value.copy(
            fila = _envio.value.fila.map { if (it.uri == uri) transformar(it) else it },
        )
    }

    // ── histórico ────────────────────────────────────────────
    fun limparHistorico() = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        runCatching { api.limparEnvios(s); api.envios(s) }
            .onSuccess { _historico.value = HistoricoState(it.envios, it.resumo) }
            .onFailure { recado(it.message ?: "não consegui limpar", erro = true) }
    }

    fun cancelarEnvioNoServidor(id: String) = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        runCatching { api.cancelarEnvio(s, id) }
            .onFailure { recado(it.message ?: "não consegui cancelar", erro = true) }
    }

    // ── ajustes ──────────────────────────────────────────────
    fun carregarTudo() = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        navegar(null)
        carregarPastasDoPc()
        runCatching { api.envios(s) }
            .onSuccess { _historico.value = HistoricoState(it.envios, it.resumo) }
        runCatching { api.preferencias(s) }
            .onSuccess { _ajustes.value = _ajustes.value.copy(preferencias = it) }
        _ajustes.value = _ajustes.value.copy(totalBaixados = indice.total())
    }

    fun carregarSessoes() = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        runCatching { api.sessoes(s) }
            .onSuccess { _ajustes.value = _ajustes.value.copy(sessoes = it.sessoes) }
            .onFailure { recado(it.message ?: "não consegui listar", erro = true) }
    }

    fun revogarSessao(id: String) = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        runCatching { api.revogar(s, id) }
            .onSuccess { recado("dispositivo desconectado"); carregarSessoes() }
            .onFailure { recado(it.message ?: "não consegui revogar", erro = true) }
    }

    fun salvarPreferenciasDoServidor(p: Preferencias) = viewModelScope.launch {
        val s = _servidor.value ?: return@launch
        runCatching { api.gravarPreferencias(s, p) }
            .onSuccess {
                _ajustes.value = _ajustes.value.copy(preferencias = it)
                recado("salvo no PC")
            }
            .onFailure { recado(it.message ?: "não consegui salvar", erro = true) }
    }

    fun salvarPrefsLocais(l: PrefsLocais) = viewModelScope.launch { prefs.salvarLocais(l) }

    fun esquecerBaixados() = viewModelScope.launch {
        val n = indice.esquecerTodos()
        _ajustes.value = _ajustes.value.copy(totalBaixados = 0)
        recado("$n registro(s) apagados — tudo volta a ser baixável")
        recarregar()
    }

    fun urlThumb(caminho: String): String =
        _servidor.value?.let { api.urlThumb(it, caminho) }.orEmpty()

    fun urlArquivo(caminho: String): String =
        _servidor.value?.let { api.urlArquivo(it, caminho) }.orEmpty()

    fun reconectar() = ws.reconectarAgora()

    override fun onCleared() {
        ws.desconectar()
        super.onCleared()
    }
}
