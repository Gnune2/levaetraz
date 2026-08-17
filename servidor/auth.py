"""
Autenticação: senha (Argon2id) + sessões revogáveis + bloqueio por tentativa.

O modelo antigo era um token único e eterno: se vazasse, não havia como
revogar sem trocar o segredo e re-parear tudo. Agora:

- a **senha** é o segredo raiz, guardada só como hash Argon2id;
- o login devolve uma **sessão** com validade, ligada a um nome de dispositivo,
  que pode ser revogada individualmente;
- em casa, um **código de pareamento** de uso único e curta duração (o QR do
  terminal) troca por uma sessão sem digitar a senha no celular;
- tentativas erradas entram em bloqueio exponencial por IP.

Só o hash das sessões é gravado em disco — o token em si nunca é persistido.
"""

import hashlib
import secrets
import threading
import time
from dataclasses import dataclass, field
from typing import Optional

from argon2 import PasswordHasher
from argon2.exceptions import InvalidHashError, VerificationError, VerifyMismatchError

from . import config as cfg

_ph = PasswordHasher()

# Validade padrão de uma sessão: 90 dias, renovada a cada uso.
VALIDADE_SESSAO_SEG = 90 * 24 * 3600
# O código do QR vale pouco tempo e só uma vez.
VALIDADE_PAREAMENTO_SEG = 10 * 60

# Bloqueio progressivo por IP após senha errada.
LIMIAR_BLOQUEIO = 5
BLOQUEIO_BASE_SEG = 5
BLOQUEIO_MAX_SEG = 15 * 60


def _hash_token(token: str) -> str:
    """Sessões usam sha256: o token já tem 256 bits de entropia."""
    return hashlib.sha256(token.encode()).hexdigest()


# ────────────────────────────────────────────────────────────
# SENHA
# ────────────────────────────────────────────────────────────
def tem_senha() -> bool:
    return bool(cfg.get_senha_hash())


def definir_senha(nova: str) -> None:
    nova = (nova or '').strip()
    if len(nova) < 8:
        raise ValueError('a senha precisa ter pelo menos 8 caracteres')
    cfg.set_senha_hash(_ph.hash(nova))
    # Trocar a senha derruba todas as sessões: é o botão de pânico.
    cfg.limpar_sessoes()


def _senha_confere(candidata: str) -> bool:
    guardado = cfg.get_senha_hash()
    if not guardado:
        return False
    try:
        _ph.verify(guardado, candidata)
    except (VerifyMismatchError, VerificationError, InvalidHashError):
        return False
    # Reforça o hash se os parâmetros do Argon2 ficaram defasados
    if _ph.check_needs_rehash(guardado):
        cfg.set_senha_hash(_ph.hash(candidata))
    return True


# ────────────────────────────────────────────────────────────
# BLOQUEIO POR TENTATIVA
# ────────────────────────────────────────────────────────────
@dataclass
class _Tentativas:
    falhas: int = 0
    bloqueado_ate: float = 0.0


class ControleDeTentativas:
    def __init__(self) -> None:
        self._por_ip: dict[str, _Tentativas] = {}
        self._lock = threading.Lock()

    def bloqueado(self, ip: str) -> float:
        """Segundos restantes de bloqueio (0 = liberado)."""
        with self._lock:
            t = self._por_ip.get(ip)
            if not t:
                return 0.0
            return max(0.0, t.bloqueado_ate - time.time())

    def registrar_falha(self, ip: str) -> float:
        with self._lock:
            t = self._por_ip.setdefault(ip, _Tentativas())
            t.falhas += 1
            if t.falhas >= LIMIAR_BLOQUEIO:
                excedente = t.falhas - LIMIAR_BLOQUEIO
                espera = min(BLOQUEIO_MAX_SEG, BLOQUEIO_BASE_SEG * (2 ** excedente))
                t.bloqueado_ate = time.time() + espera
                return espera
            return 0.0

    def registrar_sucesso(self, ip: str) -> None:
        with self._lock:
            self._por_ip.pop(ip, None)


tentativas = ControleDeTentativas()


# ────────────────────────────────────────────────────────────
# SESSÕES
# ────────────────────────────────────────────────────────────
@dataclass
class Sessao:
    id: str
    dispositivo: str
    criada_em: float
    expira_em: float
    ultimo_uso: float
    token_hash: str = field(repr=False, default='')

    def resumo(self) -> dict:
        return {
            'id': self.id,
            'dispositivo': self.dispositivo,
            'criada_em': self.criada_em,
            'expira_em': self.expira_em,
            'ultimo_uso': self.ultimo_uso,
        }


def _criar_sessao(dispositivo: str) -> tuple[str, Sessao]:
    token = secrets.token_urlsafe(32)
    agora = time.time()
    s = Sessao(
        id=secrets.token_hex(6),
        dispositivo=(dispositivo or 'dispositivo').strip()[:60],
        criada_em=agora,
        expira_em=agora + VALIDADE_SESSAO_SEG,
        ultimo_uso=agora,
        token_hash=_hash_token(token),
    )
    cfg.gravar_sessao(s.__dict__)
    return token, s


def entrar_com_senha(senha: str, dispositivo: str, ip: str) -> tuple[str, Sessao]:
    restante = tentativas.bloqueado(ip)
    if restante > 0:
        raise PermissionError(f'muitas tentativas — tente de novo em {int(restante)}s')

    if not _senha_confere(senha):
        espera = tentativas.registrar_falha(ip)
        if espera:
            raise PermissionError(f'senha incorreta — bloqueado por {int(espera)}s')
        raise PermissionError('senha incorreta')

    tentativas.registrar_sucesso(ip)
    return _criar_sessao(dispositivo)


def conferir_senha(senha: str, ip: str) -> None:
    """Valida a senha sem emitir sessão. Levanta PermissionError se falhar."""
    restante = tentativas.bloqueado(ip)
    if restante > 0:
        raise PermissionError(f'muitas tentativas — tente de novo em {int(restante)}s')
    if not _senha_confere(senha):
        espera = tentativas.registrar_falha(ip)
        raise PermissionError(
            f'senha incorreta — bloqueado por {int(espera)}s' if espera else 'senha incorreta'
        )
    tentativas.registrar_sucesso(ip)


def entrar_com_pareamento(codigo: str, dispositivo: str, ip: str) -> tuple[str, Sessao]:
    restante = tentativas.bloqueado(ip)
    if restante > 0:
        raise PermissionError(f'muitas tentativas — tente de novo em {int(restante)}s')

    guardado = cfg.get_pareamento()
    if not guardado:
        raise PermissionError('nenhum código de pareamento ativo')
    if time.time() > guardado.get('expira_em', 0):
        cfg.limpar_pareamento()
        raise PermissionError('código de pareamento expirado')
    if not secrets.compare_digest(_hash_token(codigo or ''), guardado.get('hash', '')):
        tentativas.registrar_falha(ip)
        raise PermissionError('código de pareamento inválido')

    cfg.limpar_pareamento()          # uso único
    tentativas.registrar_sucesso(ip)
    return _criar_sessao(dispositivo)


def novo_codigo_pareamento() -> tuple[str, float]:
    """Gera o código que vai no QR. Devolve (codigo, expira_em)."""
    codigo = secrets.token_urlsafe(16)
    expira = time.time() + VALIDADE_PAREAMENTO_SEG
    cfg.set_pareamento({'hash': _hash_token(codigo), 'expira_em': expira})
    return codigo, expira


def validar_token(token: Optional[str]) -> Optional[Sessao]:
    """Devolve a sessão se o token valer; renova o último uso."""
    if not token:
        return None
    alvo = _hash_token(token)
    agora = time.time()

    for bruto in cfg.listar_sessoes():
        if not secrets.compare_digest(bruto.get('token_hash', ''), alvo):
            continue
        if agora > bruto.get('expira_em', 0):
            cfg.remover_sessao(bruto['id'])
            return None
        # renovação deslizante: usar o app mantém a sessão viva
        bruto['ultimo_uso'] = agora
        bruto['expira_em'] = agora + VALIDADE_SESSAO_SEG
        cfg.gravar_sessao(bruto)
        return Sessao(**bruto)
    return None


def sair(token: str) -> bool:
    s = validar_token(token)
    if not s:
        return False
    cfg.remover_sessao(s.id)
    return True


def listar_sessoes() -> list[dict]:
    agora = time.time()
    vivas = []
    for bruto in cfg.listar_sessoes():
        if agora > bruto.get('expira_em', 0):
            cfg.remover_sessao(bruto['id'])
            continue
        vivas.append(Sessao(**bruto).resumo())
    return sorted(vivas, key=lambda s: s['ultimo_uso'], reverse=True)


def revogar(sessao_id: str) -> bool:
    return cfg.remover_sessao(sessao_id)


def revogar_todas() -> int:
    n = len(cfg.listar_sessoes())
    cfg.limpar_sessoes()
    return n
