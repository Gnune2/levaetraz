"""
API HTTP + WebSocket do levaetraz.

Toda rota de dados exige uma sessão válida (header `X-Auth-Token`, ou `?t=` nas
que o navegador não consegue mandar header). Todo caminho de arquivo passa pela
jaula antes de ser usado — ver servidor/jaula.py.
"""

import asyncio
import contextlib
import platform
import socket
import sys
from pathlib import Path
from fastapi import (
    Depends, FastAPI, Header, HTTPException, Query, Request,
    WebSocket, WebSocketDisconnect,
)
from fastapi.concurrency import run_in_threadpool
from fastapi.responses import FileResponse, JSONResponse
from fastapi.staticfiles import StaticFiles

from . import (
    NOME, __version__, arquivos, auth, banco, celular, miniaturas, power,
    rede, sistema, transferencias, watchdog,
)
from . import config as cfg
from .esquemas import (
    AbrirEnvio, Apagar, LoginRequest, NovaPasta, NovaSenha, PairRequest,
    Preferencias, Rede, Renomear,
)
from .eventos import bus
from .jaula import CaminhoNegado, configurar as configurar_jaula, jaula
from .transferencias import ErroEnvio

app = FastAPI(title=NOME, version=__version__, docs_url=None, redoc_url=None)

# ── PAINEL WEB ──────────────────────────────────────────────
# Os estáticos ficam abertos de propósito: são só HTML/CSS/JS, e o painel pede
# a senha por dentro. Tudo que traz dado de verdade continua exigindo sessão.
DIR_WEB = Path(__file__).parent / 'web'
if DIR_WEB.is_dir():
    app.mount('/web', StaticFiles(directory=DIR_WEB), name='web')


@app.get('/', include_in_schema=False)
async def painel() -> FileResponse:
    indice = DIR_WEB / 'index.html'
    if not indice.is_file():
        raise HTTPException(404, 'painel web não instalado')
    return FileResponse(indice, media_type='text/html')


# ────────────────────────────────────────────────────────────
# CICLO DE VIDA
# ────────────────────────────────────────────────────────────
@app.on_event('startup')
async def _startup() -> None:
    bus.start(asyncio.get_running_loop())
    bus.snapshot_envio = transferencias.snapshot
    bus.snapshot_resumo = transferencias.resumo

    cfg.garantir_pasta_padrao()
    configurar_jaula(cfg.get_pastas(), cfg.get_negadas())

    # O que estava a meio caminho quando o processo morreu vira 'pausado': o
    # .parcial continua no disco e o celular retoma pelo offset.
    parados = await run_in_threadpool(banco.marcar_interrompidos)
    orfaos = await run_in_threadpool(transferencias.limpar_orfaos)
    if parados:
        bus.status(f'{parados} envio(s) interrompido(s) esperando retomada', 'aviso')
    if orfaos:
        bus.status(f'{orfaos} arquivo(s) parcial(is) órfão(s) removido(s)', 'aviso')

    # watchdog: só tem efeito quando rodando sob systemd
    app.state.watchdog = asyncio.create_task(watchdog.bater_ponto())
    watchdog.pronto(f'escutando na porta {cfg.get_port()}')


@app.on_event('shutdown')
async def _shutdown() -> None:
    watchdog.encerrando()
    tarefa = getattr(app.state, 'watchdog', None)
    if tarefa:
        tarefa.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await tarefa
    power.encerrar()
    bus.stop()


# ────────────────────────────────────────────────────────────
# AUTENTICAÇÃO
# ────────────────────────────────────────────────────────────
async def sessao_atual(x_auth_token: str | None = Header(default=None)):
    s = auth.validar_token(x_auth_token)
    if s is None:
        raise HTTPException(401, 'sessão inválida ou expirada')
    return s


async def sessao_ou_query(x_auth_token: str | None = Header(default=None),
                          t: str | None = Query(default=None)):
    """
    Aceita o token pelo header ou pela query.

    Tag <img> e abrir arquivo em nova aba não mandam header nenhum, então as
    rotas de miniatura e download precisam do token na URL. O WebSocket já
    funciona assim. Como o servidor só escuta no tailnet/LAN e o log de acesso
    está desligado, o token não fica registrado em lugar nenhum.
    """
    s = auth.validar_token(x_auth_token or t)
    if s is None:
        raise HTTPException(401, 'sessão inválida ou expirada')
    return s


def _ip(req: Request) -> str:
    return (req.client.host if req.client else '?') or '?'


def _eh_local(req: Request) -> bool:
    return _ip(req) in ('127.0.0.1', '::1', 'localhost')


@app.get('/api/auth/status')
async def auth_status(request: Request) -> dict:
    """Sem auth: o cliente usa isto para saber o que mostrar antes de entrar."""
    return {
        'app': NOME,
        'version': __version__,
        'tem_senha': auth.tem_senha(),
        'pareamento_ativo': bool(cfg.get_pareamento()),
        # só o navegador aberto no próprio PC pode criar a primeira senha
        'pode_criar_senha': not auth.tem_senha() and _eh_local(request),
    }


@app.post('/api/auth/setup')
async def definir_senha_inicial(body: LoginRequest, request: Request) -> dict:
    """
    Cria a primeira senha pelo painel, para não obrigar a passar pelo terminal.

    Só funciona enquanto não existe senha **e** se a requisição vier da própria
    máquina. Sem essa segunda regra, qualquer um que alcançasse a porta na
    janela entre instalar e configurar poderia reivindicar o servidor.
    """
    if auth.tem_senha():
        raise HTTPException(409, 'este servidor já tem senha — use o login')
    if not _eh_local(request):
        raise HTTPException(
            403,
            'a primeira senha só pode ser criada no próprio PC. '
            'Abra http://127.0.0.1:%d nele, ou rode: python main.py --senha'
            % cfg.get_port(),
        )
    try:
        auth.definir_senha(body.senha)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc

    token, s = auth.entrar_com_senha(body.senha, body.dispositivo or 'painel web',
                                     _ip(request))
    return {'token': token, 'sessao': s.resumo()}


@app.post('/api/auth/login')
async def login(body: LoginRequest, request: Request) -> dict:
    if not auth.tem_senha():
        raise HTTPException(409, 'nenhuma senha definida — rode: python main.py --senha')
    try:
        token, s = auth.entrar_com_senha(body.senha, body.dispositivo, _ip(request))
    except PermissionError as exc:
        raise HTTPException(401, str(exc)) from exc
    return {'token': token, 'sessao': s.resumo()}


@app.post('/api/auth/pair')
async def parear(body: PairRequest, request: Request) -> dict:
    try:
        token, s = auth.entrar_com_pareamento(body.codigo, body.dispositivo, _ip(request))
    except PermissionError as exc:
        raise HTTPException(401, str(exc)) from exc
    return {'token': token, 'sessao': s.resumo()}


@app.post('/api/auth/verify')
async def verificar_senha(body: LoginRequest, request: Request,
                          _=Depends(sessao_atual)) -> dict:
    """
    Confere a senha sem criar sessão nova. É o que a trava do app usa como
    alternativa quando a digital falha — sem isso o usuário ficaria preso.
    """
    try:
        auth.conferir_senha(body.senha, _ip(request))
    except PermissionError as exc:
        raise HTTPException(401, str(exc)) from exc
    return {'ok': True}


@app.post('/api/auth/logout')
async def logout(x_auth_token: str | None = Header(default=None)) -> dict:
    return {'ok': auth.sair(x_auth_token or '')}


@app.get('/api/auth/sessions', dependencies=[Depends(sessao_atual)])
async def sessoes() -> dict:
    return {'sessoes': auth.listar_sessoes()}


@app.delete('/api/auth/sessions/{sessao_id}', dependencies=[Depends(sessao_atual)])
async def revogar(sessao_id: str) -> dict:
    if sessao_id == 'todas':
        return {'revogadas': auth.revogar_todas()}
    if not auth.revogar(sessao_id):
        raise HTTPException(404, 'sessão não encontrada')
    return {'ok': True}


@app.post('/api/auth/senha', dependencies=[Depends(sessao_atual)])
async def trocar_senha(body: NovaSenha, request: Request) -> dict:
    """Troca a senha pelo painel. Exige a atual — sessão sozinha não basta."""
    try:
        auth.conferir_senha(body.atual, _ip(request))
    except PermissionError as exc:
        raise HTTPException(401, f'senha atual: {exc}') from exc
    try:
        auth.definir_senha(body.nova)
    except ValueError as exc:
        raise HTTPException(400, str(exc)) from exc
    return {'ok': True, 'aviso': 'todas as sessões foram revogadas, inclusive esta'}


@app.post('/api/auth/pair/novo', dependencies=[Depends(sessao_atual)])
async def gerar_pareamento() -> dict:
    codigo, expira = auth.novo_codigo_pareamento()
    hosts, _ = rede.resolver_bind(cfg.get_bind())
    endereco = rede.endereco_publicado(hosts, cfg.get_port())
    uri = f'levaetraz://{endereco}/{codigo}'
    return {
        'codigo': codigo,
        'endereco': endereco,
        'uri': uri,
        'expira_em': expira,
        # O SVG sai pronto daqui: gerar QR no navegador exigiria embutir um
        # encoder inteiro em JS, e a lib do Python já está instalada.
        'svg': await run_in_threadpool(_qr_svg, uri),
    }


def _qr_svg(dados: str) -> str:
    import io
    import qrcode
    import qrcode.image.svg
    img = qrcode.make(dados, image_factory=qrcode.image.svg.SvgPathImage, border=2)
    buf = io.BytesIO()
    img.save(buf)
    return buf.getvalue().decode('utf-8')


# ────────────────────────────────────────────────────────────
# INFO / SAÚDE
# ────────────────────────────────────────────────────────────
@app.get('/api/health')
async def health() -> dict:
    return {'app': NOME, 'version': __version__, 'requires_auth': True}


@app.get('/api/info', dependencies=[Depends(sessao_atual)])
async def info() -> dict:
    return {
        'app': NOME,
        'version': __version__,
        'hostname': socket.gethostname(),
        'plataforma': f'{platform.system()} {platform.release()}',
        'python': sys.version.split()[0],
        'tailscale': rede.ip_tailscale(),
        'jaula': jaula.descricao(),
        'preferencias': cfg.get_preferencias().model_dump(),
    }


@app.get('/api/sistema', dependencies=[Depends(sessao_atual)])
async def estado_sistema() -> dict:
    return await run_in_threadpool(sistema.resumo)


@app.get('/api/sistema/log', dependencies=[Depends(sessao_atual)])
async def ver_log(linhas: int = Query(default=60, ge=1, le=500)) -> dict:
    return {'linhas': await run_in_threadpool(sistema.log, linhas)}


# ────────────────────────────────────────────────────────────
# CONFIGURAÇÃO
# ────────────────────────────────────────────────────────────
@app.get('/api/config/rede', dependencies=[Depends(sessao_atual)])
async def ler_rede() -> dict:
    return {'bind': cfg.get_bind(), 'porta': cfg.get_port(),
            'pastas': cfg.get_pastas(), 'jaula': jaula.descricao()}


@app.put('/api/config/rede', dependencies=[Depends(sessao_atual)])
async def gravar_rede(body: Rede) -> dict:
    if body.bind:
        try:
            rede.resolver_bind(body.bind)      # falha cedo se o modo não der
        except RuntimeError as exc:
            raise HTTPException(400, str(exc)) from exc
        cfg.set_bind(body.bind)
    if body.porta:
        cfg.set_port(body.porta)
    if body.pastas is not None:
        limpas = []
        for r in body.pastas:
            p = Path(r).expanduser()
            if not p.is_dir():
                raise HTTPException(400, f'não é uma pasta: {r}')
            limpas.append(str(p.resolve()))
        if not limpas:
            raise HTTPException(400, 'precisa de pelo menos uma pasta compartilhada')
        cfg.set_pastas(limpas)
        configurar_jaula(limpas, cfg.get_negadas())
    return {'ok': True,
            'reiniciar': body.bind is not None or body.porta is not None}


@app.get('/api/preferencias', dependencies=[Depends(sessao_atual)])
async def ler_preferencias() -> Preferencias:
    return cfg.get_preferencias()


@app.put('/api/preferencias', dependencies=[Depends(sessao_atual)])
async def gravar_preferencias(novo: Preferencias) -> Preferencias:
    _validar(novo.destino_padrao, 'pasta padrão')
    return cfg.set_preferencias(novo)


# ────────────────────────────────────────────────────────────
# ARQUIVOS DO PC
# ────────────────────────────────────────────────────────────
def _validar(caminho: str, rotulo: str = 'caminho') -> Path:
    try:
        return jaula.validar(caminho)
    except CaminhoNegado as exc:
        raise HTTPException(403, f'{rotulo}: {exc}') from exc


@app.get('/api/arquivos', dependencies=[Depends(sessao_atual)])
async def listar_arquivos(caminho: str | None = None,
                          so_pastas: bool = False) -> dict:
    # Sem caminho, começa na primeira pasta compartilhada. Com caminho negado,
    # diz que negou — cair na raiz em silêncio confunde quem está navegando.
    alvo = _validar(caminho, 'pasta') if caminho else jaula.pastas[0]
    listagem = await run_in_threadpool(arquivos.listar, str(alvo), so_pastas)
    listagem['itens'] = [i for i in listagem['itens'] if jaula.permitido(i['caminho'])]
    if listagem.get('pai') and not jaula.permitido(listagem['pai']):
        listagem['pai'] = None
    listagem['espaco'] = await run_in_threadpool(arquivos.espaco, alvo)
    return listagem


@app.get('/api/arquivos/pastas', dependencies=[Depends(sessao_atual)])
async def pastas_compartilhadas() -> dict:
    """Os pontos de partida da navegação — uma entrada por pasta compartilhada."""
    saida = []
    for p in jaula.pastas:
        saida.append({'nome': p.name or str(p), 'caminho': str(p),
                      'existe': p.is_dir(),
                      'espaco': await run_in_threadpool(arquivos.espaco, p)})
    return {'pastas': saida}


@app.post('/api/arquivos/pasta', dependencies=[Depends(sessao_atual)])
async def nova_pasta(body: NovaPasta) -> dict:
    try:
        alvo = jaula.validar_para_criar(body.onde, body.nome)
        caminho = await run_in_threadpool(arquivos.criar_pasta, alvo)
    except CaminhoNegado as exc:
        raise HTTPException(403, str(exc)) from exc
    except FileExistsError as exc:
        raise HTTPException(409, str(exc)) from exc
    except OSError as exc:
        raise HTTPException(400, f'não consegui criar: {exc}') from exc
    bus.publicar({'type': 'lista'})
    return {'ok': True, 'caminho': caminho}


@app.post('/api/arquivos/renomear', dependencies=[Depends(sessao_atual)])
async def renomear_arquivo(body: Renomear) -> dict:
    origem = _validar(body.caminho, 'arquivo')
    try:
        caminho = await run_in_threadpool(arquivos.renomear, origem, body.nome_novo)
    except FileExistsError as exc:
        raise HTTPException(409, str(exc)) from exc
    except OSError as exc:
        raise HTTPException(400, f'não consegui renomear: {exc}') from exc
    bus.publicar({'type': 'lista'})
    return {'ok': True, 'caminho': caminho}


@app.post('/api/arquivos/apagar', dependencies=[Depends(sessao_atual)])
async def apagar_arquivos(body: Apagar) -> dict:
    if not body.caminhos:
        raise HTTPException(400, 'nada para apagar')
    apagados, erros = [], []
    for bruto in body.caminhos:
        try:
            alvo = _validar(bruto, 'arquivo')
            # Apagar a própria pasta compartilhada seria tirar o chão da jaula.
            if alvo in jaula.pastas:
                raise HTTPException(403, f'{alvo.name} é uma pasta compartilhada')
            await run_in_threadpool(arquivos.apagar, alvo)
            apagados.append(str(alvo))
        except HTTPException as exc:
            erros.append(f'{Path(bruto).name}: {exc.detail}')
        except OSError as exc:
            erros.append(f'{Path(bruto).name}: {exc}')
    bus.publicar({'type': 'lista'})
    return {'apagados': len(apagados), 'erros': erros}


@app.get('/api/arquivos/thumb', dependencies=[Depends(sessao_ou_query)])
async def thumb(caminho: str) -> FileResponse:
    alvo = _validar(caminho, 'arquivo')
    destino = await run_in_threadpool(miniaturas.thumb, str(alvo))
    if destino is None:
        raise HTTPException(404, 'sem miniatura para este arquivo')
    return FileResponse(destino, media_type='image/jpeg',
                        headers={'Cache-Control': 'private, max-age=86400'})


@app.get('/api/arquivos/baixar', dependencies=[Depends(sessao_ou_query)])
async def baixar(caminho: str) -> FileResponse:
    """
    PC → celular. É só isto: um GET.

    O FileResponse do Starlette já responde Range, então o celular retoma um
    download interrompido sozinho, sem protocolo nenhum do nosso lado.
    """
    alvo = _validar(caminho, 'arquivo')
    if not alvo.is_file():
        raise HTTPException(404, 'arquivo não encontrado')
    return FileResponse(alvo, filename=alvo.name)


@app.post('/api/arquivos/thumbs/limpar', dependencies=[Depends(sessao_atual)])
async def limpar_thumbs() -> dict:
    return {'removidas': await run_in_threadpool(miniaturas.limpar_cache)}


# ────────────────────────────────────────────────────────────
# ENVIOS — celular → PC
# ────────────────────────────────────────────────────────────
@app.post('/api/envios')
async def abrir_envio(body: AbrirEnvio, sessao=Depends(sessao_atual)) -> dict:
    """Anuncia um arquivo e descobre de onde continuar. Nenhum byte trafega aqui."""
    try:
        return await run_in_threadpool(transferencias.abrir, body,
                                       getattr(sessao, 'dispositivo', ''))
    except ErroEnvio as exc:
        raise HTTPException(400, str(exc)) from exc


@app.put('/api/envios/{envio_id}')
async def receber_envio(envio_id: str, request: Request,
                        offset: int = Query(default=0, ge=0),
                        _=Depends(sessao_atual)) -> dict:
    """
    Recebe os bytes. O corpo é o arquivo cru — sem multipart, sem base64: o
    celular já sabe o nome e o tamanho pelo passo anterior, e envelopar o
    conteúdo só faria o upload crescer 33% à toa.
    """
    power.atualizar(1)          # segura o suspend enquanto entra arquivo
    try:
        return await transferencias.receber(envio_id, request.stream(), offset)
    except ErroEnvio as exc:
        raise HTTPException(400, str(exc)) from exc
    finally:
        power.atualizar(0)


@app.post('/api/envios/{envio_id}/fim', dependencies=[Depends(sessao_atual)])
async def concluir_envio(envio_id: str) -> dict:
    try:
        return await run_in_threadpool(transferencias.concluir, envio_id)
    except ErroEnvio as exc:
        raise HTTPException(400, str(exc)) from exc


@app.delete('/api/envios/{envio_id}', dependencies=[Depends(sessao_atual)])
async def cancelar_envio(envio_id: str) -> dict:
    if not await run_in_threadpool(transferencias.cancelar, envio_id):
        raise HTTPException(404, 'envio não encontrado')
    return {'ok': True}


@app.get('/api/envios', dependencies=[Depends(sessao_atual)])
async def listar_envios() -> dict:
    return {'envios': await run_in_threadpool(transferencias.listar),
            'resumo': await run_in_threadpool(transferencias.resumo)}


@app.post('/api/envios/limpar', dependencies=[Depends(sessao_atual)])
async def limpar_envios() -> dict:
    return {'removidos': await run_in_threadpool(transferencias.limpar_concluidos)}


# ────────────────────────────────────────────────────────────
# APP DO CELULAR
# ────────────────────────────────────────────────────────────
@app.get('/api/celular', dependencies=[Depends(sessao_atual)])
async def estado_celular() -> dict:
    hosts, _ = rede.resolver_bind(cfg.get_bind())
    so_tailnet = any(h.startswith('100.') for h in hosts) and '0.0.0.0' not in hosts
    peers = await run_in_threadpool(rede.peers_tailscale)

    # Com bind no tailnet, um celular fora da VPN não alcança o servidor de
    # jeito nenhum — nem para baixar o APK. Melhor dizer isso do que mostrar
    # um QR que fica carregando para sempre.
    return {
        'apk': celular.estado_apk(),
        'endereco': rede.endereco_publicado(hosts, cfg.get_port()),
        'so_tailnet': so_tailnet,
        'peers': peers,
        'alcancavel': (not so_tailnet) or bool(peers),
    }


@app.post('/api/celular/apk', dependencies=[Depends(sessao_atual)])
async def baixar_apk() -> dict:
    ok, msg = await run_in_threadpool(celular.baixar_apk)
    if not ok:
        raise HTTPException(400, msg)
    return {'ok': True, 'mensagem': msg, 'apk': celular.estado_apk()}


@app.get('/app.apk', dependencies=[Depends(sessao_ou_query)], include_in_schema=False)
async def servir_apk() -> FileResponse:
    """O celular baixa daqui pelo QR — por isso o token vai na query."""
    if not celular.APK.is_file():
        raise HTTPException(404, 'APK ainda não foi baixado — use o painel')
    return FileResponse(celular.APK,
                        media_type='application/vnd.android.package-archive',
                        filename='levaetraz.apk')


@app.get('/api/qr', dependencies=[Depends(sessao_atual)])
async def qr(dados: str) -> dict:
    """QR genérico em SVG, para o painel desenhar sem encoder em JS."""
    return {'svg': await run_in_threadpool(_qr_svg, dados)}


# ────────────────────────────────────────────────────────────
# TAILSCALE
# ────────────────────────────────────────────────────────────
@app.get('/api/tailscale', dependencies=[Depends(sessao_atual)])
async def tailscale_estado() -> dict:
    return {**sistema.tailscale(), 'login': celular.estado_login(),
            'pode_operar': await run_in_threadpool(celular.pode_operar)}


@app.post('/api/tailscale/login', dependencies=[Depends(sessao_atual)])
async def tailscale_login() -> dict:
    return await run_in_threadpool(celular.iniciar_login)


# ────────────────────────────────────────────────────────────
# WEBSOCKET
# ────────────────────────────────────────────────────────────
@app.websocket('/ws')
async def stream(ws: WebSocket, token: str | None = Query(default=None)) -> None:
    if auth.validar_token(token) is None:
        await ws.close(code=4401, reason='sessão inválida')
        return

    await ws.accept()
    fila = bus.registrar_cliente()

    try:
        await ws.send_json({
            'type': 'snapshot',
            'envios': await run_in_threadpool(transferencias.listar),
            'resumo': await run_in_threadpool(transferencias.resumo),
            'server': {'version': __version__, 'hostname': socket.gethostname()},
        })

        recebedor = asyncio.create_task(_drenar_cliente(ws))
        try:
            while True:
                payload = await fila.get()
                await ws.send_text(payload)
        finally:
            recebedor.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await recebedor
    except (WebSocketDisconnect, RuntimeError, ConnectionError):
        pass
    finally:
        bus.remover_cliente(fila)


async def _drenar_cliente(ws: WebSocket) -> None:
    while True:
        await ws.receive_text()


# ────────────────────────────────────────────────────────────
# ERROS
# ────────────────────────────────────────────────────────────
@app.exception_handler(CaminhoNegado)
async def _caminho_negado(_, exc: CaminhoNegado) -> JSONResponse:
    return JSONResponse(status_code=403, content={'detail': str(exc)})


@app.exception_handler(ErroEnvio)
async def _erro_envio(_, exc: ErroEnvio) -> JSONResponse:
    return JSONResponse(status_code=400, content={'detail': str(exc)})


@app.exception_handler(ValueError)
async def _erro_de_valor(_, exc: ValueError) -> JSONResponse:
    return JSONResponse(status_code=400, content={'detail': str(exc)})
