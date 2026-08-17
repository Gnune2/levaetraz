"""
Miniaturas dos arquivos das pastas compartilhadas.

Sem elas o navegador de arquivos vira uma lista de nomes, e escolher qual foto
mandar para o celular fica impossível. Cada miniatura é gerada uma vez e fica
em cache indexada por caminho+mtime, então editar o arquivo invalida sozinho.

O ffmpeg é opcional: sem ele, vídeo simplesmente não ganha miniatura — o resto
continua funcionando.
"""

import hashlib
import os
import shutil
import subprocess
import tempfile
from pathlib import Path

from .arquivos import tipo_de

THUMB_SIZE = (400, 400)
THUMB_QUALIDADE = 80


def _dir_cache() -> Path:
    base = os.environ.get('XDG_CACHE_HOME') or os.path.expanduser('~/.cache')
    if os.name == 'nt':
        base = os.environ.get('LOCALAPPDATA') or tempfile.gettempdir()
    d = Path(base) / 'levaetraz' / 'thumbs'
    d.mkdir(parents=True, exist_ok=True)
    return d


THUMB_DIR = _dir_cache()


# ────────────────────────────────────────────────────────────
# MINIATURAS
# ────────────────────────────────────────────────────────────
def _caminho_thumb(arquivo: Path) -> Path:
    """Cache por hash do caminho + mtime: arquivo alterado gera thumb nova."""
    try:
        mtime = arquivo.stat().st_mtime_ns
    except OSError:
        mtime = 0
    chave = hashlib.sha1(f'{arquivo}:{mtime}'.encode()).hexdigest()
    return THUMB_DIR / f'{chave}.jpg'


def _thumb_imagem(origem: Path, destino: Path) -> None:
    from PIL import Image, ImageOps
    with Image.open(origem) as im:
        im = ImageOps.exif_transpose(im)
        im = im.convert('RGB')
        im.thumbnail(THUMB_SIZE, Image.LANCZOS)
        im.save(destino, 'JPEG', quality=THUMB_QUALIDADE)


def _thumb_video(origem: Path, destino: Path) -> None:
    ffmpeg = shutil.which('ffmpeg')
    if not ffmpeg:
        raise RuntimeError('ffmpeg não encontrado para gerar a miniatura')

    def tentar(args: list[str]) -> None:
        subprocess.run(
            [ffmpeg, '-y', *args, '-frames:v', '1',
             '-vf', f'scale={THUMB_SIZE[0]}:-1', str(destino)],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=20,
        )

    # Busca 1s pra dentro (evita o frame preto de abertura); se o vídeo for
    # mais curto que isso, o seek falha e a segunda tentativa pega o começo.
    try:
        tentar(['-ss', '00:00:01', '-i', str(origem)])
    except subprocess.TimeoutExpired:
        pass
    if not destino.exists():
        tentar(['-i', str(origem)])


def thumb(caminho: str) -> Path | None:
    """Devolve o caminho da miniatura (gerando e cacheando na primeira vez)."""
    arquivo = Path(caminho).expanduser()
    if not arquivo.is_file():
        return None

    tipo = tipo_de(arquivo)
    if tipo not in ('imagem', 'video'):
        return None

    destino = _caminho_thumb(arquivo)
    if destino.exists():
        return destino

    try:
        if tipo == 'imagem':
            _thumb_imagem(arquivo, destino)
        else:
            _thumb_video(arquivo, destino)
    except Exception:
        destino.unlink(missing_ok=True)
        return None

    return destino if destino.exists() else None


def limpar_cache() -> int:
    """Apaga as miniaturas em cache. Devolve quantas foram removidas."""
    n = 0
    for f in THUMB_DIR.glob('*.jpg'):
        try:
            f.unlink()
            n += 1
        except OSError:
            pass
    return n
