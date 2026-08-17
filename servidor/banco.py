"""
Persistência dos envios.

Sem isto, reiniciar o PC apagaria o histórico e, pior, deixaria arquivos
`.parcial` órfãos no disco sem ninguém sabendo a que envio pertenciam. Com o
SQLite, um envio interrompido sobrevive ao reinício: o celular pergunta o
offset e continua de onde parou.

Guardamos o sha256 dos arquivos já recebidos justamente para o outro lado poder
perguntar "isso aqui você já tem?" antes de subir 300 MB de novo.
"""

import sqlite3
import threading
import time
from pathlib import Path
from typing import Optional

from . import config as cfg

_lock = threading.RLock()
_conn: Optional[sqlite3.Connection] = None


def _arquivo() -> Path:
    return cfg.CONFIG_DIR / 'envios.db'


def conectar() -> sqlite3.Connection:
    global _conn
    with _lock:
        if _conn is not None:
            return _conn
        cfg.CONFIG_DIR.mkdir(parents=True, exist_ok=True)
        _conn = sqlite3.connect(_arquivo(), check_same_thread=False)
        _conn.row_factory = sqlite3.Row
        # WAL: leitura e escrita concorrentes sem travar as threads de escrita
        _conn.execute('PRAGMA journal_mode=WAL')
        _conn.execute('PRAGMA synchronous=NORMAL')
        _conn.executescript('''
            CREATE TABLE IF NOT EXISTS envios (
                id            TEXT PRIMARY KEY,
                nome          TEXT NOT NULL,
                tamanho       INTEGER DEFAULT 0,
                recebido      INTEGER DEFAULT 0,
                estado        TEXT NOT NULL,
                destino       TEXT,
                caminho_final TEXT,
                parcial       TEXT,
                sha256        TEXT,
                origem        TEXT,
                mensagem      TEXT,
                criado_em     REAL,
                atualizado_em REAL
            );
            CREATE INDEX IF NOT EXISTS idx_envios_estado ON envios(estado);
            CREATE INDEX IF NOT EXISTS idx_envios_criado ON envios(criado_em);
            CREATE INDEX IF NOT EXISTS idx_envios_sha    ON envios(sha256);
        ''')
        _conn.commit()
        return _conn


# ────────────────────────────────────────────────────────────
# ESCRITA
# ────────────────────────────────────────────────────────────
CAMPOS = ('id', 'nome', 'tamanho', 'recebido', 'estado', 'destino',
          'caminho_final', 'parcial', 'sha256', 'origem', 'mensagem',
          'criado_em', 'atualizado_em')


def salvar(dados: dict) -> None:
    """
    Insere ou atualiza. Aceita dicionário parcial — a maior parte das chamadas
    só mexe em `estado` e `recebido`.

    UPDATE e INSERT são caminhos separados de propósito. Um
    `INSERT ... ON CONFLICT DO UPDATE` parece mais curto, mas o SQLite valida
    os NOT NULL da linha *antes* de resolver o conflito: uma atualização de
    progresso, que não carrega o nome do arquivo, estouraria em
    `NOT NULL constraint failed: envios.nome` em vez de atualizar a linha.
    """
    envio_id = dados.get('id')
    if not envio_id:
        raise ValueError('envio sem id')

    with _lock:
        c = conectar()
        campos = {k: dados[k] for k in CAMPOS if k in dados and k != 'id'}
        campos['atualizado_em'] = time.time()

        existe = c.execute('SELECT 1 FROM envios WHERE id = ?', (envio_id,)).fetchone()
        if existe:
            atribuicoes = ', '.join(f'{k} = ?' for k in campos)
            c.execute(f'UPDATE envios SET {atribuicoes} WHERE id = ?',
                      [*campos.values(), envio_id])
        else:
            campos.setdefault('nome', '')
            campos.setdefault('estado', 'aguardando')
            campos.setdefault('criado_em', time.time())
            campos['id'] = envio_id
            colunas = ', '.join(campos)
            marcas = ', '.join('?' * len(campos))
            c.execute(f'INSERT INTO envios ({colunas}) VALUES ({marcas})',
                      list(campos.values()))
        c.commit()


def remover(envio_id: str) -> None:
    with _lock:
        c = conectar()
        c.execute('DELETE FROM envios WHERE id = ?', (envio_id,))
        c.commit()


def remover_concluidos() -> int:
    with _lock:
        c = conectar()
        cur = c.execute(
            "DELETE FROM envios WHERE estado IN ('concluido','duplicado','erro','cancelado')")
        c.commit()
        return cur.rowcount


def podar_historico(limite: int) -> int:
    """Mantém só os N finalizados mais recentes."""
    with _lock:
        c = conectar()
        cur = c.execute('''
            DELETE FROM envios WHERE id IN (
                SELECT id FROM envios
                 WHERE estado IN ('concluido','duplicado','erro','cancelado')
                 ORDER BY criado_em DESC
                 LIMIT -1 OFFSET ?
            )
        ''', (limite,))
        c.commit()
        return cur.rowcount


# ────────────────────────────────────────────────────────────
# LEITURA
# ────────────────────────────────────────────────────────────
def listar() -> list[dict]:
    with _lock:
        c = conectar()
        return [dict(l) for l in
                c.execute('SELECT * FROM envios ORDER BY criado_em DESC')]


def obter(envio_id: str) -> Optional[dict]:
    with _lock:
        c = conectar()
        linha = c.execute('SELECT * FROM envios WHERE id = ?', (envio_id,)).fetchone()
        return dict(linha) if linha else None


def por_sha(sha: str) -> Optional[dict]:
    """Um envio concluído com este conteúdo — a base do 'não mandar duas vezes'."""
    if not sha:
        return None
    with _lock:
        c = conectar()
        linha = c.execute(
            "SELECT * FROM envios WHERE sha256 = ? AND estado = 'concluido' "
            'ORDER BY criado_em DESC LIMIT 1', (sha,)).fetchone()
        return dict(linha) if linha else None


def retomavel(nome: str, tamanho: int, destino: str) -> Optional[dict]:
    """
    Envio da mesma tripla que ficou pela metade. É o que permite continuar de
    onde parou depois de um reinício, sem o celular guardar id nenhum.
    """
    with _lock:
        c = conectar()
        linha = c.execute(
            "SELECT * FROM envios WHERE nome = ? AND tamanho = ? AND destino = ? "
            "AND estado IN ('recebendo','pausado','aguardando') "
            'ORDER BY criado_em DESC LIMIT 1',
            (nome, tamanho, destino)).fetchone()
        return dict(linha) if linha else None


# ────────────────────────────────────────────────────────────
# BOOT
# ────────────────────────────────────────────────────────────
def marcar_interrompidos() -> int:
    """
    No arranque: o que estava recebendo quando o processo morreu não está mais.
    Vira 'pausado' — o .parcial continua no disco e o celular retoma pelo offset.
    """
    with _lock:
        c = conectar()
        cur = c.execute('''
            UPDATE envios
               SET estado = 'pausado',
                   mensagem = 'interrompido quando o servidor parou'
             WHERE estado IN ('recebendo','aguardando')
        ''')
        c.commit()
        return cur.rowcount
