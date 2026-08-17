"""
Entrega do app Android e controle do Tailscale pelo painel.

Duas coisas que antes só existiam no terminal:

- o APK, que o celular baixa direto do servidor por QR — sem cabo, sem adb e
  sem precisar do Android SDK na máquina;
- o `tailscale up`, que normalmente pede root. O instalador roda
  `tailscale set --operator=$USER` uma vez, e a partir daí o painel consegue
  disparar o login e mostrar o link.
"""

import re
import shutil
import subprocess
import threading
from pathlib import Path

DIR_WEB = Path(__file__).parent / 'web'
APK = DIR_WEB / 'app.apk'
MARCA_VERSAO = DIR_WEB / 'app.apk.versao'

RAIZ = Path(__file__).parent.parent
BAIXAR_APK = RAIZ / 'scripts' / 'baixar_apk.py'


# ────────────────────────────────────────────────────────────
# APK
# ────────────────────────────────────────────────────────────
def estado_apk() -> dict:
    if not APK.is_file():
        return {'presente': False, 'versao': None, 'tamanho_mb': 0}
    return {
        'presente': True,
        'versao': MARCA_VERSAO.read_text().strip() if MARCA_VERSAO.exists() else '?',
        'tamanho_mb': round(APK.stat().st_size / (1024 * 1024)),
    }


def baixar_apk() -> tuple[bool, str]:
    """Busca o APK do release. Devolve (ok, mensagem)."""
    import sys
    try:
        r = subprocess.run(
            [sys.executable, str(BAIXAR_APK)],
            capture_output=True, text=True, timeout=600,
        )
    except (subprocess.SubprocessError, OSError) as exc:
        return False, f'não consegui rodar o download: {exc}'

    saida = (r.stdout + r.stderr).strip()
    return r.returncode == 0, saida or 'sem saída'


# ────────────────────────────────────────────────────────────
# TAILSCALE
# ────────────────────────────────────────────────────────────
_login = {'url': None, 'rodando': False, 'erro': None}
_lock = threading.Lock()


def pode_operar() -> bool:
    """O usuário atual consegue rodar `tailscale up` sem sudo?"""
    exe = shutil.which('tailscale')
    if not exe:
        return False
    try:
        r = subprocess.run([exe, 'status', '--json'],
                           capture_output=True, text=True, timeout=5)
        return r.returncode == 0
    except (subprocess.SubprocessError, OSError):
        return False


def estado_login() -> dict:
    with _lock:
        return dict(_login)


def iniciar_login() -> dict:
    """
    Dispara `tailscale up` em segundo plano e captura a URL de autenticação.

    O comando fica bloqueado até o usuário autenticar no navegador, então ele
    roda numa thread e o painel vai consultando o estado.
    """
    exe = shutil.which('tailscale')
    if not exe:
        return {'erro': 'tailscale não está instalado neste PC'}

    with _lock:
        if _login['rodando']:
            return dict(_login)
        _login.update(url=None, rodando=True, erro=None)

    def trabalhar() -> None:
        try:
            proc = subprocess.Popen(
                [exe, 'up', '--reset'],
                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True,
            )
            for linha in proc.stdout or []:
                achado = re.search(r'https://login\.tailscale\.com/\S+', linha)
                if achado:
                    with _lock:
                        _login['url'] = achado.group(0)
                if 'permission denied' in linha.lower() or 'access denied' in linha.lower():
                    with _lock:
                        _login['erro'] = (
                            'sem permissão. Rode uma vez no terminal: '
                            'sudo tailscale set --operator=$USER'
                        )
            proc.wait(timeout=300)
        except Exception as exc:                     # noqa: BLE001
            with _lock:
                _login['erro'] = str(exc)[:200]
        finally:
            with _lock:
                _login['rodando'] = False

    threading.Thread(target=trabalhar, daemon=True, name='tailscale-up').start()
    return estado_login()
