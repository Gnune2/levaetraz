/* A aba "como funciona". Fica aqui, no próprio painel, para não depender de
   ninguém abrir o README no GitHub. */

const AJUDA = `
<div class="ajuda">
<h1>Como funciona</h1>
<p class="sub">o PC vira um servidor de arquivos que só você alcança</p>

<h2>A ideia</h2>
<p>Seu PC roda um servidor pequeno que expõe <b>algumas pastas escolhidas</b> —
por padrão só <code>~/Transferencias</code>. O celular entra nelas pelo app, e
os arquivos vão nos dois sentidos: puxar do PC ou mandar para o PC. Nada passa
por servidor de terceiro, e não existe conta em lugar nenhum.</p>

<h2>Os dois sentidos</h2>
<table>
  <tr><th>sentido</th><th>como</th><th>o que acontece</th></tr>
  <tr><td>PC → celular</td><td>um GET comum</td>
      <td>com <code>Range</code>, então o celular retoma sozinho se a rede cair</td></tr>
  <tr><td>celular → PC</td><td>protocolo de 3 passos</td>
      <td>anuncia, manda os bytes, fecha — dá para continuar de onde parou</td></tr>
</table>

<h2>Por que subir é mais complicado</h2>
<p>Baixar é fácil: o arquivo já existe inteiro no PC e o cliente pede o pedaço
que quiser. Subir não — o servidor precisa guardar o pedaço recebido em algum
lugar e saber costurar o resto depois. Por isso os bytes chegam num arquivo
<code>.parcial</code> dentro de uma pasta oculta e só viram o arquivo de verdade
no último passo.</p>
<p>Duas coisas caem de graça daí: você <b>nunca</b> vê na pasta um arquivo pela
metade se passando por pronto, e uma queda no meio do caminho não deixa lixo com
o nome do arquivo bom.</p>

<h2>O mesmo arquivo duas vezes</h2>
<p>Antes de mandar, o celular calcula o <code>sha256</code> do arquivo e
pergunta. Se esse conteúdo já estiver no PC, o servidor responde
<b>duplicado</b> e nenhum byte trafega — mesmo que o nome seja outro.</p>
<p>Se o nome já existir mas o conteúdo for diferente, o novo entra como
<code>foto (2).jpg</code>. O antigo nunca é sobrescrito.</p>

<h2>A jaula</h2>
<p>O servidor só enxerga o que está dentro das pastas compartilhadas. É lista de
<b>permissão</b>: qualquer caminho fora delas é recusado, e a checagem acontece
depois de resolver os links simbólicos — então um atalho apontando para fora não
escapa.</p>
<div class="nota">Quanto menor a pasta compartilhada, menor o estrago se algo
der errado. O padrão de uma pasta só existe por isso.</div>

<h2>Segurança</h2>
<ul>
  <li><b>Senha</b> guardada só como hash Argon2id, nunca em texto.</li>
  <li><b>Sessões</b> revogáveis uma a uma, na aba dispositivos.</li>
  <li><b>Primeira senha</b> só pode ser criada de dentro do próprio PC.</li>
  <li><b>Tentativas erradas</b> travam o IP por tempo crescente, de 5s a 15min.</li>
  <li><b>Pareamento</b> por QR de uso único, válido 10 minutos.</li>
  <li><b>No celular</b>: digital a cada abertura, e nada aparece na bandeja de apps.</li>
</ul>

<h2>De onde dá para acessar</h2>
<table>
  <tr><th>modo</th><th>alcance</th></tr>
  <tr><td><code>auto</code></td><td>Tailscale se existir, senão a rede local</td></tr>
  <tr><td><code>tailscale</code></td><td>só pela VPN — de qualquer lugar do mundo</td></tr>
  <tr><td><code>lan</code></td><td>só quem estiver no mesmo Wi-Fi</td></tr>
  <tr><td><code>local</code></td><td>só o próprio PC</td></tr>
</table>
<p>Com Tailscale, PC e celular ganham um IP fixo entre si e conversam
diretamente, criptografado, sem abrir porta nenhuma no seu roteador.</p>

<h2>Quando o PC reinicia</h2>
<p>O serviço sobe sozinho junto com o sistema — antes mesmo de alguém fazer
login, graças ao <i>linger</i>. Se o processo travar sem morrer, o watchdog do
systemd percebe a falta de sinal de vida e reinicia em até um minuto.</p>
<p>Um envio que estava no meio vira <b>pausado</b>: o pedaço recebido continua
no disco, e o celular retoma pelo ponto exato quando reconectar.</p>

<h2>Comandos, se precisar</h2>
<pre>python main.py --senha             troca a senha (derruba as sessões)
python main.py --parear            QR de pareamento no terminal
python main.py --sessoes           lista os aparelhos conectados
python main.py --revogar todas     desconecta tudo

systemctl --user status levaetraz
journalctl --user -u levaetraz -f</pre>
</div>
`;
