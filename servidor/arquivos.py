"""
Navegação e operações nas pastas compartilhadas do PC.

Tudo aqui recebe caminho já validado pela jaula — este módulo não valida nada
sozinho, de propósito: uma única porta de entrada (servidor/jaula.py) é mais
fácil de auditar do que a mesma checagem repetida em oito funções.
"""

import os
import shutil
from pathlib import Path

from .jaula import SUFIXO_PARCIAL

IMG_EXT = {'.jpg', '.jpeg', '.png', '.gif', '.webp', '.bmp', '.avif', '.jfif', '.heic'}
VID_EXT = {'.mp4', '.webm', '.mkv', '.mov', '.avi', '.m4v', '.flv', '.ts', '.3gp'}
AUD_EXT = {'.mp3', '.m4a', '.opus', '.ogg', '.flac', '.wav', '.aac'}
DOC_EXT = {'.pdf', '.doc', '.docx', '.odt', '.txt', '.md', '.rtf',
           '.xls', '.xlsx', '.ods', '.ppt', '.pptx', '.epub', '.csv'}


def tipo_de(caminho: Path) -> str:
    ext = caminho.suffix.lower()
    if ext in IMG_EXT:
        return 'imagem'
    if ext in VID_EXT:
        return 'video'
    if ext in AUD_EXT:
        return 'audio'
    if ext in DOC_EXT:
        return 'documento'
    return 'arquivo'


def listar(caminho: str, so_pastas: bool = False) -> dict:
    """Conteúdo de uma pasta, com o que as duas telas precisam para desenhar."""
    alvo = Path(caminho)
    if not alvo.is_dir():
        alvo = alvo.parent

    itens: list[dict] = []
    try:
        entradas = list(os.scandir(alvo))
    except (PermissionError, OSError):
        return {'caminho': str(alvo), 'pai': str(alvo.parent), 'itens': [],
                'arquivos': 0, 'bytes': 0,
                'erro': 'sem permissão para ler esta pasta'}

    for e in entradas:
        # Ocultos ficam de fora, e a pasta dos .parcial é oculta justamente
        # para cair nesta regra: arquivo pela metade não aparece como pronto.
        if e.name.startswith('.') or e.name == SUFIXO_PARCIAL:
            continue
        try:
            eh_dir = e.is_dir()
            info = e.stat()
        except OSError:
            continue

        if eh_dir:
            itens.append({'nome': e.name, 'caminho': e.path, 'tipo': 'pasta',
                          'tamanho': 0, 'modificado': info.st_mtime, 'thumb': False})
        elif not so_pastas:
            p = Path(e.path)
            tipo = tipo_de(p)
            itens.append({'nome': e.name, 'caminho': e.path, 'tipo': tipo,
                          'tamanho': info.st_size, 'modificado': info.st_mtime,
                          'thumb': tipo in ('imagem', 'video')})

    itens.sort(key=lambda i: (i['tipo'] != 'pasta', i['nome'].lower()))
    arquivos = [i for i in itens if i['tipo'] != 'pasta']

    pai = str(alvo.parent) if alvo.parent != alvo else None
    return {'caminho': str(alvo), 'pai': pai, 'itens': itens,
            'arquivos': len(arquivos),
            'bytes': sum(i['tamanho'] for i in arquivos)}


def criar_pasta(caminho: Path) -> str:
    if caminho.exists():
        raise FileExistsError(f'já existe: {caminho.name}')
    caminho.mkdir(parents=True)
    return str(caminho)


def renomear(origem: Path, nome_novo: str) -> str:
    destino = origem.parent / nome_novo
    if destino.exists():
        raise FileExistsError(f'já existe um "{nome_novo}" nesta pasta')
    origem.rename(destino)
    return str(destino)


def apagar(caminho: Path) -> None:
    """
    Apaga de vez — não vai para lixeira nenhuma.

    O celular pergunta antes; o painel também. Aqui é o ponto sem volta, e é
    por isso que a jaula precisa estar apertada: só dá para apagar o que está
    dentro de uma pasta compartilhada.
    """
    if caminho.is_dir():
        shutil.rmtree(caminho)
    else:
        caminho.unlink()


def espaco(caminho: Path) -> dict:
    """Quanto ainda cabe no disco da pasta. O app avisa antes de encher."""
    try:
        uso = shutil.disk_usage(caminho)
        return {'total': uso.total, 'livre': uso.free, 'usado': uso.used}
    except OSError:
        return {'total': 0, 'livre': 0, 'usado': 0}


def formatar_bytes(n: float) -> str:
    for unidade in ('B', 'KB', 'MB', 'GB', 'TB'):
        if abs(n) < 1024 or unidade == 'TB':
            return f'{n:.0f} {unidade}' if unidade == 'B' else f'{n:.1f} {unidade}'
        n /= 1024
    return f'{n:.1f} TB'
