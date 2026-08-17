#!/usr/bin/env python3
"""
Baixa o APK do app Android a partir do release do GitHub.

Compilar o app num PC limpo exigiria ~3 GB de Android SDK e JDK, o que não faz
sentido para quem só quer rodar o servidor. Então o APK vive como release e o
painel serve ele para o celular baixar por QR.

O download passa pelo `gh` porque ele já resolve qual é o release mais recente
e cuida de redirecionamento e retomada — não é por autenticação: o repositório
é público.
"""

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

RAIZ = Path(__file__).resolve().parent.parent
DESTINO = RAIZ / 'servidor' / 'web' / 'app.apk'
REPO = 'Gnune2/levaetraz'


def versao_local() -> str | None:
    marca = DESTINO.with_suffix('.apk.versao')
    return marca.read_text().strip() if marca.exists() else None


def marcar(versao: str) -> None:
    DESTINO.with_suffix('.apk.versao').write_text(versao)


def ultimo_release() -> tuple[str, str] | None:
    """Devolve (tag, nome_do_asset) do release mais recente."""
    gh = shutil.which('gh')
    if not gh:
        return None
    try:
        r = subprocess.run(
            [gh, 'release', 'view', '--repo', REPO,
             '--json', 'tagName,assets'],
            capture_output=True, text=True, timeout=30,
        )
        if r.returncode != 0:
            return None
        dados = json.loads(r.stdout)
    except (subprocess.SubprocessError, json.JSONDecodeError, OSError):
        return None

    apks = [a['name'] for a in dados.get('assets', []) if a['name'].endswith('.apk')]
    return (dados['tagName'], apks[0]) if apks else None


def baixar() -> int:
    gh = shutil.which('gh')
    if not gh:
        print('✗ o `gh` (GitHub CLI) não está instalado.')
        print('  instale com: sudo dnf install gh   (ou apt/pacman)')
        print(f'  depois autentique: gh auth login')
        print(f'  ou baixe à mão em https://github.com/{REPO}/releases')
        return 1

    info = ultimo_release()
    if not info:
        print('✗ não consegui ler os releases.')
        print('  confira se o `gh` está autenticado: gh auth status')
        return 1

    tag, asset = info
    if DESTINO.exists() and versao_local() == tag:
        print(f'✓ APK já está na versão {tag}')
        return 0

    print(f'→ baixando o APK {tag}…')
    DESTINO.parent.mkdir(parents=True, exist_ok=True)
    tmp = DESTINO.with_suffix('.apk.parcial')
    tmp.unlink(missing_ok=True)

    r = subprocess.run(
        [gh, 'release', 'download', tag, '--repo', REPO,
         '--pattern', asset, '--output', str(tmp), '--clobber'],
        capture_output=True, text=True, timeout=600,
    )
    if r.returncode != 0 or not tmp.exists():
        tmp.unlink(missing_ok=True)
        print(f'✗ falhou: {(r.stderr or "erro desconhecido").strip()[:200]}')
        return 1

    tmp.replace(DESTINO)
    marcar(tag)
    mb = DESTINO.stat().st_size / (1024 * 1024)
    print(f'✓ APK {tag} pronto ({mb:.0f} MB)')
    return 0


if __name__ == '__main__':
    sys.exit(baixar())
