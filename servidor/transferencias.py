"""
Motor de envios (celular → PC).

O sentido contrário (PC → celular) não precisa de motor: é um GET com suporte a
Range, e quem controla a retomada é o cliente. Subir é que dá trabalho, porque
o servidor precisa guardar o pedaço já recebido em algum lugar e saber costurar
o resto depois de uma queda de rede, de bateria ou do PC inteiro.

Protocolo, em três passos:

    POST /api/envios          anuncia nome+tamanho, devolve id e o offset
                              de onde continuar (0 se for novo)
    PUT  /api/envios/{id}     manda os bytes a partir do offset
    POST /api/envios/{id}/fim fecha, confere o tamanho e move para o lugar

Os bytes vão para `<destino>/.levaetraz-parcial/<id>.parcial` e só viram o
arquivo de verdade no último passo. Isso importa: quem estiver olhando a pasta
nunca vê um arquivo pela metade se passando por pronto, e uma queda no meio não
deixa lixo com o nome do arquivo bom.
"""

import hashlib
import os
import shutil
import threading
import time
import uuid
from pathlib import Path
from typing import AsyncIterator, BinaryIO, Optional

from fastapi.concurrency import run_in_threadpool

from . import banco, config as cfg
from .esquemas import AbrirEnvio, Envio
from .eventos import bus
from .jaula import SUFIXO_PARCIAL, CaminhoNegado, jaula

# Pedaço lido por vez do corpo da requisição. 1 MiB equilibra syscalls e
# memória; abaixo disso o overhead aparece em arquivos grandes.
BLOCO = 1024 * 1024

# Extensões agrupadas quando "organizar por tipo" está ligado.
GRUPOS = {
    'Imagens': {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.heic', '.svg'},
    'Vídeos': {'.mp4', '.mkv', '.mov', '.avi', '.webm', '.m4v', '.3gp'},
    'Áudio': {'.mp3', '.m4a', '.flac', '.wav', '.ogg', '.opus', '.aac'},
    'Documentos': {'.pdf', '.doc', '.docx', '.odt', '.txt', '.md', '.xls',
                   '.xlsx', '.ods', '.ppt', '.pptx', '.epub'},
}


class ErroEnvio(RuntimeError):
    """Falha esperada de envio — vira 4xx, não 500."""


# ────────────────────────────────────────────────────────────
# ESTADO EM MEMÓRIA
# ────────────────────────────────────────────────────────────
class _Ativo:
    """O que só existe enquanto os bytes estão passando."""

    def __init__(self, envio_id: str) -> None:
        self.id = envio_id
        self.cancelar = threading.Event()
        self.pausar = threading.Event()
        self.inicio = time.time()
        self.bytes_no_inicio = 0
        self.ultimo_marco = 0.0


_ativos: dict[str, _Ativo] = {}
_lock = threading.RLock()


# ────────────────────────────────────────────────────────────
# CAMINHOS
# ────────────────────────────────────────────────────────────
def _dir_parcial(destino: Path) -> Path:
    d = destino / SUFIXO_PARCIAL
    d.mkdir(parents=True, exist_ok=True)
    return d


def _resolver_destino(pedido: AbrirEnvio) -> Path:
    """Pasta onde o arquivo vai cair, já validada pela jaula."""
    prefs = cfg.get_preferencias()
    bruto = pedido.destino or prefs.destino_padrao or cfg.get_pastas()[0]
    try:
        destino = jaula.validar(bruto)
    except CaminhoNegado as exc:
        raise ErroEnvio(f'destino recusado: {exc}') from exc

    if not destino.is_dir():
        raise ErroEnvio('destino não é uma pasta')

    if prefs.organizar_por_tipo:
        ext = Path(pedido.nome).suffix.lower()
        for grupo, exts in GRUPOS.items():
            if ext in exts:
                destino = destino / grupo
                destino.mkdir(parents=True, exist_ok=True)
                break
        else:
            destino = destino / 'Outros'
            destino.mkdir(parents=True, exist_ok=True)

    return destino


def _nome_livre(destino: Path, nome: str) -> Path:
    """
    Nome que ainda não existe na pasta. `foto.jpg` vira `foto (2).jpg`.

    Só chega aqui quem já passou pela checagem de duplicata, ou seja: o arquivo
    de mesmo nome tem conteúdo diferente e sobrescrever seria perda de dado.
    """
    alvo = destino / nome
    if not alvo.exists():
        return alvo
    tronco, ext = alvo.stem, alvo.suffix
    for n in range(2, 10_000):
        candidato = destino / f'{tronco} ({n}){ext}'
        if not candidato.exists():
            return candidato
    raise ErroEnvio('pasta cheia demais de arquivos com este nome')


# ────────────────────────────────────────────────────────────
# HASH
# ────────────────────────────────────────────────────────────
def sha_do_arquivo(caminho: Path) -> str:
    h = hashlib.sha256()
    with open(caminho, 'rb') as f:
        while pedaco := f.read(BLOCO):
            h.update(pedaco)
    return h.hexdigest()


# ────────────────────────────────────────────────────────────
# ABRIR
# ────────────────────────────────────────────────────────────
def abrir(pedido: AbrirEnvio, origem: str = '') -> dict:
    """
    Anuncia um arquivo. Devolve o id e de que byte continuar.

    Três respostas possíveis, e a ordem importa:
      1. já temos esse conteúdo  -> 'duplicado', nada trafega
      2. tem um .parcial da mesma tripla -> continua do offset
      3. novo -> offset 0
    """
    destino = _resolver_destino(pedido)
    prefs = cfg.get_preferencias()

    # 1. duplicata por conteúdo — o celular manda o sha e economiza o upload
    if prefs.pular_duplicados and pedido.sha256:
        anterior = banco.por_sha(pedido.sha256)
        if anterior and anterior.get('caminho_final') \
                and Path(anterior['caminho_final']).is_file():
            return {
                'id': anterior['id'],
                'offset': pedido.tamanho,
                'estado': 'duplicado',
                'caminho_final': anterior['caminho_final'],
                'mensagem': f"já está no PC como {Path(anterior['caminho_final']).name}",
            }

    # 2. retomada
    parado = banco.retomavel(pedido.nome, pedido.tamanho, str(destino))
    if parado and parado.get('parcial'):
        parcial = Path(parado['parcial'])
        if parcial.is_file():
            offset = parcial.stat().st_size
            if offset <= pedido.tamanho:
                banco.salvar({'id': parado['id'], 'estado': 'recebendo',
                              'recebido': offset, 'origem': origem or parado.get('origem', ''),
                              'mensagem': ''})
                bus.marcar(parado['id'], urgente=True)
                return {'id': parado['id'], 'offset': offset, 'estado': 'recebendo'}

    # 3. novo
    envio_id = uuid.uuid4().hex[:12]
    parcial = _dir_parcial(destino) / f'{envio_id}.parcial'
    parcial.touch()
    banco.salvar({
        'id': envio_id,
        'nome': pedido.nome,
        'tamanho': pedido.tamanho,
        'recebido': 0,
        'estado': 'aguardando',
        'destino': str(destino),
        'caminho_final': '',
        'parcial': str(parcial),
        'sha256': pedido.sha256 or '',
        'origem': origem,
        'mensagem': '',
        'criado_em': time.time(),
    })
    bus.marcar(envio_id, urgente=True)
    return {'id': envio_id, 'offset': 0, 'estado': 'aguardando'}


# ────────────────────────────────────────────────────────────
# RECEBER
# ────────────────────────────────────────────────────────────
async def receber(envio_id: str, pedacos: AsyncIterator[bytes], offset: int) -> dict:
    """
    Grava os bytes que estão chegando.

    É async de propósito. A alternativa — rodar tudo numa thread e alimentar um
    iterador síncrono a partir do `request.stream()` — exige uma fila com
    backpressure entre o event loop e a thread, e essa ponte trava feio se o
    lado que grava desistir antes do lado que lê. Aqui o loop dirige e só a
    escrita de cada bloco vai para a thread, onde ela custa menos de um
    milissegundo em disco local.
    """
    linha = banco.obter(envio_id)
    if linha is None:
        raise ErroEnvio('envio desconhecido')
    if linha['estado'] in ('concluido', 'duplicado', 'cancelado'):
        raise ErroEnvio(f"envio já {linha['estado']}")

    parcial = Path(linha['parcial'])
    if not parcial.parent.is_dir():
        parcial.parent.mkdir(parents=True, exist_ok=True)

    atual = parcial.stat().st_size if parcial.exists() else 0
    if offset > atual:
        raise ErroEnvio(f'offset {offset} à frente do que temos ({atual})')

    with _lock:
        ativo = _Ativo(envio_id)
        ativo.bytes_no_inicio = offset
        _ativos[envio_id] = ativo

    banco.salvar({'id': envio_id, 'estado': 'recebendo', 'recebido': offset})
    bus.marcar(envio_id, urgente=True)

    escritos = offset
    try:
        # 'r+b' e não 'ab': com append o SO ignora o seek, e um cliente que
        # reenvia um pedaço já recebido duplicaria bytes no meio do arquivo.
        modo = 'r+b' if parcial.exists() else 'wb'
        with open(parcial, modo) as f:
            await run_in_threadpool(_posicionar, f, offset)
            async for pedaco in pedacos:
                if ativo.cancelar.is_set():
                    raise ErroEnvio('cancelado')
                if ativo.pausar.is_set():
                    break
                if not pedaco:
                    continue
                await run_in_threadpool(f.write, pedaco)
                escritos += len(pedaco)
                _talvez_publicar(ativo, envio_id, escritos)
            # fsync antes de responder: sem isso o servidor confirma bytes que
            # ainda estão em cache do SO, e uma queda de energia logo depois
            # deixaria o offset do banco à frente do arquivo real.
            await run_in_threadpool(_sincronizar, f)
    except ErroEnvio:
        _finalizar_ativo(envio_id)
        banco.salvar({'id': envio_id, 'estado': 'cancelado', 'recebido': escritos,
                      'mensagem': 'cancelado pelo celular'})
        bus.marcar(envio_id, urgente=True)
        raise
    except OSError as exc:
        _finalizar_ativo(envio_id)
        banco.salvar({'id': envio_id, 'estado': 'erro', 'recebido': escritos,
                      'mensagem': f'erro ao gravar: {exc}'})
        bus.marcar(envio_id, urgente=True)
        raise ErroEnvio(f'erro ao gravar no PC: {exc}') from exc

    pausado = ativo.pausar.is_set()
    _finalizar_ativo(envio_id)
    banco.salvar({'id': envio_id, 'recebido': escritos,
                  'estado': 'pausado' if pausado else 'recebendo'})
    bus.marcar(envio_id, urgente=True)
    return {'id': envio_id, 'recebido': escritos, 'pausado': pausado}


def _posicionar(f: BinaryIO, offset: int) -> None:
    f.seek(offset)
    f.truncate(offset)          # descarta o que veio depois de um corte


def _sincronizar(f: BinaryIO) -> None:
    f.flush()
    os.fsync(f.fileno())


def _talvez_publicar(ativo: _Ativo, envio_id: str, escritos: int) -> None:
    """Marca progresso no máximo 5x/s — o bus coalesce, mas o banco não."""
    agora = time.time()
    if agora - ativo.ultimo_marco < 0.2:
        return
    ativo.ultimo_marco = agora
    banco.salvar({'id': envio_id, 'recebido': escritos})
    bus.marcar(envio_id)


def _finalizar_ativo(envio_id: str) -> None:
    with _lock:
        _ativos.pop(envio_id, None)


# ────────────────────────────────────────────────────────────
# CONCLUIR
# ────────────────────────────────────────────────────────────
def concluir(envio_id: str) -> dict:
    """Confere o tamanho, decide o nome final e move o .parcial para o lugar."""
    linha = banco.obter(envio_id)
    if linha is None:
        raise ErroEnvio('envio desconhecido')
    if linha['estado'] == 'concluido':
        return _para_envio(linha).model_dump()

    parcial = Path(linha['parcial'])
    if not parcial.is_file():
        raise ErroEnvio('o arquivo parcial sumiu do PC')

    tamanho_real = parcial.stat().st_size
    esperado = int(linha['tamanho'] or 0)
    if esperado and tamanho_real != esperado:
        banco.salvar({'id': envio_id, 'estado': 'erro', 'recebido': tamanho_real,
                      'mensagem': f'faltaram bytes: {tamanho_real} de {esperado}'})
        bus.marcar(envio_id, urgente=True)
        raise ErroEnvio(f'arquivo incompleto: {tamanho_real} de {esperado} bytes')

    sha = sha_do_arquivo(parcial)
    destino = Path(linha['destino'])
    prefs = cfg.get_preferencias()

    # Duplicata só é duplicata se o conteúdo bater. Mesmo nome com conteúdo
    # diferente é arquivo novo, e sobrescrever seria perder o antigo.
    if prefs.pular_duplicados:
        gemeo = _gemeo_no_disco(destino, linha['nome'], sha)
        if gemeo is not None:
            parcial.unlink(missing_ok=True)
            banco.salvar({'id': envio_id, 'estado': 'duplicado', 'sha256': sha,
                          'recebido': tamanho_real, 'caminho_final': str(gemeo),
                          'mensagem': f'idêntico ao que já estava em {gemeo.name}'})
            bus.marcar(envio_id, urgente=True)
            bus.status(f'{linha["nome"]} já estava no PC', 'aviso')
            return _para_envio(banco.obter(envio_id)).model_dump()

    final = _nome_livre(destino, linha['nome'])
    try:
        # replace() é atômico dentro do mesmo filesystem, e o .parcial mora na
        # pasta de destino justamente para garantir isso. move() é a rede de
        # segurança para quem apontou a pasta parcial para outro disco.
        try:
            parcial.replace(final)
        except OSError:
            shutil.move(str(parcial), str(final))
    except OSError as exc:
        banco.salvar({'id': envio_id, 'estado': 'erro',
                      'mensagem': f'não consegui mover para o destino: {exc}'})
        bus.marcar(envio_id, urgente=True)
        raise ErroEnvio(f'não consegui salvar em {destino}: {exc}') from exc

    banco.salvar({'id': envio_id, 'estado': 'concluido', 'sha256': sha,
                  'recebido': tamanho_real, 'caminho_final': str(final),
                  'mensagem': ''})
    banco.podar_historico(prefs.manter_historico)
    bus.marcar(envio_id, urgente=True)
    bus.status(f'{final.name} chegou no PC', 'ok')
    return _para_envio(banco.obter(envio_id)).model_dump()


def _gemeo_no_disco(destino: Path, nome: str, sha: str) -> Optional[Path]:
    """Arquivo já existente com o mesmo conteúdo, no destino ou no histórico."""
    candidato = destino / nome
    if candidato.is_file():
        try:
            if candidato.stat().st_size and sha_do_arquivo(candidato) == sha:
                return candidato
        except OSError:
            pass

    anterior = banco.por_sha(sha)
    if anterior and anterior.get('caminho_final'):
        p = Path(anterior['caminho_final'])
        if p.is_file():
            return p
    return None


# ────────────────────────────────────────────────────────────
# CONTROLE
# ────────────────────────────────────────────────────────────
def cancelar(envio_id: str) -> bool:
    linha = banco.obter(envio_id)
    if linha is None:
        return False
    with _lock:
        ativo = _ativos.get(envio_id)
    if ativo:
        ativo.cancelar.set()

    if linha.get('parcial'):
        Path(linha['parcial']).unlink(missing_ok=True)
    banco.salvar({'id': envio_id, 'estado': 'cancelado',
                  'mensagem': 'cancelado'})
    bus.marcar(envio_id, urgente=True)
    return True


def pausar(envio_id: str) -> bool:
    with _lock:
        ativo = _ativos.get(envio_id)
    if ativo is None:
        return False
    ativo.pausar.set()
    return True


def limpar_concluidos() -> int:
    n = banco.remover_concluidos()
    bus.publicar({'type': 'lista'})
    return n


# ────────────────────────────────────────────────────────────
# LEITURA
# ────────────────────────────────────────────────────────────
def _para_envio(linha: Optional[dict]) -> Envio:
    linha = linha or {}
    return Envio(
        id=linha.get('id', ''),
        nome=linha.get('nome', ''),
        tamanho=int(linha.get('tamanho') or 0),
        recebido=int(linha.get('recebido') or 0),
        estado=linha.get('estado') or 'aguardando',
        destino=linha.get('destino') or '',
        caminho_final=linha.get('caminho_final') or '',
        mensagem=linha.get('mensagem') or '',
        origem=linha.get('origem') or '',
        criado_em=float(linha.get('criado_em') or 0),
        atualizado_em=float(linha.get('atualizado_em') or 0),
    )


def snapshot(envio_id: str) -> Optional[dict]:
    linha = banco.obter(envio_id)
    if linha is None:
        return None
    d = _para_envio(linha).model_dump()
    d['percent'] = _para_envio(linha).percent
    return d


def listar() -> list[dict]:
    saida = []
    for linha in banco.listar():
        e = _para_envio(linha)
        d = e.model_dump()
        d['percent'] = e.percent
        saida.append(d)
    return saida


def resumo() -> dict:
    """Uma linha sobre o que está acontecendo agora. Vai no topo das duas telas."""
    with _lock:
        ativos = list(_ativos.values())

    em_curso = [linha for linha in banco.listar() if linha['estado'] == 'recebendo']
    if not em_curso:
        return {'ativos': 0, 'percent': 0.0, 'bytes_por_s': 0.0, 'texto': ''}

    total = sum(int(l['tamanho'] or 0) for l in em_curso)
    feito = sum(int(l['recebido'] or 0) for l in em_curso)
    percent = round(feito / total * 100, 1) if total else 0.0

    agora = time.time()
    taxa = 0.0
    for a in ativos:
        linha = banco.obter(a.id)
        if not linha:
            continue
        decorrido = max(agora - a.inicio, 0.001)
        taxa += (int(linha['recebido'] or 0) - a.bytes_no_inicio) / decorrido

    n = len(em_curso)
    texto = f'recebendo {n} arquivo{"s" if n > 1 else ""}'
    if taxa > 0:
        texto += f' · {formatar_bytes(taxa)}/s'
    return {'ativos': n, 'percent': percent, 'bytes_por_s': round(taxa, 1),
            'texto': texto}


def formatar_bytes(n: float) -> str:
    for unidade in ('B', 'KB', 'MB', 'GB', 'TB'):
        if abs(n) < 1024 or unidade == 'TB':
            return f'{n:.0f} {unidade}' if unidade == 'B' else f'{n:.1f} {unidade}'
        n /= 1024
    return f'{n:.1f} TB'


# ────────────────────────────────────────────────────────────
# BOOT
# ────────────────────────────────────────────────────────────
def limpar_orfaos() -> int:
    """
    Remove .parcial que não pertence a envio nenhum — sobra de um banco apagado
    ou de uma pasta que deixou de ser compartilhada. Sem isso a pasta oculta
    cresce para sempre sem ninguém notar.
    """
    conhecidos = {l['parcial'] for l in banco.listar() if l.get('parcial')}
    removidos = 0
    for pasta in cfg.get_pastas():
        d = Path(pasta) / SUFIXO_PARCIAL
        if not d.is_dir():
            continue
        for arquivo in d.glob('*.parcial'):
            if str(arquivo) not in conhecidos:
                try:
                    arquivo.unlink()
                    removidos += 1
                except OSError:
                    pass
    return removidos
