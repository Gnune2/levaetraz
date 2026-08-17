"""
Estado do sistema para o painel web.

Junta o que hoje só dá pra ver em vários comandos de terminal: se o serviço
está de pé, se o Tailscale está ativo, quanto disco sobrou nas pastas
compartilhadas e o fim do log.
"""

import platform
import shutil
import subprocess
import sys
import time
from pathlib import Path

from . import __version__, rede
from . import config as cfg

UNIDADE = 'levaetraz.service'


def _systemctl(*args: str) -> str:
    try:
        r = subprocess.run(
            ['systemctl', '--user', *args, UNIDADE],
            capture_output=True, text=True, timeout=5,
        )
        return r.stdout.strip()
    except (subprocess.SubprocessError, OSError):
        return ''


def servico() -> dict:
    """Estado do serviço systemd — vazio quando roda solto no terminal."""
    ativo = _systemctl('is-active')
    if not ativo:
        return {'gerenciado': False}

    props = {}
    try:
        r = subprocess.run(
            ['systemctl', '--user', 'show', UNIDADE,
             '-p', 'ActiveState', '-p', 'NRestarts', '-p', 'ExecMainStartTimestamp',
             '-p', 'MemoryCurrent', '-p', 'WatchdogUSec'],
            capture_output=True, text=True, timeout=5,
        )
        for linha in r.stdout.splitlines():
            if '=' in linha:
                k, v = linha.split('=', 1)
                props[k] = v
    except (subprocess.SubprocessError, OSError):
        pass

    linger = ''
    try:
        import os
        r = subprocess.run(
            ['loginctl', 'show-user', os.environ.get('USER', ''), '-p', 'Linger', '--value'],
            capture_output=True, text=True, timeout=5,
        )
        linger = r.stdout.strip()
    except (subprocess.SubprocessError, OSError):
        pass

    memoria = props.get('MemoryCurrent', '')
    return {
        'gerenciado': True,
        'ativo': ativo == 'active',
        'estado': props.get('ActiveState', ativo),
        'habilitado': _systemctl('is-enabled') == 'enabled',
        'reinicios': int(props.get('NRestarts') or 0),
        'desde': props.get('ExecMainStartTimestamp', ''),
        'memoria_mb': round(int(memoria) / 1048576, 1) if memoria.isdigit() else None,
        'watchdog': props.get('WatchdogUSec', '') not in ('', '0'),
        'linger': linger == 'yes',
    }


def disco(caminho: str | None = None) -> dict:
    alvo = Path(caminho or cfg.get_pastas()[0])
    while not alvo.exists() and alvo != alvo.parent:
        alvo = alvo.parent
    try:
        u = shutil.disk_usage(alvo)
    except OSError:
        return {}
    gb = 1024 ** 3
    return {
        'caminho': str(alvo),
        'total_gb': round(u.total / gb, 1),
        'livre_gb': round(u.free / gb, 1),
        'usado_pct': round((u.total - u.free) / u.total * 100) if u.total else 0,
    }


def log(linhas: int = 60) -> list[str]:
    try:
        r = subprocess.run(
            ['journalctl', '--user', '-u', UNIDADE, '-n', str(linhas),
             '--no-pager', '-o', 'short-precise'],
            capture_output=True, text=True, timeout=8,
        )
        return [l for l in r.stdout.splitlines() if l.strip()]
    except (subprocess.SubprocessError, OSError):
        return []


def tailscale() -> dict:
    ip = rede.ip_tailscale()
    return {
        'ativo': ip is not None,
        'ip': ip,
        'nome': rede.nome_tailscale() if ip else None,
        'instalado': shutil.which('tailscale') is not None,
    }


def resumo() -> dict:
    hosts, explicacao = ([], 'indisponível')
    try:
        hosts, explicacao = rede.resolver_bind(cfg.get_bind())
    except RuntimeError as exc:
        explicacao = str(exc)

    return {
        'versao': __version__,
        'hostname': platform.node(),
        'plataforma': f'{platform.system()} {platform.release()}',
        'python': sys.version.split()[0],
        'ffmpeg': shutil.which('ffmpeg'),
        'porta': cfg.get_port(),
        'bind': cfg.get_bind(),
        'bind_explicacao': explicacao,
        'hosts': hosts,
        'endereco_celular': rede.endereco_publicado(hosts, cfg.get_port()),
        'ip_lan': rede.ip_lan(),
        'pastas': cfg.get_pastas(),
        'agora': time.time(),
        'servico': servico(),
        'tailscale': tailscale(),
        'disco': disco(),
    }
