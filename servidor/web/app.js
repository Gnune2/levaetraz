/* Painel do levaetraz. Sem framework e sem build: um arquivo, servido direto.
   O estado real mora no servidor; aqui só desenhamos o que o WebSocket manda. */

const $ = (s) => document.querySelector(s);
const $$ = (s) => [...document.querySelectorAll(s)];

let token = localStorage.getItem('levaetraz_token') || '';
let ws = null;
let caminhoAtual = null;
let selecionados = new Set();
let listaAtual = [];
let fila = [];                 // uploads pendentes do navegador
let enviando = false;

// ── util ───────────────────────────────────────────────────
const esc = (t) => String(t ?? '').replace(/[&<>"']/g,
  (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

function tamanho(n) {
  const u = ['B', 'KB', 'MB', 'GB', 'TB'];
  let i = 0;
  while (n >= 1024 && i < u.length - 1) { n /= 1024; i += 1; }
  return i === 0 ? `${n} B` : `${n.toFixed(1)} ${u[i]}`;
}

function quando(ts) {
  if (!ts) return '';
  const s = (Date.now() / 1000) - ts;
  if (s < 60) return 'agora';
  if (s < 3600) return `há ${Math.floor(s / 60)} min`;
  if (s < 86400) return `há ${Math.floor(s / 3600)} h`;
  return new Date(ts * 1000).toLocaleDateString('pt-BR');
}

function aviso(texto, erro = false) {
  const el = document.createElement('div');
  el.className = 'aviso' + (erro ? ' erro' : '');
  el.textContent = texto;
  $('#avisos').appendChild(el);
  setTimeout(() => el.remove(), erro ? 7000 : 3800);
}

async function api(caminho, opcoes = {}) {
  const r = await fetch(caminho, {
    ...opcoes,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { 'X-Auth-Token': token } : {}),
      ...(opcoes.headers || {}),
    },
  });
  if (r.status === 401) { sair(); throw new Error('sessão expirada'); }
  const texto = await r.text();
  const dados = texto ? JSON.parse(texto) : {};
  if (!r.ok) throw new Error(dados.detail || `erro ${r.status}`);
  return dados;
}

const urlComToken = (base, params) =>
  `${base}?${new URLSearchParams({ ...params, t: token })}`;

// ── login ──────────────────────────────────────────────────
async function abrir() {
  const st = await (await fetch('/api/auth/status')).json();
  $('#login-sub').textContent = st.pode_criar_senha
    ? 'primeiro acesso — escolha a senha deste servidor'
    : 'entre com a senha do servidor';
  $('#senha2').hidden = !st.pode_criar_senha;
  $('#bt-entrar').textContent = st.pode_criar_senha ? 'CRIAR SENHA' : 'ENTRAR';
  $('#form-login').dataset.modo = st.pode_criar_senha ? 'setup' : 'login';

  if (!st.tem_senha && !st.pode_criar_senha) {
    $('#login-nota').hidden = false;
    $('#login-nota').innerHTML =
      'Este servidor ainda não tem senha, e ela só pode ser criada no próprio PC.<br>'
      + 'Abra <code>http://127.0.0.1:8765</code> nele, ou rode <code>python main.py --senha</code>.';
  }

  if (token) {
    try { await api('/api/info'); return entrar(); } catch { token = ''; }
  }
  $('#login').hidden = false;
}

function entrar() {
  $('#login').hidden = true;
  $('#painel').hidden = false;
  conectarWs();
  irPara('arquivos');
  carregarInfo();
}

function sair() {
  token = '';
  localStorage.removeItem('levaetraz_token');
  if (ws) { ws.onclose = null; ws.close(); ws = null; }
  $('#painel').hidden = true;
  $('#login').hidden = false;
}

$('#form-login').addEventListener('submit', async (e) => {
  e.preventDefault();
  const senha = $('#senha').value;
  const setup = $('#form-login').dataset.modo === 'setup';
  if (setup && senha !== $('#senha2').value) return aviso('as senhas não conferem', true);
  try {
    const r = await api(setup ? '/api/auth/setup' : '/api/auth/login', {
      method: 'POST',
      body: JSON.stringify({ senha, dispositivo: 'painel web' }),
    });
    token = r.token;
    localStorage.setItem('levaetraz_token', token);
    $('#senha').value = $('#senha2').value = '';
    entrar();
  } catch (err) { aviso(err.message, true); }
});

$('#bt-sair').addEventListener('click', async () => {
  try { await api('/api/auth/logout', { method: 'POST' }); } catch { /* já era */ }
  sair();
});

// ── navegação entre abas ───────────────────────────────────
function irPara(aba) {
  $$('nav button[data-aba]').forEach((b) => {
    b.setAttribute('aria-current', b.dataset.aba === aba ? 'page' : 'false');
  });
  $$('section[data-painel]').forEach((s) => { s.hidden = s.dataset.painel !== aba; });
  location.hash = aba;

  if (aba === 'arquivos') listarArquivos(caminhoAtual);
  if (aba === 'transferencias') carregarEnvios();
  if (aba === 'celular') carregarCelular();
  if (aba === 'dispositivos') carregarSessoes();
  if (aba === 'ajustes') carregarAjustes();
  if (aba === 'ajuda') $('#conteudo-ajuda').innerHTML = AJUDA;
}

$$('nav button[data-aba]').forEach((b) => {
  b.addEventListener('click', () => irPara(b.dataset.aba));
});

// ── websocket ──────────────────────────────────────────────
function conectarWs() {
  if (ws) ws.close();
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  ws = new WebSocket(`${proto}://${location.host}/ws?token=${encodeURIComponent(token)}`);

  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.type === 'status') aviso(m.text, m.level === 'erro');
    if (m.type === 'snapshot' || m.type === 'envio' || m.type === 'resumo') {
      if (!$('section[data-painel="transferencias"]').hidden) carregarEnvios();
      if (m.type === 'envio' && m.envio?.estado === 'concluido'
          && !$('section[data-painel="arquivos"]').hidden) {
        listarArquivos(caminhoAtual);
      }
    }
    if (m.type === 'lista' && !$('section[data-painel="arquivos"]').hidden) {
      listarArquivos(caminhoAtual);
    }
  };
  // Reconecta sozinho: o servidor reinicia (deploy, watchdog) e o painel não
  // pode ficar mudo esperando um F5.
  ws.onclose = () => { if (token) setTimeout(conectarWs, 2500); };
}

async function carregarInfo() {
  try {
    const i = await api('/api/info');
    $('#estado-servidor').textContent = `v${i.version} · ${i.hostname}`;
  } catch { /* o painel funciona sem isso */ }
}

// ══════════════════════════════════════════════════════════
// ARQUIVOS
// ══════════════════════════════════════════════════════════
const ICONE = {
  pasta: '📁', imagem: '🖼', video: '🎬', audio: '🎵',
  documento: '📄', arquivo: '📦',
};

async function listarArquivos(caminho) {
  let r;
  try {
    r = await api('/api/arquivos' + (caminho ? `?caminho=${encodeURIComponent(caminho)}` : ''));
  } catch (e) { return aviso(e.message, true); }

  caminhoAtual = r.caminho;
  listaAtual = r.itens;
  selecionados.clear();
  desenharSelecao();
  desenharTrilha(r);

  $('#arq-espaco').textContent = r.espaco?.total
    ? `${tamanho(r.espaco.livre)} livres de ${tamanho(r.espaco.total)}`
    : '';
  $('#arq-sub').textContent = r.arquivos
    ? `${r.arquivos} arquivo${r.arquivos > 1 ? 's' : ''} · ${tamanho(r.bytes)}`
    : 'o que está aqui, o celular alcança';

  const lista = $('#lista-arquivos');
  lista.innerHTML = '';
  $('#arq-vazio').hidden = r.itens.length > 0;
  $('#arq-vazio').textContent = r.erro
    || 'Pasta vazia. Solte um arquivo aqui em cima para mandá-lo do PC, ou mande do celular.';

  for (const item of r.itens) {
    const el = document.createElement('div');
    el.className = 'arq';
    el.dataset.caminho = item.caminho;
    el.title = item.nome;

    const capa = item.thumb
      ? `<img loading="lazy" src="${urlComToken('/api/arquivos/thumb', { caminho: item.caminho })}"
              onerror="this.replaceWith(Object.assign(document.createElement('div'),
                       {className:'icone',textContent:'${ICONE[item.tipo]}'}))">`
      : `<div class="icone">${ICONE[item.tipo] || ICONE.arquivo}</div>`;

    el.innerHTML = capa
      + (item.tipo === 'video' ? '<div class="marca-play">▶</div>' : '')
      + `<div class="nome">${esc(item.nome)}`
      + (item.tipo === 'pasta' ? '' : `<div class="tam">${tamanho(item.tamanho)}</div>`)
      + '</div>';

    el.addEventListener('click', (ev) => {
      if (item.tipo === 'pasta' && !ev.ctrlKey && !ev.metaKey) return listarArquivos(item.caminho);
      alternarSelecao(item.caminho, el);
    });
    el.addEventListener('dblclick', () => {
      if (item.tipo !== 'pasta') {
        window.open(urlComToken('/api/arquivos/baixar', { caminho: item.caminho }), '_blank');
      }
    });
    lista.appendChild(el);
  }
}

function desenharTrilha(r) {
  const t = $('#trilha');
  t.innerHTML = '';
  const raiz = document.createElement('button');
  raiz.textContent = 'pastas compartilhadas';
  raiz.addEventListener('click', () => listarArquivos(null));
  t.appendChild(raiz);

  if (r.pai) {
    const sep = document.createElement('span');
    sep.className = 'sep'; sep.textContent = '/';
    const acima = document.createElement('button');
    acima.textContent = '..';
    acima.addEventListener('click', () => listarArquivos(r.pai));
    t.append(sep, acima);
  }
  const sep2 = document.createElement('span');
  sep2.className = 'sep'; sep2.textContent = '/';
  const aqui = document.createElement('button');
  aqui.textContent = r.caminho.split('/').pop() || r.caminho;
  t.append(sep2, aqui);
}

function alternarSelecao(caminho, el) {
  if (selecionados.has(caminho)) selecionados.delete(caminho);
  else selecionados.add(caminho);
  el.setAttribute('aria-selected', selecionados.has(caminho));
  desenharSelecao();
}

function desenharSelecao() {
  const n = selecionados.size;
  $('#selecao').hidden = n === 0;
  $('#selecao-texto').textContent = `${n} selecionado${n > 1 ? 's' : ''}`;
}

$('#bt-limpar-sel').addEventListener('click', () => {
  selecionados.clear();
  $$('.arq[aria-selected="true"]').forEach((e) => e.setAttribute('aria-selected', 'false'));
  desenharSelecao();
});

$('#bt-baixar-sel').addEventListener('click', () => {
  // Um clique por arquivo: o navegador não baixa vários de uma tag só, e
  // empacotar num zip no servidor gastaria disco e CPU à toa.
  for (const c of selecionados) {
    window.open(urlComToken('/api/arquivos/baixar', { caminho: c }), '_blank');
  }
});

$('#bt-apagar-sel').addEventListener('click', async () => {
  const n = selecionados.size;
  if (!confirm(`Apagar ${n} item(ns) de vez? Não vai para a lixeira.`)) return;
  try {
    const r = await api('/api/arquivos/apagar', {
      method: 'POST',
      body: JSON.stringify({ caminhos: [...selecionados] }),
    });
    aviso(`${r.apagados} apagado(s)`);
    r.erros.forEach((e) => aviso(e, true));
    listarArquivos(caminhoAtual);
  } catch (e) { aviso(e.message, true); }
});

$('#bt-nova-pasta').addEventListener('click', async () => {
  const nome = prompt('nome da pasta nova:');
  if (!nome) return;
  try {
    await api('/api/arquivos/pasta', {
      method: 'POST',
      body: JSON.stringify({ onde: caminhoAtual, nome }),
    });
    listarArquivos(caminhoAtual);
  } catch (e) { aviso(e.message, true); }
});

$('#bt-atualizar').addEventListener('click', () => listarArquivos(caminhoAtual));

// ── enviar do navegador ────────────────────────────────────
// Mesmo protocolo em três passos que o app usa. O painel ganha de graça a
// retomada: recarregar a página e soltar o mesmo arquivo continua de onde parou.
const zona = $('#zona-solta');
['dragenter', 'dragover'].forEach((ev) => zona.addEventListener(ev, (e) => {
  e.preventDefault(); zona.classList.add('ativa');
}));
['dragleave', 'drop'].forEach((ev) => zona.addEventListener(ev, (e) => {
  e.preventDefault(); zona.classList.remove('ativa');
}));
zona.addEventListener('drop', (e) => enfileirar([...e.dataTransfer.files]));
$('#bt-escolher').addEventListener('click', () => $('#entrada-arquivo').click());
$('#entrada-arquivo').addEventListener('change', (e) => {
  enfileirar([...e.target.files]);
  e.target.value = '';
});

function enfileirar(arquivos) {
  if (!arquivos.length) return;
  fila.push(...arquivos);
  aviso(`${arquivos.length} arquivo(s) na fila`);
  if (!enviando) proximoDaFila();
}

async function proximoDaFila() {
  const arquivo = fila.shift();
  if (!arquivo) { enviando = false; listarArquivos(caminhoAtual); return; }
  enviando = true;
  try {
    await enviarArquivo(arquivo);
  } catch (e) {
    aviso(`${arquivo.name}: ${e.message}`, true);
  }
  proximoDaFila();
}

async function sha256De(arquivo) {
  // crypto.subtle só existe em contexto seguro: vale em http://127.0.0.1, mas
  // não em http://100.x.x.x (tailnet) nem na LAN. Sem ele o arquivo sobe
  // inteiro e a duplicata só é pega no fim — funciona igual, gasta mais rede.
  if (!window.crypto?.subtle) return null;
  try {
    const bytes = await crypto.subtle.digest('SHA-256', await arquivo.arrayBuffer());
    return [...new Uint8Array(bytes)].map((b) => b.toString(16).padStart(2, '0')).join('');
  } catch { return null; }
}

async function enviarArquivo(arquivo) {
  const abertura = await api('/api/envios', {
    method: 'POST',
    body: JSON.stringify({
      nome: arquivo.name,
      tamanho: arquivo.size,
      destino: caminhoAtual,
      sha256: await sha256De(arquivo),
      modificado_em: arquivo.lastModified / 1000,
    }),
  });

  if (abertura.estado === 'duplicado') {
    aviso(`${arquivo.name} já estava no PC — nada foi enviado`);
    return;
  }

  const corpo = abertura.offset > 0 ? arquivo.slice(abertura.offset) : arquivo;
  const r = await fetch(`/api/envios/${abertura.id}?offset=${abertura.offset}`, {
    method: 'PUT',
    headers: { 'X-Auth-Token': token, 'Content-Type': 'application/octet-stream' },
    body: corpo,
    duplex: 'half',
  });
  if (!r.ok) throw new Error((await r.json()).detail || `erro ${r.status}`);

  // O fim é quem decide se virou arquivo novo ou duplicata: sem olhar a
  // resposta, o painel anunciava "enviado" em cima do aviso "já estava no PC".
  const fim = await api(`/api/envios/${abertura.id}/fim`, { method: 'POST' });
  if (fim.estado === 'duplicado') {
    aviso(`${arquivo.name} já estava no PC — nada foi guardado`);
  } else {
    aviso(`${arquivo.name} enviado`);
  }
}

// ══════════════════════════════════════════════════════════
// TRANSFERÊNCIAS
// ══════════════════════════════════════════════════════════
const ROTULO = {
  aguardando: ['aguardando', 'off'], recebendo: ['recebendo', 'ok'],
  pausado: ['pausado', 'aviso'], concluido: ['concluído', 'ok'],
  duplicado: ['já existia', 'aviso'], erro: ['erro', 'erro'],
  cancelado: ['cancelado', 'off'],
};

async function carregarEnvios() {
  let r;
  try { r = await api('/api/envios'); } catch (e) { return aviso(e.message, true); }

  const res = r.resumo || {};
  $('#cartao-resumo').hidden = !res.ativos;
  $('#resumo-texto').textContent = res.texto || '';
  $('#resumo-barra').style.width = `${res.percent || 0}%`;

  const lista = $('#lista-envios');
  lista.innerHTML = '';
  $('#env-vazio').hidden = r.envios.length > 0;

  for (const e of r.envios) {
    const [rotulo, cor] = ROTULO[e.estado] || [e.estado, 'off'];
    const el = document.createElement('div');
    el.className = 'envio';
    el.innerHTML = `
      <div class="topo">
        <span class="ponto ${cor}"></span>
        <span class="nome">${esc(e.nome)}</span>
        <span class="selo" style="color:var(--${cor === 'off' ? 'texto-apagado' : cor})">${rotulo}</span>
        ${e.estado === 'recebendo' || e.estado === 'pausado'
          ? `<button class="acao" data-cancelar="${e.id}" title="cancelar">✕</button>` : ''}
      </div>
      ${e.estado === 'recebendo'
        ? `<div class="barra"><i style="width:${e.percent}%"></i></div>` : ''}
      <div class="meta">
        <span>${e.estado === 'recebendo'
          ? `${tamanho(e.recebido)} de ${tamanho(e.tamanho)} · ${e.percent}%`
          : tamanho(e.tamanho)}</span>
        <span class="destaque">${esc(e.mensagem || e.origem || '')} ${quando(e.criado_em)}</span>
      </div>`;
    lista.appendChild(el);
  }

  $$('#lista-envios [data-cancelar]').forEach((b) => {
    b.addEventListener('click', async () => {
      try {
        await api(`/api/envios/${b.dataset.cancelar}`, { method: 'DELETE' });
        carregarEnvios();
      } catch (err) { aviso(err.message, true); }
    });
  });
}

$('#bt-limpar-hist').addEventListener('click', async () => {
  try {
    const r = await api('/api/envios/limpar', { method: 'POST' });
    aviso(`${r.removidos} removido(s) do histórico`);
    carregarEnvios();
  } catch (e) { aviso(e.message, true); }
});

// ══════════════════════════════════════════════════════════
// CELULAR
// ══════════════════════════════════════════════════════════
async function qrDe(dados) {
  return (await api(`/api/qr?dados=${encodeURIComponent(dados)}`)).svg;
}

async function carregarCelular() {
  let c;
  try { c = await api('/api/celular'); } catch (e) { return aviso(e.message, true); }

  if (!c.alcancavel) {
    $('#apk-estado').innerHTML = `
      <div class="nota alerta">
        <b>O celular ainda não alcança este servidor.</b><br>
        Ele está escutando só no tailnet, e nenhum outro aparelho entrou na sua
        rede Tailscale. Instale o Tailscale no celular com a <b>mesma conta</b>
        antes de gerar o QR — senão o link abre e fica carregando para sempre.
      </div>`;
  } else if (c.apk.presente) {
    $('#apk-estado').innerHTML = `
      <p>App <b>${esc(c.apk.versao)}</b> · ${c.apk.tamanho_mb} MB · pronto para o celular baixar.</p>
      <div class="chips">
        <button class="chip" id="bt-apk-qr">MOSTRAR QR DE INSTALAÇÃO</button>
        <button class="chip" id="bt-apk-atualizar">procurar versão nova</button>
      </div>`;
    $('#bt-apk-qr').addEventListener('click', async () => {
      try {
        const url = `http://${c.endereco}/app.apk?t=${encodeURIComponent(token)}`;
        $('#apk-qr-svg').innerHTML = await qrDe(url);
        $('#apk-qr').hidden = false;
      } catch (e) { aviso(e.message, true); }
    });
    $('#bt-apk-atualizar').addEventListener('click', baixarApk);
  } else {
    $('#apk-estado').innerHTML = `
      <p>O app ainda não está neste PC. Ele vem do release do GitHub.</p>
      <button class="bt" id="bt-apk-baixar">BAIXAR O APP</button>`;
    $('#bt-apk-baixar').addEventListener('click', baixarApk);
  }
  carregarVpn();
}

async function baixarApk() {
  $$('#apk-estado button').forEach((b) => { b.disabled = true; });
  aviso('baixando o app… pode levar um minuto');
  try {
    const r = await api('/api/celular/apk', { method: 'POST' });
    aviso(`app ${r.apk.versao} pronto`);
    carregarCelular();
  } catch (e) {
    aviso(e.message, true);
    $$('#apk-estado button').forEach((b) => { b.disabled = false; });
  }
}

$('#bt-parear').addEventListener('click', async () => {
  try {
    const r = await api('/api/auth/pair/novo', { method: 'POST' });
    $('#par-qr-svg').innerHTML = r.svg;
    $('#par-qr').hidden = false;
    $('#par-endereco').textContent = `${r.endereco} · uso único · 10 min`;
  } catch (e) { aviso(e.message, true); }
});

let poolVpn = null;

async function carregarVpn() {
  let t;
  try { t = await api('/api/tailscale'); } catch { return; }

  if (t.ativo) {
    if (poolVpn) { clearInterval(poolVpn); poolVpn = null; }
    $('#vpn-estado').innerHTML = `
      <p><span class="ponto ok"></span> Tailscale ativo — <b>${esc(t.ip)}</b>${
        t.nome ? ` · ${esc(t.nome)}` : ''}</p>
      <p>Instale o Tailscale no celular com a <b>mesma conta</b> e deixe a VPN
         ligada. Aí o mesmo endereço funciona em casa e no 4G.</p>`;
    return;
  }

  if (!t.instalado) {
    $('#vpn-estado').innerHTML = `
      <p><span class="ponto aviso"></span> Tailscale não está instalado.</p>
      <p>Sem ele o servidor só responde na rede local. Para instalar, rode no
         terminal: <code>./systemd/tailscale.sh</code></p>`;
    return;
  }

  const l = t.login || {};
  if (l.url) {
    const svg = await qrDe(l.url).catch(() => null);
    $('#vpn-estado').innerHTML = `
      <p><span class="ponto aviso"></span> Falta autenticar. Abra o link para entrar
         na sua conta Tailscale:</p>
      <p><a href="${esc(l.url)}" target="_blank" rel="noopener">${esc(l.url)}</a></p>
      ${svg ? `<div class="qr">${svg}</div>` : ''}`;
  } else if (l.rodando) {
    $('#vpn-estado').innerHTML =
      '<p><span class="ponto aviso"></span> conectando… aguardando o link de login</p>';
  } else {
    $('#vpn-estado').innerHTML = `
      <p><span class="ponto off"></span> Tailscale instalado, mas desconectado.</p>
      ${l.erro ? `<p style="color:var(--erro)">${esc(l.erro)}</p>` : ''}
      <button class="bt" id="bt-vpn">CONECTAR AO TAILSCALE</button>`;
    $('#bt-vpn')?.addEventListener('click', async () => {
      try {
        await api('/api/tailscale/login', { method: 'POST' });
        aviso('gerando o link de login…');
        if (poolVpn) clearInterval(poolVpn);
        poolVpn = setInterval(carregarVpn, 2500);
      } catch (e) { aviso(e.message, true); }
    });
  }
}

// ══════════════════════════════════════════════════════════
// DISPOSITIVOS
// ══════════════════════════════════════════════════════════
async function carregarSessoes() {
  let r;
  try { r = await api('/api/auth/sessions'); } catch (e) { return aviso(e.message, true); }

  const lista = $('#lista-sessoes');
  lista.innerHTML = r.sessoes.length ? '' : '<div class="vazio">nenhum dispositivo conectado</div>';

  for (const s of r.sessoes) {
    const el = document.createElement('div');
    el.className = 'cartao';
    el.innerHTML = `
      <div class="linha">
        <span class="k">dispositivo</span>
        <span class="v">${esc(s.dispositivo)}</span>
      </div>
      <div class="linha"><span class="k">visto</span>
        <span class="v">${quando(s.ultimo_uso)}</span></div>
      <div class="linha"><span class="k">criado</span>
        <span class="v">${quando(s.criado_em)}</span></div>
      <button class="chip perigo" data-revogar="${s.id}" style="margin-top:12px">
        desconectar
      </button>`;
    lista.appendChild(el);
  }

  $$('[data-revogar]').forEach((b) => {
    b.addEventListener('click', async () => {
      try {
        await api(`/api/auth/sessions/${b.dataset.revogar}`, { method: 'DELETE' });
        aviso('dispositivo desconectado');
        carregarSessoes();
      } catch (e) { aviso(e.message, true); }
    });
  });
}

$('#bt-revogar-todas').addEventListener('click', async () => {
  if (!confirm('Desconectar todos, inclusive este painel?')) return;
  try {
    await api('/api/auth/sessions/todas', { method: 'DELETE' });
    sair();
  } catch (e) { aviso(e.message, true); }
});

// ══════════════════════════════════════════════════════════
// AJUSTES
// ══════════════════════════════════════════════════════════
async function carregarAjustes() {
  let r; let p;
  try {
    r = await api('/api/config/rede');
    p = await api('/api/preferencias');
  } catch (e) { return aviso(e.message, true); }

  // pastas
  const lp = $('#lista-pastas');
  lp.innerHTML = '';
  r.pastas.forEach((caminho, i) => {
    const el = document.createElement('div');
    el.className = 'linha';
    el.innerHTML = `<span class="v" style="text-align:left">${esc(caminho)}</span>`;
    if (r.pastas.length > 1) {
      const bt = document.createElement('button');
      bt.className = 'chip perigo';
      bt.textContent = 'remover';
      bt.addEventListener('click', () => salvarPastas(r.pastas.filter((_, j) => j !== i)));
      el.appendChild(bt);
    }
    lp.appendChild(el);
  });

  $('#nota-jaula').hidden = !r.jaula.amplo;
  if (r.jaula.amplo) {
    $('#nota-jaula').className = 'nota alerta';
    $('#nota-jaula').innerHTML =
      '<b>Você compartilhou a home inteira.</b> Funciona, mas qualquer arquivo seu '
      + 'passa a estar ao alcance de quem tiver a senha. As pastas sensíveis '
      + `(<code>${r.jaula.negadas.length}</code> delas, como <code>.ssh</code>) `
      + 'continuam bloqueadas, mas isso é lista de negação: o que for novo entra liberado.';
  }

  // preferências
  $('#prefs').innerHTML = `
    <div class="linha">
      <span class="k">não receber o mesmo arquivo duas vezes</span>
      <input type="checkbox" id="p-dup" ${p.pular_duplicados ? 'checked' : ''}
             style="width:auto">
    </div>
    <div class="linha">
      <span class="k">separar em Imagens / Vídeos / Documentos</span>
      <input type="checkbox" id="p-org" ${p.organizar_por_tipo ? 'checked' : ''}
             style="width:auto">
    </div>
    <div class="linha">
      <span class="k">quantos envios guardar no histórico</span>
      <input type="number" id="p-hist" value="${p.manter_historico}" min="0" max="5000"
             style="width:110px">
    </div>
    <div class="linha">
      <span class="k">pasta padrão de chegada</span>
      <span class="v">${esc(p.destino_padrao)}</span>
    </div>
    <button class="bt" id="bt-prefs" style="margin-top:12px">SALVAR</button>`;

  $('#bt-prefs').addEventListener('click', async () => {
    try {
      await api('/api/preferencias', {
        method: 'PUT',
        body: JSON.stringify({
          destino_padrao: p.destino_padrao,
          pular_duplicados: $('#p-dup').checked,
          organizar_por_tipo: $('#p-org').checked,
          manter_historico: Number($('#p-hist').value),
        }),
      });
      aviso('preferências salvas');
    } catch (e) { aviso(e.message, true); }
  });

  // rede
  $('#rede-form').innerHTML = `
    <label><span class="txt">onde escutar</span>
      <select id="r-bind">
        ${['auto', 'tailscale', 'lan', 'local'].map((m) =>
          `<option value="${m}" ${r.bind === m ? 'selected' : ''}>${m}</option>`).join('')}
      </select>
    </label>
    <label><span class="txt">porta</span>
      <input type="number" id="r-porta" value="${r.porta}" min="1" max="65535">
    </label>
    <button class="bt" id="bt-rede">SALVAR REDE</button>
    <div class="nota">Mudar isto exige reiniciar o servidor:
      <code>systemctl --user restart levaetraz</code></div>`;

  $('#bt-rede').addEventListener('click', async () => {
    try {
      await api('/api/config/rede', {
        method: 'PUT',
        body: JSON.stringify({ bind: $('#r-bind').value, porta: Number($('#r-porta').value) }),
      });
      aviso('salvo — reinicie o servidor para valer');
    } catch (e) { aviso(e.message, true); }
  });
}

async function salvarPastas(pastas) {
  try {
    await api('/api/config/rede', { method: 'PUT', body: JSON.stringify({ pastas }) });
    aviso('pastas atualizadas');
    carregarAjustes();
    caminhoAtual = null;
  } catch (e) { aviso(e.message, true); }
}

$('#bt-add-pasta').addEventListener('click', async () => {
  const novo = $('#nova-pasta-caminho').value.trim();
  if (!novo) return;
  const r = await api('/api/config/rede');
  await salvarPastas([...r.pastas, novo]);
  $('#nova-pasta-caminho').value = '';
});

$('#form-senha').addEventListener('submit', async (e) => {
  e.preventDefault();
  try {
    await api('/api/auth/senha', {
      method: 'POST',
      body: JSON.stringify({ atual: $('#senha-atual').value, nova: $('#senha-nova').value }),
    });
    aviso('senha trocada — entrando de novo');
    setTimeout(sair, 1200);
  } catch (err) { aviso(err.message, true); }
});

$('#bt-limpar-thumbs').addEventListener('click', async () => {
  try {
    const r = await api('/api/arquivos/thumbs/limpar', { method: 'POST' });
    aviso(`${r.removidas} miniatura(s) removida(s)`);
  } catch (e) { aviso(e.message, true); }
});

$('#bt-ver-log').addEventListener('click', async () => {
  try {
    const r = await api('/api/sistema/log?linhas=80');
    $('#log').hidden = false;
    $('#log').textContent = r.linhas.join('\n') || '(vazio — o log só existe rodando sob systemd)';
  } catch (e) { aviso(e.message, true); }
});

// ── arranque ───────────────────────────────────────────────
window.addEventListener('hashchange', () => {
  const aba = location.hash.slice(1);
  if (aba && !$('#painel').hidden) irPara(aba);
});

abrir();
