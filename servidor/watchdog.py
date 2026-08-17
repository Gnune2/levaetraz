"""
Integração com o watchdog do systemd, sem dependência externa.

`Restart=always` só religa o processo quando ele **morre**. Se o event loop
travar — deadlock, thread presa, disco que não responde — o processo continua
"vivo" e inútil. O watchdog resolve isso: o servidor precisa dar sinal de vida
periodicamente; se parar de dar, o systemd mata e sobe de novo.

Falar com o systemd é só mandar um datagrama para $NOTIFY_SOCKET, então dá
para fazer com a stdlib.
"""

import asyncio
import os
import socket
from typing import Optional

_socket: Optional[socket.socket] = None
_endereco: Optional[str] = None


def _preparar() -> bool:
    global _socket, _endereco
    if _socket is not None:
        return True

    caminho = os.environ.get('NOTIFY_SOCKET')
    if not caminho:
        return False                       # rodando fora do systemd

    # "@" no início significa socket abstrato do Linux
    _endereco = '\0' + caminho[1:] if caminho.startswith('@') else caminho
    try:
        _socket = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM | socket.SOCK_CLOEXEC)
    except OSError:
        return False
    return True


def notificar(mensagem: str) -> None:
    """Manda um estado para o systemd (READY=1, WATCHDOG=1, STATUS=...)."""
    if not _preparar():
        return
    try:
        _socket.sendto(mensagem.encode(), _endereco)   # type: ignore[union-attr]
    except OSError:
        pass


def pronto(status: str = 'servidor no ar') -> None:
    notificar(f'READY=1\nSTATUS={status}')


def status(texto: str) -> None:
    notificar(f'STATUS={texto}')


def encerrando() -> None:
    notificar('STOPPING=1\nSTATUS=encerrando')


def intervalo_seg() -> Optional[float]:
    """Metade do WatchdogSec da unit — o intervalo recomendado de ping."""
    bruto = os.environ.get('WATCHDOG_USEC')
    if not bruto:
        return None
    try:
        return (int(bruto) / 1_000_000) / 2
    except ValueError:
        return None


async def bater_ponto(intervalo: Optional[float] = None) -> None:
    """
    Task que pinga o watchdog enquanto o loop estiver saudável.

    Como roda *dentro* do event loop, ela só consegue pingar se o loop estiver
    girando — que é exatamente a condição que se quer verificar.
    """
    espera = intervalo or intervalo_seg()
    if not espera:
        return
    while True:
        await asyncio.sleep(espera)
        notificar('WATCHDOG=1')
