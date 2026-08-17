package com.levaetraz

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.levaetraz.model.EstadoConexao
import com.levaetraz.ui.components.Chip
import com.levaetraz.ui.screens.AjustesScreen
import com.levaetraz.ui.screens.ConnectScreen
import com.levaetraz.ui.screens.EnviarScreen
import com.levaetraz.ui.screens.HistoricoScreen
import com.levaetraz.ui.screens.IndicadorConexao
import com.levaetraz.ui.screens.LockScreen
import com.levaetraz.ui.screens.MediaBrowserScreen
import com.levaetraz.ui.screens.PreviewScreen
import com.levaetraz.ui.screens.QrScannerScreen
import com.levaetraz.ui.theme.LevaeTrazTheme
import com.levaetraz.ui.theme.LocalHaptics
import com.levaetraz.ui.theme.Palette
import com.levaetraz.ui.theme.Spacing
import com.levaetraz.util.autenticar
import com.levaetraz.util.temBiometria
import com.levaetraz.vm.MainViewModel
import kotlinx.coroutines.launch

private enum class Aba(val rotulo: String) {
    ARQUIVOS("no PC"),
    ENVIAR("enviar"),
    HISTORICO("histórico"),
    AJUSTES("ajustes"),
}

class MainActivity : FragmentActivity() {

    /**
     * Arquivos vindos do "compartilhar", como estado observável.
     *
     * `setIntent()` sozinho não basta: com o app já aberto o Android entrega
     * por `onNewIntent`, e a árvore do Compose — montada uma vez no onCreate —
     * nunca releria o intent novo. Compartilhar para o app aberto simplesmente
     * não fazia nada.
     */
    private val recebidos = mutableStateOf<List<Uri>>(emptyList())

    private val pedirNotificacoes = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* negar só remove a notificação de progresso; o app segue funcionando */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // FLAG_SECURE: a miniatura na bandeja de apps recentes fica em branco e
        // screenshot/gravação de tela ficam bloqueados. Nada do conteúdo
        // aparece fora do app.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE,
        )

        enableEdgeToEdge()
        garantirPermissaoNotificacao()

        val app = application as LevaeTrazApp

        recebidos.value = compartilhados(intent)

        setContent {
            LevaeTrazTheme(haptics = app.haptics) {
                val vm: MainViewModel = viewModel()
                RaizDoApp(
                    vm = vm,
                    compartilhados = recebidos.value,
                    onConsumidos = { recebidos.value = emptyList() },
                    activity = this,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        compartilhados(intent).takeIf { it.isNotEmpty() }?.let { recebidos.value = it }
    }

    /** Arquivos vindos do "compartilhar" de outro app. */
    private fun compartilhados(intent: Intent?): List<Uri> = when (intent?.action) {
        Intent.ACTION_SEND ->
            listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE ->
            intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
        else -> emptyList()
    }

    private fun garantirPermissaoNotificacao() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val concedida = ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (!concedida) pedirNotificacoes.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}

@Composable
private fun RaizDoApp(
    vm: MainViewModel,
    compartilhados: List<Uri>,
    onConsumidos: () -> Unit,
    activity: FragmentActivity,
) {
    val haptics = LocalHaptics.current
    val escopo = rememberCoroutineScope()

    val servidor by vm.servidor.collectAsStateWithLifecycle()
    val conexao by vm.conexao.collectAsStateWithLifecycle()
    val navegador by vm.navegador.collectAsStateWithLifecycle()
    val envio by vm.envio.collectAsStateWithLifecycle()
    val historico by vm.historico.collectAsStateWithLifecycle()
    val ajustes by vm.ajustes.collectAsStateWithLifecycle()
    val pareando by vm.pareando.collectAsStateWithLifecycle()
    val sondado by vm.sondado.collectAsStateWithLifecycle()
    val erroPareamento by vm.erroPareamento.collectAsStateWithLifecycle()

    var aba by remember { mutableStateOf(Aba.ARQUIVOS) }
    var escaneando by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    var ultimoErro by remember { mutableStateOf(false) }

    // ── trava biométrica ─────────────────────────────────────
    // Se o aparelho tem digital cadastrada, o app começa trancado e nada é
    // desenhado atrás até a autenticação passar.
    val travaLigada by vm.travaAtiva.collectAsStateWithLifecycle()
    val temSensor = remember { temBiometria(activity) }
    // Só tranca depois de pareado. Antes disso não há nada guardado para
    // proteger — e, pior, a saída de emergência da trava é "usar a senha do
    // servidor", que sem servidor nenhum deixaria o usuário preso na tela.
    val exigeBiometria = temSensor && travaLigada && servidor != null
    // O DataStore é assíncrono: `travaLigada` chega como true até carregar, então
    // o app tranca primeiro e destranca depois (direção segura). Por isso o
    // bloqueio é derivado — capturá-lo na primeira composição deixaria o app
    // preso na trava mesmo com a preferência desligada.
    // `remember` e não `rememberSaveable`: o estado destrancado morre junto com
    // a composição, então voltar do segundo plano cai na trava de novo.
    var destrancado by remember { mutableStateOf(false) }
    var biometriaRecusada by remember { mutableStateOf(false) }
    var verificandoSenha by remember { mutableStateOf(false) }
    var erroSenha by remember { mutableStateOf<String?>(null) }

    suspend fun pedirBiometria() {
        val ok = autenticar(activity)
        destrancado = ok
        biometriaRecusada = !ok
    }

    // Tranca ao sair da tela e pede a digital ao voltar.
    //
    // O gatilho precisa ser o ON_START, não uma mudança de estado: se a digital
    // foi recusada antes, `destrancado` continua false e um LaunchedEffect com
    // chave nesse valor nunca voltaria a rodar — o app abriria travado sem
    // nunca mostrar o prompt.
    val dono = LocalLifecycleOwner.current
    DisposableEffect(dono, exigeBiometria) {
        val obs = LifecycleEventObserver { _, evento ->
            when (evento) {
                Lifecycle.Event.ON_STOP -> if (exigeBiometria && !vm.saindoParaSeletor) {
                    destrancado = false
                    biometriaRecusada = false
                    erroSenha = null
                }
                Lifecycle.Event.ON_START -> {
                    vm.voltouDoSeletor()
                    if (exigeBiometria && !destrancado) {
                        escopo.launch { pedirBiometria() }
                    }
                }
                else -> Unit
            }
        }
        dono.lifecycle.addObserver(obs)
        onDispose { dono.lifecycle.removeObserver(obs) }
    }

    if (exigeBiometria && !destrancado) {
        LockScreen(
            recusado = biometriaRecusada,
            verificandoSenha = verificandoSenha,
            erroSenha = erroSenha,
            onTentar = { escopo.launch { pedirBiometria() } },
            onSenha = { senha ->
                escopo.launch {
                    verificandoSenha = true
                    erroSenha = null
                    if (vm.conferirSenhaDoServidor(senha)) destrancado = true
                    else erroSenha = "senha incorreta"
                    verificandoSenha = false
                }
            },
        )
        return
    }

    // ── recados ──────────────────────────────────────────────
    LaunchedEffect(Unit) {
        vm.recados.collect { r ->
            ultimoErro = r.erro
            snackbar.showSnackbar(r.texto, duration = SnackbarDuration.Short)
        }
    }

    // Arquivos vindos do "compartilhar" de outro app caem direto na fila.
    // Consumir zera a lista: sem isso, qualquer recomposição os enfileiraria
    // de novo e o mesmo arquivo subiria várias vezes.
    LaunchedEffect(compartilhados) {
        if (compartilhados.isNotEmpty()) {
            vm.enfileirarParaEnvio(compartilhados)
            aba = Aba.ENVIAR
            onConsumidos()
        }
    }

    LaunchedEffect(aba, conexao) {
        if (aba == Aba.ARQUIVOS && conexao == EstadoConexao.CONECTADO &&
            navegador.path.isBlank() && !navegador.carregando
        ) {
            vm.navegar(null)
        }
        if (aba == Aba.AJUSTES) vm.carregarSessoes()
        if (aba == Aba.ENVIAR) vm.carregarPastasDoPc()
    }

    if (escaneando) {
        QrScannerScreen(
            onLido = { texto ->
                escaneando = false
                vm.entrarComQr(texto)
            },
            onCancelar = { escaneando = false },
        )
        return
    }

    // Sem servidor pareado: só a tela de conexão existe
    if (servidor == null) {
        Scaffold(
            containerColor = Palette.Bg,
            snackbarHost = { HostDeAviso(snackbar, ultimoErro) },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                ConnectScreen(
                    pareando = pareando,
                    servidorSondado = sondado,
                    erro = erroPareamento,
                    onSondar = vm::sondar,
                    onEntrar = vm::entrarComSenha,
                    onAbrirScanner = { escaneando = true },
                )
            }
        }
        return
    }

    navegador.preview?.let { item ->
        PreviewScreen(
            item = item,
            token = servidor!!.token,
            rawUrl = vm::urlArquivo,
            onFechar = vm::fecharPreview,
            onPuxar = {
                vm.alternarSelecao(item)
                vm.baixarSelecionados()
            },
        )
        return
    }

    Scaffold(
        containerColor = Palette.Bg,
        topBar = {
            BarraSuperior(
                aba = aba,
                conexao = conexao,
                pendentes = envio.pendentes + envio.ativos,
                recebendo = historico.resumo.ativos,
                onAba = { haptics.tick(); aba = it },
                onReconectar = vm::reconectar,
            )
        },
        snackbarHost = { HostDeAviso(snackbar, ultimoErro) },
    ) { pad ->
        Box(Modifier.padding(pad)) {
            when (aba) {
                Aba.ARQUIVOS -> MediaBrowserScreen(
                    estado = navegador,
                    token = servidor!!.token,
                    thumbUrl = vm::urlThumb,
                    onNavegar = vm::navegar,
                    onRecarregar = vm::recarregar,
                    onAbrir = vm::abrirPreview,
                    onAlternarSelecao = vm::alternarSelecao,
                    onSelecionarTodos = vm::selecionarTodos,
                    onLimparSelecao = vm::limparSelecao,
                    onPuxar = vm::baixarSelecionados,
                    onAlternarForcar = vm::alternarForcar,
                )

                Aba.ENVIAR -> EnviarScreen(
                    estado = envio,
                    onAbrindoSeletor = vm::vouAbrirSeletor,
                    onEscolher = vm::enfileirarParaEnvio,
                    onDestino = vm::escolherDestino,
                    onCancelar = vm::cancelarEnvios,
                    onLimpar = vm::limparFilaDeEnvio,
                )

                Aba.HISTORICO -> HistoricoScreen(
                    estado = historico,
                    onLimpar = vm::limparHistorico,
                    onCancelar = vm::cancelarEnvioNoServidor,
                )

                Aba.AJUSTES -> AjustesScreen(
                    estado = ajustes,
                    servidor = servidor!!,
                    travaLigada = travaLigada,
                    temSensor = temSensor,
                    onTrava = vm::salvarTrava,
                    onPrefsServidor = vm::salvarPreferenciasDoServidor,
                    onPrefsLocais = vm::salvarPrefsLocais,
                    onRevogar = vm::revogarSessao,
                    onEsquecerBaixados = vm::esquecerBaixados,
                    onDesconectar = vm::desconectar,
                )
            }
        }
    }
}

@Composable
private fun BarraSuperior(
    aba: Aba,
    conexao: EstadoConexao,
    pendentes: Int,
    recebendo: Int,
    onAba: (Aba) -> Unit,
    onReconectar: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Palette.Surface)
            .statusBarsPadding(),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "leva",
                style = MaterialTheme.typography.titleLarge,
                color = Palette.Text,
            )
            Text(
                "etraz",
                style = MaterialTheme.typography.titleLarge,
                color = Palette.Accent,
            )
            Box(Modifier.weight(1f))
            IndicadorConexao(conexao, onReconectar)
        }

        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = Spacing.sm, end = Spacing.sm, bottom = Spacing.xs),
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
        ) {
            Aba.entries.forEach { a ->
                val marca = when (a) {
                    Aba.ENVIAR -> pendentes
                    Aba.HISTORICO -> recebendo
                    else -> 0
                }
                Chip(
                    texto = if (marca > 0) "${a.rotulo} · $marca" else a.rotulo,
                    selecionado = aba == a,
                    onClick = { onAba(a) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun HostDeAviso(estado: SnackbarHostState, erro: Boolean) {
    SnackbarHost(estado) { dados ->
        Snackbar(
            containerColor = Palette.SurfaceHigh,
            contentColor = if (erro) Palette.Err else Palette.Text,
            shape = MaterialTheme.shapes.medium,
            modifier = Modifier.padding(Spacing.md),
        ) {
            Text(dados.visuals.message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
