"""
Barramento de eventos: ponte entre as threads que escrevem os arquivos e o
event loop do FastAPI, com coalescência para não saturar o WebSocket.

Um envio de 500 MB atualiza o progresso milhares de vezes. Publicar tudo
derrubaria a bateria do celular à toa, então o `EventBus` mantém um "dirty set"
de envios e faz o flush num tick fixo — a última amostra de cada um vence.
Transições de estado (concluído, erro, cancelado) são marcadas como urgentes e
forçam um flush imediato.
"""

import asyncio
import json
import threading
import time
from typing import Any, Optional

TICK_SEG = 0.2          # 5 flushes por segundo
FILA_MAX = 512          # descarta eventos antigos se o cliente não acompanhar


class EventBus:
    def __init__(self) -> None:
        self._loop: Optional[asyncio.AbstractEventLoop] = None
        self._clientes: set[asyncio.Queue] = set()
        self._lock = threading.Lock()

        # jobs marcados como "sujos" desde o último flush
        self._dirty: set[str] = set()
        self._urgente = threading.Event()
        self._parar = threading.Event()

        # callbacks preenchidos pelas transferências (evita import circular)
        self.snapshot_envio = lambda eid: None
        self.snapshot_resumo = lambda: None

        self._flusher: Optional[threading.Thread] = None

    # ── ciclo de vida ────────────────────────────────────────
    def start(self, loop: asyncio.AbstractEventLoop) -> None:
        self._loop = loop
        self._parar.clear()
        self._flusher = threading.Thread(target=self._loop_flush, daemon=True,
                                         name='eventbus-flush')
        self._flusher.start()

    def stop(self) -> None:
        self._parar.set()
        self._urgente.set()

    # ── clientes WebSocket ───────────────────────────────────
    def registrar_cliente(self) -> asyncio.Queue:
        fila: asyncio.Queue = asyncio.Queue(maxsize=FILA_MAX)
        with self._lock:
            self._clientes.add(fila)
        return fila

    def remover_cliente(self, fila: asyncio.Queue) -> None:
        with self._lock:
            self._clientes.discard(fila)

    @property
    def num_clientes(self) -> int:
        with self._lock:
            return len(self._clientes)

    # ── publicação ───────────────────────────────────────────
    def marcar(self, eid: str, urgente: bool = False) -> None:
        """Chamado das threads que escrevem os bytes. Barato de propósito."""
        with self._lock:
            self._dirty.add(eid)
        if urgente:
            self._urgente.set()

    def publicar(self, evento: dict[str, Any]) -> None:
        """Envia um evento imediatamente, sem passar pelo coalescer."""
        if self._loop is None or self._loop.is_closed():
            return
        try:
            self._loop.call_soon_threadsafe(self._entregar, evento)
        except RuntimeError:
            pass

    def status(self, texto: str, nivel: str = 'ok') -> None:
        self.publicar({'type': 'status', 'text': texto, 'level': nivel})

    # ── interno ──────────────────────────────────────────────
    def _entregar(self, evento: dict[str, Any]) -> None:
        """Roda no event loop. Nunca bloqueia: cliente lento perde evento antigo."""
        payload = json.dumps(evento, ensure_ascii=False, default=str)
        with self._lock:
            filas = list(self._clientes)
        for fila in filas:
            try:
                fila.put_nowait(payload)
            except asyncio.QueueFull:
                try:
                    fila.get_nowait()       # descarta o mais antigo
                    fila.put_nowait(payload)
                except Exception:
                    pass

    def _loop_flush(self) -> None:
        ultimo_agg: Optional[str] = None
        while not self._parar.is_set():
            self._urgente.wait(timeout=TICK_SEG)
            self._urgente.clear()
            if self._parar.is_set():
                break

            with self._lock:
                ids = self._dirty
                self._dirty = set()

            if not ids and self.num_clientes == 0:
                continue

            for eid in ids:
                snap = self.snapshot_envio(eid)
                if snap is not None:
                    self.publicar({'type': 'envio', 'envio': snap})

            # o resumo só vai para a rede quando de fato muda
            agg = self.snapshot_resumo()
            if agg is not None:
                chave = json.dumps(agg, sort_keys=True, default=str)
                if chave != ultimo_agg:
                    ultimo_agg = chave
                    self.publicar({'type': 'resumo', **agg})


bus = EventBus()
