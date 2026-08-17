"""
Impede a máquina de dormir enquanto houver download ativo.

Porte de frontend/app.py:117-170. No app antigo isso rodava num
`root.after(30000)`; aqui é acionado por evento — o JobManager avisa quando o
número de jobs ativos muda.
"""

import platform
import subprocess
import threading

_OS = platform.system()
_lock = threading.Lock()
_inibidor: subprocess.Popen | None = None
_ligado = False

# Windows: ES_CONTINUOUS | ES_SYSTEM_REQUIRED | ES_DISPLAY_REQUIRED
_ES_MANTER = 0x80000001 | 0x00000002
_ES_LIBERAR = 0x80000000


def _ligar() -> None:
    global _inibidor
    if _OS == 'Windows':
        try:
            import ctypes
            ctypes.windll.kernel32.SetThreadExecutionState(_ES_MANTER)
        except Exception:
            pass
    elif _OS == 'Linux':
        if _inibidor is None or _inibidor.poll() is not None:
            try:
                _inibidor = subprocess.Popen(
                    ['systemd-inhibit', '--what=sleep:idle', '--who=levaetraz',
                     '--why=Download em andamento', 'sleep', '9999999'],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            except (FileNotFoundError, OSError):
                _inibidor = None
    elif _OS == 'Darwin':
        if _inibidor is None or _inibidor.poll() is not None:
            try:
                _inibidor = subprocess.Popen(
                    ['caffeinate', '-dimsu'],
                    stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                )
            except (FileNotFoundError, OSError):
                _inibidor = None


def _desligar() -> None:
    global _inibidor
    if _OS == 'Windows':
        try:
            import ctypes
            ctypes.windll.kernel32.SetThreadExecutionState(_ES_LIBERAR)
        except Exception:
            pass
    elif _inibidor is not None:
        try:
            _inibidor.terminate()
        except Exception:
            pass
        _inibidor = None


def atualizar(jobs_ativos: int) -> None:
    """Chamado pelo JobManager sempre que a contagem de jobs ativos muda."""
    global _ligado
    with _lock:
        desejado = jobs_ativos > 0
        if desejado == _ligado:
            return
        _ligado = desejado
        _ligar() if desejado else _desligar()


def encerrar() -> None:
    global _ligado
    with _lock:
        _ligado = False
        _desligar()
