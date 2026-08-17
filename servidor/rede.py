"""
Descoberta de rede e escolha do endereço de escuta.

Com Tailscale no ar, o certo é escutar **só** no tailnet + localhost. Aí nem
um vizinho no mesmo Wi-Fi de café alcança o servidor: o único caminho é estar
na sua rede privada, e o WireGuard já cuida da criptografia ponta a ponta.
"""

import json
import shutil
import socket
import subprocess
from typing import Optional


def ip_lan() -> str:
    """IP da interface usada para sair pra rede (sem enviar nada)."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(('10.255.255.255', 1))
        return s.getsockname()[0]
    except OSError:
        return '127.0.0.1'
    finally:
        s.close()


def ip_tailscale() -> Optional[str]:
    """IPv4 do tailnet (100.x.y.z), ou None se o Tailscale não estiver ativo."""
    exe = shutil.which('tailscale')
    if not exe:
        return None
    try:
        saida = subprocess.run(
            [exe, 'status', '--json'],
            capture_output=True, text=True, timeout=5,
        )
        if saida.returncode != 0:
            return None
        dados = json.loads(saida.stdout)
    except (subprocess.SubprocessError, json.JSONDecodeError, OSError):
        return None

    if dados.get('BackendState') != 'Running':
        return None
    for ip in (dados.get('Self') or {}).get('TailscaleIPs') or []:
        if ':' not in ip:            # descarta IPv6
            return ip
    return None


def nome_tailscale() -> Optional[str]:
    """Nome MagicDNS da máquina (ex: fedora.minha-tailnet.ts.net)."""
    exe = shutil.which('tailscale')
    if not exe:
        return None
    try:
        saida = subprocess.run([exe, 'status', '--json'],
                               capture_output=True, text=True, timeout=5)
        dados = json.loads(saida.stdout)
        nome = (dados.get('Self') or {}).get('DNSName') or ''
        return nome.rstrip('.') or None
    except Exception:
        return None


def peers_tailscale() -> list[dict]:
    """Outros aparelhos no tailnet. Vazio = só este PC está conectado."""
    exe = shutil.which('tailscale')
    if not exe:
        return []
    try:
        r = subprocess.run([exe, 'status', '--json'],
                           capture_output=True, text=True, timeout=5)
        dados = json.loads(r.stdout)
    except (subprocess.SubprocessError, json.JSONDecodeError, OSError):
        return []
    saida = []
    for p in (dados.get('Peer') or {}).values():
        ips = [i for i in p.get('TailscaleIPs') or [] if ':' not in i]
        saida.append({'nome': p.get('HostName') or '?',
                      'ip': ips[0] if ips else None,
                      'online': bool(p.get('Online')),
                      'so': p.get('OS') or ''})
    return saida


def resolver_bind(modo: str) -> tuple[list[str], str]:
    """
    Traduz o modo de bind em (lista_de_hosts, explicação).

      auto      -> tailnet + localhost se o tailnet existir, senão 0.0.0.0
      tailscale -> tailnet + localhost; falha explícita se a VPN não estiver no ar
      lan       -> 0.0.0.0 (qualquer um na rede local alcança)
      local     -> 127.0.0.1 (só o próprio PC)

    O localhost entra junto do tailnet de propósito: só o próprio PC alcança
    127.0.0.1, então não abre superfície nenhuma, e destrava health check,
    scripts locais e `adb reverse` na hora de depurar.
    """
    ts = ip_tailscale()

    if modo == 'tailscale':
        if not ts:
            raise RuntimeError(
                'bind=tailscale mas o Tailscale não está ativo. '
                'Rode "tailscale up" ou troque o bind para "lan".'
            )
        return [ts, '127.0.0.1'], f'Tailscale ({ts}) + localhost'

    if modo == 'lan':
        return ['0.0.0.0'], 'rede local inteira (0.0.0.0)'

    if modo == 'local':
        return ['127.0.0.1'], 'somente este PC (127.0.0.1)'

    # auto
    if ts:
        return [ts, '127.0.0.1'], f'Tailscale ({ts}) + localhost — detectado automaticamente'
    return ['0.0.0.0'], 'rede local inteira (0.0.0.0) — Tailscale não detectado'


def endereco_publicado(hosts: list[str] | str, porta: int) -> str:
    """O endereço que vale a pena mostrar no QR / banner."""
    lista = [hosts] if isinstance(hosts, str) else list(hosts)
    # o loopback nunca serve pro celular; o tailnet tem prioridade
    uteis = [h for h in lista if h not in ('127.0.0.1', '::1', '0.0.0.0', '::')]
    if uteis:
        return f'{uteis[0]}:{porta}'
    return f'{ip_lan()}:{porta}'


def criar_sockets(hosts: list[str], porta: int) -> list:
    """
    Abre um socket de escuta por host. O uvicorn só aceita um `host`, mas
    aceita uma lista de sockets já abertos — é assim que dá pra atender
    tailnet e localhost ao mesmo tempo num processo só.
    """
    sockets = []
    try:
        for h in hosts:
            s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            s.bind((h, porta))
            s.listen(2048)
            s.set_inheritable(True)
            sockets.append(s)
    except OSError:
        for s in sockets:
            s.close()
        raise
    return sockets
