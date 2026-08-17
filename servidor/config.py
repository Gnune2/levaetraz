"""
Persistência de configuração no PC.

Guarda senha (só o hash), sessões ativas (só o hash do token), as pastas
compartilhadas e as preferências. Escrita atômica e com permissão 600 — o
arquivo tem material sensível.
"""

import json
import os
import threading
from pathlib import Path
from typing import Any, Optional

from .esquemas import Preferencias

_lock = threading.RLock()


def _dir_config() -> Path:
    if os.name == 'nt':
        base = os.environ.get('APPDATA') or os.path.expanduser('~')
        return Path(base) / 'levaetraz'
    base = os.environ.get('XDG_CONFIG_HOME') or os.path.expanduser('~/.config')
    return Path(base) / 'levaetraz'


CONFIG_DIR = _dir_config()
CONFIG_FILE = CONFIG_DIR / 'config.json'

# A pasta que o instalador cria e que é o alcance inteiro do servidor por padrão.
PASTA_PADRAO = Path.home() / 'Transferencias'


def garantir_pasta_padrao() -> Path:
    PASTA_PADRAO.mkdir(parents=True, exist_ok=True)
    return PASTA_PADRAO


def _defaults() -> dict:
    return {
        'versao_config': 1,
        'port': 8765,
        # Onde escutar. 'auto' = Tailscale + localhost se o tailnet existir,
        # senão 0.0.0.0 (LAN). Ver servidor/rede.py.
        'bind': 'auto',
        'senha_hash': None,
        'sessoes': [],
        'pareamento': None,
        # A jaula. Lista de permissão: o servidor não enxerga nada fora daqui.
        'pastas_compartilhadas': [str(PASTA_PADRAO)],
        'pastas_negadas': None,          # None = usa a lista padrão do jaula.py
        'preferencias': Preferencias(destino_padrao=str(PASTA_PADRAO)).model_dump(),
    }


# ────────────────────────────────────────────────────────────
# I/O
# ────────────────────────────────────────────────────────────
def _ler() -> dict:
    with _lock:
        if not CONFIG_FILE.exists():
            dados = _defaults()
            _gravar(dados)
            return dados
        try:
            dados = json.loads(CONFIG_FILE.read_text(encoding='utf-8'))
        except (json.JSONDecodeError, OSError):
            dados = _defaults()
            _gravar(dados)
            return dados

        padrao = _defaults()
        mudou = False
        for chave, valor in padrao.items():
            if chave not in dados:
                dados[chave] = valor
                mudou = True
        if mudou:
            _gravar(dados)
        return dados


def _gravar(dados: dict) -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True)
    tmp = CONFIG_FILE.with_suffix('.json.tmp')
    tmp.write_text(json.dumps(dados, indent=2, ensure_ascii=False), encoding='utf-8')
    if os.name != 'nt':
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
    tmp.replace(CONFIG_FILE)


def _atualizar(chave: str, valor: Any) -> None:
    with _lock:
        dados = _ler()
        dados[chave] = valor
        _gravar(dados)


# ────────────────────────────────────────────────────────────
# SENHA / SESSÕES / PAREAMENTO
# ────────────────────────────────────────────────────────────
def get_senha_hash() -> Optional[str]:
    return _ler().get('senha_hash')


def set_senha_hash(h: str) -> None:
    _atualizar('senha_hash', h)


def listar_sessoes() -> list[dict]:
    return list(_ler().get('sessoes', []))


def gravar_sessao(sessao: dict) -> None:
    with _lock:
        dados = _ler()
        sessoes = [s for s in dados.get('sessoes', []) if s['id'] != sessao['id']]
        sessoes.append(dict(sessao))
        dados['sessoes'] = sessoes
        _gravar(dados)


def remover_sessao(sessao_id: str) -> bool:
    with _lock:
        dados = _ler()
        antes = len(dados.get('sessoes', []))
        dados['sessoes'] = [s for s in dados.get('sessoes', []) if s['id'] != sessao_id]
        removeu = len(dados['sessoes']) != antes
        if removeu:
            _gravar(dados)
        return removeu


def limpar_sessoes() -> None:
    _atualizar('sessoes', [])


def get_pareamento() -> Optional[dict]:
    return _ler().get('pareamento')


def set_pareamento(p: Optional[dict]) -> None:
    _atualizar('pareamento', p)


def limpar_pareamento() -> None:
    _atualizar('pareamento', None)


# ────────────────────────────────────────────────────────────
# REDE / JAULA
# ────────────────────────────────────────────────────────────
def get_port() -> int:
    return int(_ler().get('port', 8765))


def set_port(p: int) -> None:
    _atualizar('port', int(p))


def get_bind() -> str:
    return _ler().get('bind', 'auto')


def set_bind(modo: str) -> None:
    _atualizar('bind', modo)


def get_pastas() -> list[str]:
    return list(_ler().get('pastas_compartilhadas') or [str(PASTA_PADRAO)])


def set_pastas(pastas: list[str]) -> None:
    _atualizar('pastas_compartilhadas', list(pastas))


def get_negadas() -> Optional[list[str]]:
    return _ler().get('pastas_negadas')


def set_negadas(negadas: Optional[list[str]]) -> None:
    _atualizar('pastas_negadas', negadas)


# ────────────────────────────────────────────────────────────
# PREFERÊNCIAS
# ────────────────────────────────────────────────────────────
def get_preferencias() -> Preferencias:
    try:
        return Preferencias(**_ler()['preferencias'])
    except Exception:
        return Preferencias(destino_padrao=get_pastas()[0])


def set_preferencias(novo: Preferencias) -> Preferencias:
    _atualizar('preferencias', novo.model_dump())
    return novo
