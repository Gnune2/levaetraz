#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Entry point do levaetraz — servidor headless de troca de arquivos.

Não abre nenhuma janela: a interface vive no app Android e no painel web.

    python main.py                     sobe o servidor
    python main.py --senha             define/troca a senha (derruba as sessões)
    python main.py --parear            gera um QR de pareamento e sai
    python main.py --sessoes           lista os dispositivos conectados
    python main.py --revogar ID|todas  revoga sessão(ões)
    python main.py --bind tailscale    escuta só no tailnet
"""

import argparse
import getpass
import os
import sys
import time
from pathlib import Path


def _reexec_no_venv() -> None:
    """
    Se rodaram com o Python do sistema, salta para o do .venv.

    As dependências (uvicorn, fastapi, pillow…) vivem só no venv. Sem isto,
    `python main.py` morre com um ModuleNotFoundError que não ajuda em nada a
    descobrir o porquê.
    """
    if os.environ.get('LEVAETRAZ_REEXEC'):        # trava contra loop infinito
        return

    raiz = Path(__file__).resolve().parent
    dir_venv = raiz / '.venv'
    venv = dir_venv / ('Scripts/python.exe' if os.name == 'nt' else 'bin/python')
    if not venv.exists():
        return

    # Compara pelo sys.prefix, não pelo executável: o `python` do venv é um
    # symlink para o interpretador do sistema, então resolver os dois caminhos
    # faz eles coincidirem e o salto nunca aconteceria.
    try:
        if Path(sys.prefix).resolve() == dir_venv.resolve():
            return                            # já estamos no venv
    except OSError:
        return

    os.environ['LEVAETRAZ_REEXEC'] = '1'
    os.execv(str(venv), [str(venv), str(raiz / 'main.py'), *sys.argv[1:]])


_reexec_no_venv()

import uvicorn

from servidor import __version__, auth, rede
from servidor import config as cfg

VERDE = '\033[92m'
CIANO = '\033[96m'
AMARELO = '\033[93m'
VERMELHO = '\033[91m'
CINZA = '\033[90m'
NEGRITO = '\033[1m'
RESET = '\033[0m'


def imprimir_qr(dados: str) -> None:
    try:
        import qrcode
        qr = qrcode.QRCode(border=1)
        qr.add_data(dados)
        qr.make(fit=True)
        qr.print_ascii(invert=True)
    except Exception:
        print(f'{CINZA}(instale "qrcode" para ver o QR){RESET}')


# ────────────────────────────────────────────────────────────
# COMANDOS
# ────────────────────────────────────────────────────────────
def cmd_senha() -> int:
    print(f'{NEGRITO}Definir a senha do levaetraz{RESET}')
    print(f'{CINZA}Ela é guardada só como hash Argon2id. Mínimo 8 caracteres.{RESET}')
    print(f'{CINZA}Trocar a senha desconecta todos os dispositivos.{RESET}\n')

    nova = getpass.getpass('nova senha: ')
    conf = getpass.getpass('repita: ')
    if nova != conf:
        print(f'{VERMELHO}as senhas não conferem.{RESET}')
        return 1
    try:
        auth.definir_senha(nova)
    except ValueError as exc:
        print(f'{VERMELHO}{exc}{RESET}')
        return 1
    print(f'\n{VERDE}senha definida.{RESET} Sessões anteriores foram revogadas.')
    return 0


def cmd_parear(porta: int, host_pub: str | None = None) -> int:
    if not auth.tem_senha():
        print(f'{AMARELO}defina uma senha primeiro: python main.py --senha{RESET}')
        return 1

    codigo, expira = auth.novo_codigo_pareamento()
    endereco = host_pub or rede.endereco_publicado(
        rede.resolver_bind(cfg.get_bind())[0], porta
    )
    uri = f'levaetraz://{endereco}/{codigo}'
    minutos = int((expira - time.time()) / 60)

    print()
    print(f'{NEGRITO}Pareamento{RESET} {CINZA}— válido por {minutos} min, uso único{RESET}')
    print(f'  endereço  {CIANO}{endereco}{RESET}')
    print(f'  código    {CIANO}{codigo}{RESET}')
    print()
    imprimir_qr(uri)
    print(f'{CINZA}  aponte a câmera do app para o QR acima.{RESET}\n')
    return 0


def cmd_sessoes() -> int:
    sessoes = auth.listar_sessoes()
    if not sessoes:
        print(f'{CINZA}nenhum dispositivo conectado.{RESET}')
        return 0
    print(f'{NEGRITO}Dispositivos conectados{RESET}\n')
    agora = time.time()
    for s in sessoes:
        idade = (agora - s['ultimo_uso']) / 60
        visto = 'agora' if idade < 1 else f'há {int(idade)} min' if idade < 60 \
            else f'há {int(idade / 60)} h'
        print(f"  {CIANO}{s['id']}{RESET}  {s['dispositivo']:<24} {CINZA}visto {visto}{RESET}")
    print(f"\n{CINZA}revogar: python main.py --revogar <id>  (ou 'todas'){RESET}")
    return 0


def cmd_revogar(alvo: str) -> int:
    if alvo == 'todas':
        print(f'{VERDE}{auth.revogar_todas()} sessão(ões) revogada(s).{RESET}')
    elif auth.revogar(alvo):
        print(f'{VERDE}sessão {alvo} revogada.{RESET}')
    else:
        print(f'{VERMELHO}sessão {alvo} não encontrada.{RESET}')
        return 1
    return 0


# ────────────────────────────────────────────────────────────
# SERVIDOR
# ────────────────────────────────────────────────────────────
def banner(hosts: list[str], explicacao: str, porta: int) -> None:
    ts = rede.ip_tailscale()
    print()
    print(f'{VERDE}{NEGRITO}  levaetraz{RESET} {CINZA}v{__version__} — troca de arquivos PC ↔ celular{RESET}')
    print(f'{CINZA}  ─────────────────────────────────────────────{RESET}')
    print(f'  escutando  {CINZA}{explicacao}{RESET}')
    print(f'  porta      {CIANO}{porta}{RESET}')
    if ts:
        nome = rede.nome_tailscale()
        print(f'  tailnet    {CIANO}{ts}{RESET}{CINZA}{"  " + nome if nome else ""}{RESET}')
        print(f'{CINZA}             acessível de qualquer lugar pela VPN{RESET}')
    else:
        print(f'  rede local {CIANO}{rede.ip_lan()}{RESET}')
        print(f'{AMARELO}             Tailscale não detectado — só funciona na rede local{RESET}')

    if auth.tem_senha():
        n = len(auth.listar_sessoes())
        print(f'  senha      {VERDE}definida{RESET} {CINZA}· {n} dispositivo(s) conectado(s){RESET}')
    else:
        print(f'  senha      {VERMELHO}NÃO definida{RESET} {CINZA}— rode: python main.py --senha{RESET}')

    pastas = cfg.get_pastas()
    print(f'  pastas     {CINZA}{", ".join(pastas)}{RESET}')
    print(f'  config     {CINZA}{cfg.CONFIG_FILE}{RESET}')
    print(f'{CINZA}  ─────────────────────────────────────────────{RESET}')
    print(f'{CINZA}  parear um celular: python main.py --parear{RESET}')
    print(f'{CINZA}  nenhuma janela será aberta neste PC. Ctrl+C encerra.{RESET}')
    print()


def main() -> int:
    ap = argparse.ArgumentParser(description='Servidor de troca de arquivos levaetraz')
    ap.add_argument('--host', default=None, help='sobrescreve o endereço de escuta')
    ap.add_argument('--port', type=int, default=None, help='porta (padrão: a salva)')
    ap.add_argument('--bind', choices=['auto', 'tailscale', 'lan', 'local'],
                    default=None, help='onde escutar (grava na config)')
    ap.add_argument('--senha', action='store_true', help='define/troca a senha e sai')
    ap.add_argument('--parear', action='store_true', help='gera um QR de pareamento e sai')
    ap.add_argument('--sessoes', action='store_true', help='lista os dispositivos e sai')
    ap.add_argument('--revogar', metavar='ID', help='revoga uma sessão (ou "todas")')
    args = ap.parse_args()

    cfg.garantir_pasta_padrao()

    if args.senha:
        return cmd_senha()
    if args.sessoes:
        return cmd_sessoes()
    if args.revogar:
        return cmd_revogar(args.revogar)

    if args.bind:
        cfg.set_bind(args.bind)
    if args.port:
        cfg.set_port(args.port)

    porta = args.port or cfg.get_port()

    if args.parear:
        return cmd_parear(porta)

    try:
        hosts, explicacao = rede.resolver_bind(cfg.get_bind())
    except RuntimeError as exc:
        print(f'{VERMELHO}{exc}{RESET}')
        return 1
    if args.host:
        hosts, explicacao = [args.host], f'{args.host} (via --host)'

    banner(hosts, explicacao, porta)

    if not auth.tem_senha():
        print(f'{AMARELO}  Atenção: sem senha o app não consegue entrar.{RESET}')
        print(f'{AMARELO}  Rode em outro terminal: python main.py --senha{RESET}\n')

    try:
        # sockets abertos à mão porque o uvicorn.run() só aceita um host, e
        # aqui pode ser tailnet + localhost ao mesmo tempo
        sockets = rede.criar_sockets(hosts, porta)
    except OSError as exc:
        print(f'{VERMELHO}não consegui escutar em {hosts}:{porta} — {exc}{RESET}')
        return 1

    servidor = uvicorn.Server(uvicorn.Config(
        'servidor.app:app', log_level='warning', access_log=False,
    ))
    try:
        servidor.run(sockets=sockets)
    except KeyboardInterrupt:
        pass
    finally:
        for s in sockets:
            s.close()
    print(f'\n{CINZA}servidor encerrado.{RESET}')
    return 0


if __name__ == '__main__':
    sys.exit(main())
