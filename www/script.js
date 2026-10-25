/* =====================================================================
   Dock Líquida · script.js
   Carregado no fim do <body>: roda depois que o HTML existe. As funções novas podem sobrescrever as antigas (a última declaração vale).

   Blocos, na ordem em que aparecem no arquivo:
    1. Avisos de erro na tela (útil no APK, onde não há console)
    2. 1. ESTADO: todos os números que o utilizador controla ficam aqui.
    3. 2. PAPEL DE PAREDE PADRÃO: um degradê para a ferramenta funcionar
    4. 3. DESENHO: a cada mudança, repintamos tudo na ordem certa
    5. DESENHO RÁPIDO
    6. 2b. REFRAÇÃO FÍSICA (método do artigo https://kube.io/blog/liquid-glass-css-svg/)
    7. 4. INTERFACE: cria os controlos e liga os eventos
    8. Configurações guardadas no aparelho (localStorage)
    9. Celular: abas do painel inferior
   10. Tela inicial ao vivo (experimental, só no APK)
   11. Alinhar sobre a tela inicial REAL (sobreposição nativa do Android)
   12. Aplicar direto como papel de parede (sem baixar nem salvar)
   13. v6: interface (miniaturas, mockup, handle e botão dividido)
   14. v8: abas Base | Dock, menu ☰ e cartões (inspirado no DockWalls)
   15. v9: cores dinâmicas (Material 3 / Material You)
   16. v10: áreas seguras reais (barras do sistema)
   17. v11: telas, galeria com histórico e preferências
   18. v12: sem print de referência; destino do wallpaper escolhido na hora de aplicar
   ===================================================================== */

/* ---------- Avisos de erro na tela (útil no APK, onde não há console) ---------- */
/* Mostra erros de JavaScript na própria tela (útil dentro do APK, onde não há console) */
function showErr(msg){
  const d = document.createElement('div');
  d.style.cssText = 'position:fixed;left:0;right:0;bottom:0;z-index:99;background:#b00020;color:#fff;font:12px/1.4 monospace;padding:8px;white-space:pre-wrap';
  d.textContent = 'Erro: ' + msg;
  document.body.appendChild(d);
}
window.addEventListener('error', e => showErr(e.message + ' (linha ' + e.lineno + ')'));
window.addEventListener('unhandledrejection', e => showErr(String(e.reason)));

/* =====================================================================
   1. ESTADO: todos os números que o utilizador controla ficam aqui.
   Mudou um número? Redesenhamos tudo a partir deste objeto.
   ===================================================================== */
const state = {
  x: 0,          // deslocamento horizontal em relação ao centro (px)
  y: 0,          // distância do topo da dock até o topo da imagem (px)
  w: 0, h: 0,    // largura e altura da dock (px)
  r: 0,          // raio da curvatura dos cantos (px)
  blur: 18,      // desfoque do fundo atrás do vidro (px)
  ior: 1.5,      // índice de refração do vidro (1 = ar, 1.5 = vidro)
  thick: 60,     // espessura do vidro (px)
  scale: 100,    // escala da refração (%)
  chroma: 8,     // aberração cromática (%)
  angle: -60,    // direção da luz do reflexo (graus)
  profile: 'squircle', // formato da superfície do bisel
  wpBlur: 0,        // desfoque do papel de parede inteiro (px)
  zoom: 100,     // zoom do papel de parede dentro do quadro (%; 100 = só o corte mínimo)
  panX: 0,       // enquadramento horizontal (-100 a 100)
  panY: 0,       // enquadramento vertical (-100 a 100)
  bevel: 34,     // largura do bisel, a faixa de borda que "entorta" a luz (px)
  shine: 60      // intensidade do brilho especular (0–100)
};

// Lista que gera os botões -1 / +1 (chave do estado, rótulo na tela)
const STEPPERS = [['y','Posição Y'],['x','Posição X'],['w','Largura'],['h','Altura'],['r','Curvatura']];
// Lista que gera os sliders (chave, rótulo, mínimo, máximo)
const SLIDERS = [['blur','Desfoque do vidro',0,60],
                 ['bevel','Largura do bisel',8,150],['thick','Espessura do vidro',4,200],
                 ['ior','Índice de refração',1,2,0.01],['scale','Escala da refração',0,150],
                 ['chroma','Aberração cromática',0,30],['shine','Brilho especular',0,100],['angle','Ângulo da luz',-180,180]];

const cv = document.getElementById('cv');       // elemento <canvas>
const ctx = cv.getContext('2d');                // "caneta" para desenhar nele
let wall = makeDefaultWall();                   // imagem de fundo (começa com um degradê)

/* =====================================================================
   2. PAPEL DE PAREDE PADRÃO: um degradê para a ferramenta funcionar
   mesmo antes de carregares uma imagem (tamanho de um iPhone 15).
   ===================================================================== */
function makeDefaultWall(){
  const c = document.createElement('canvas'); c.width = 1179; c.height = 2556;
  const g = c.getContext('2d');
  const lin = g.createLinearGradient(0,0,1179,2556);   // degradê diagonal
  lin.addColorStop(0,'#ff7a59'); lin.addColorStop(.5,'#7a4dff'); lin.addColorStop(1,'#12c2e9');
  g.fillStyle = lin; g.fillRect(0,0,1179,2556);
  const glow = g.createRadialGradient(300,2300,20,300,2300,700);  // mancha de luz perto da dock
  glow.addColorStop(0,'rgba(255,255,255,.7)'); glow.addColorStop(1,'rgba(255,255,255,0)');
  g.fillStyle = glow; g.fillRect(0,0,1179,2556);
  return c;
}

/* Tamanho de saída: o do print (o tamanho real do ecrã), o da imagem, ou o que escreveres.
   Ao mudar de tamanho, a dock é reescalada na mesma proporção para não "fugir". */
function applySize(){
  const oW = document.getElementById('outW'), oH = document.getElementById('outH');
  const modo = document.getElementById('outMode').value;
  if (modo === 'device'){ oW.value = Math.round(screen.width * devicePixelRatio); oH.value = Math.round(screen.height * devicePixelRatio); }   // resolução física deste aparelho
  const custom = modo === 'custom' || modo === 'device';
  oW.disabled = oH.disabled = modo !== 'custom';             // só o modo Personalizado deixa digitar
  let W, H;
  if (custom){
    W = Math.min(8192, Math.max(64, Math.round(+oW.value) || OUT.w));   // limites seguros do canvas
    H = Math.min(8192, Math.max(64, Math.round(+oH.value) || OUT.h));
  } else {
    [W, H] = sizeOf(wall);                                  // tamanho da própria imagem
  }
  oW.value = W; oH.value = H;
  if ((W !== OUT.w || H !== OUT.h) && OUT.w > 0){
    const fx = W/OUT.w, fy = H/OUT.h;                 // fatores de escala horizontal e vertical
    ['x','w','r','bevel','thick','blur','wpBlur'].forEach(k => state[k] = Math.round(state[k]*fx));
    ['y','h'].forEach(k => state[k] = Math.round(state[k]*fy));
  }
  OUT.w = W; OUT.h = H;                                      // tamanho real de exportação (o canvas da tela usa a prévia)
}

/* Desenha uma imagem no modo "cobrir": ela é ampliada só até preencher o quadro e o
   excesso é cortado. A proporção original NUNCA muda, então não há esticar.
   fixed = true ignora zoom e enquadramento (usado no print). */
/* Cache do fundo: papel de parede já cortado e desfocado fica guardado num canvas
   e só é refeito quando algo dele muda (assim arrastar a dock não recalcula o desfoque). */
const baseCv = document.createElement('canvas');
let baseKey = '', wallId = 0, glassKey = '', GD = null, GW = 0;
const OUT = { w: 0, h: 0 };                     // tamanho real de saída (o canvas visível pode ser menor)
const COL = new Float32Array(3);                // cor do pixel reutilizada (evita criar um array por pixel)
/* Fundo desfocado do vidro: calculado UMA vez e guardado em GD (RGBA).
   Antes era refeito a cada quadro, e isso pesava muito. */
function buildGlass(){
  const g = document.createElement('canvas'); g.width = baseCv.width; g.height = baseCv.height;
  const gc = g.getContext('2d');
  gc.drawImage(baseCv, 0, 0);                     // fundo nítido por baixo: evita borda transparente
  gc.filter = `blur(${state.blur}px) saturate(1.5) brightness(1.06)`;   // vidro desfoca e realça a cor
  gc.drawImage(baseCv, 0, 0);
  GW = g.width; GD = gc.getImageData(0, 0, g.width, g.height).data;
}
function drawCover(img, fixed, c = ctx){
  const [iw, ih] = sizeOf(img);
  const s = Math.max(cv.width/iw, cv.height/ih) * (fixed ? 1 : state.zoom/100 * (1 + 4*state.wpBlur/Math.min(cv.width, cv.height)));  // com desfoque, amplia um pouco para as bordas não ficarem transparentes
  // // escala única nos dois eixos
  const dw = iw*s, dh = ih*s;                                  // tamanho da imagem já escalada
  const px = fixed ? 0 : state.panX/100, py = fixed ? 0 : state.panY/100;
  const dx = (cv.width - dw)/2 - px*(dw - cv.width)/2;         // 0 = centrado; ±100 = encosta na borda
  const dy = (cv.height - dh)/2 - py*(dh - cv.height)/2;
  c.imageSmoothingEnabled = true; c.imageSmoothingQuality = 'high';
  c.filter = (!fixed && state.wpBlur > 0) ? `blur(${state.wpBlur}px)` : 'none';   // desfoque só no papel de parede
  c.drawImage(img, dx, dy, dw, dh);
  c.filter = 'none';
}

/* Tamanho real de qualquer fonte de imagem (canvas, bitmap...) */
const sizeOf = s => [s.naturalWidth || s.width, s.naturalHeight || s.height];

/* Define uma dock inicial proporcional ao tamanho do papel de parede */
function resetDock(){
  const W = OUT.w, H = OUT.h;   // usa o tamanho do quadro de saída (print ou personalizado)
  state.w = Math.round(W*0.9);
  state.h = Math.round(W*0.2);
  state.x = 0;
  state.y = H - state.h - Math.round(H*0.025);   // perto da base da tela
  state.r = Math.round(state.h*0.36);
  state.bevel = Math.round(state.h*0.35);        // bisel ≈ 1/3 da altura da dock
  state.thick = Math.round(state.bevel*1.2);     // espessura do vidro proporcional ao bisel
  state.blur = Math.round(W*0.012);              // desfoque proporcional ao tamanho da imagem
}

/* Usa uma nova imagem como papel de parede: o canvas passa a ter a resolução dela */
function setWall(img){
  wall = img; wallId++;                          // wallId invalida o cache do fundo
  state.zoom = 100; state.panX = 0; state.panY = 0;   // novo papel de parede: enquadramento padrão
  applySize();           // define o tamanho de saída (print, imagem ou personalizado)
  if (needReset){ resetDock(); needReset = false; }  // só na primeira vez: depois a dock ajustada é mantida
  syncInputs(); draw();
}

/* =====================================================================
   3. DESENHO: a cada mudança, repintamos tudo na ordem certa
   ===================================================================== */
/* ---------- DESENHO RÁPIDO ----------
   A prévia é desenhada em resolução reduzida (só o necessário para a tela do aparelho), o que
   deixa tudo muito mais leve no celular. O PNG exportado usa o tamanho real (full = true). */
const SCALED = ['x','y','w','h','r','bevel','thick','blur','wpBlur'];   // medidas em px que acompanham a escala
function previewFactor(){
  const mobile = innerWidth <= 820;
  if (mobile){                                           // celular: usa o tamanho real da caixa da prévia na tela
    const r = cv.getBoundingClientRect();
    if (r.width && OUT.h){
      const s = Math.min(r.width/OUT.w, r.height/OUT.h);
      return Math.min(1, Math.max(0.2, s * Math.min(devicePixelRatio || 1, 2)));
    }
  }
  const big = document.querySelector('.stage').classList.contains('big');
  const altura = innerHeight * (mobile ? (big ? .74 : .46) : .8);       // altura que a prévia ocupa na tela (px CSS)
  return Math.min(1, Math.max(0.2, altura * Math.min(devicePixelRatio || 1, 2) / OUT.h));
}
function draw(full = false){
  const f = full ? (typeof full === 'number' ? full : 1) : previewFactor();   // full pode ser um número (2 = dobro do tamanho)
  const saved = { ...state };                                            // guarda os valores reais
  SCALED.forEach(k => state[k] = Math.round(saved[k]*f));                // versão reduzida só para este desenho
  const tw = Math.round(OUT.w*f), th = Math.round(OUT.h*f);
  if (cv.width !== tw) cv.width = tw;                                    // redimensionar o canvas é caro: só se mudou
  if (cv.height !== th) cv.height = th;
  try { render(); } finally { Object.assign(state, saved); }     // devolve os valores reais
}
function render(){
  const W = cv.width, H = cv.height;
  ctx.clearRect(0,0,W,H);
  const key = [W, H, state.zoom, state.panX, state.panY, state.wpBlur, wallId].join('|');   // "assinatura" do fundo
  if (key !== baseKey){ baseCv.width = W; baseCv.height = H; drawCover(wall, false, baseCv.getContext('2d')); baseKey = key; }
  ctx.drawImage(baseCv, 0, 0);
  const gkey = key + '|' + state.blur;               // o vidro só é recalculado se o fundo ou o desfoque mudarem
  if (gkey !== glassKey){ buildGlass(); glassKey = gkey; }               // 1) papel de parede
  drawDock();                                    // 2) dock de vidro
  // Aviso de qualidade: escala > 1 significa que a imagem original é menor que o quadro e foi ampliada
  const sc = Math.max(OUT.w/sizeOf(wall)[0], OUT.h/sizeOf(wall)[1]) * state.zoom/100;
  document.getElementById('info').textContent = OUT.w + ' × ' + OUT.h + ' px (prévia a ' + Math.round(W/OUT.w*100) + '%, o PNG sai em 100%) · ' +
    (sc > 1.001 ? 'papel de parede ampliado ' + sc.toFixed(2) + '×: pode perder nitidez' : 'sem ampliação, nitidez total');
  saveSoon();
}

/* =====================================================================
   2b. REFRAÇÃO FÍSICA (método do artigo https://kube.io/blog/liquid-glass-css-svg/)
   Em vez de "empurrar" pixels à vontade, simulamos 128 raios de luz que
   atravessam o vidro, do ponto da borda até o fim do bisel, e guardamos
   quanto cada raio se desvia. Depois só consultamos essa tabela.
   ===================================================================== */
// Altura do vidro (0 a 1) em função da distância à borda x (0 = borda, 1 = fim do bisel)
const squircle = x => Math.pow(1 - Math.pow(1-x,4), 1/4);      // curva suave que a Apple usa
const PROFILES = {
  squircle,
  circle:  x => Math.sqrt(1 - (1-x)*(1-x)),                      // cúpula esférica (transição mais dura)
  concave: x => 1 - squircle(x),                                 // tigela: empurra a luz para fora
  lip:     x => { const t = x*x*x*(x*(x*6-15)+10);               // smootherstep: mistura convexo e côncavo
                  return squircle(x)*(1-t) + (1-squircle(x))*t; }
};

function buildTable(profile, bevel, thick, ior){
  const f = PROFILES[profile] || squircle;
  const eta = 1/ior;                                  // razão n1/n2 (ar → vidro)
  const table = [];
  for (let i=0; i<128; i++){
    const x = i/127;
    const a = Math.max(0,x-0.001), b = Math.min(1,x+0.001);
    // inclinação da superfície (derivada numérica), convertida de unidades normalizadas para px
    const slope = (f(b)-f(a))/(b-a) * thick/bevel;
    const len = Math.hypot(slope,1);
    const nx = -slope/len, nz = 1/len;               // normal da superfície (vetor unitário)
    const cosi = nz;                                  // raio incidente vertical I=(0,-1): cos = -(N·I)
    const k = 1 - eta*eta*(1-cosi*cosi);
    const c = eta*cosi - Math.sqrt(Math.max(0,k));   // lei de Snell em forma vetorial
    const tx = c*nx, tz = -eta + c*nz;               // direção do raio refratado
    const z = thick*f(x);                             // altura do vidro neste ponto
    // desvio lateral = quanto o raio anda de lado até chegar ao fundo (altura z)
    table.push(tz < -1e-6 ? tx*z/(-tz) : 0);
  }
  return table;                                       // positivo = o fundo visto vem de mais para dentro
}

/* Caminho de um retângulo arredondado (usado só para a sombra) */
function roundRectPath(x,y,w,h,r){
  ctx.beginPath(); ctx.roundRect(x,y,w,h,r);
}

function drawDock(){
  const w = state.w, h = state.h;
  const r = Math.min(state.r, w/2, h/2);        // o raio nunca passa de metade do lado menor
  const left = Math.round(cv.width/2 + state.x - w/2);  // x = 0 significa "centrada"
  const top = state.y;

  // --- Sombra suave da dock: a forma é desenhada FORA da tela e só a sombra (deslocada de volta) aparece,
  //     então nenhum pixel preto toca a borda do vidro ---
  ctx.save();
  const OFF = cv.width + w + 400;
  ctx.shadowColor = 'rgba(0,0,0,.35)'; ctx.shadowBlur = h*0.25; ctx.shadowOffsetX = OFF; ctx.shadowOffsetY = h*0.08;
  ctx.fillStyle = '#000';
  roundRectPath(left - OFF, top, w, h, r);
  ctx.fill();
  ctx.restore();

  // --- Região de trabalho = dock + margem (para o desfoque e a refração) ---
  const bevel = Math.min(state.bevel, h/2);                          // bisel não passa de metade da altura
  const table = buildTable(state.profile, bevel, state.thick, state.ior); // tabela de refração
  const maxD = Math.max(...table.map(Math.abs)) * state.scale/100;   // maior desvio possível (px)
  const M = Math.ceil(state.blur*2 + maxD) + 4;
  const bx = left - M, by = top - M, bw = w + 2*M, bh = h + 2*M;

  // --- Passo A: o fundo desfocado já está pronto em GD (cache); não se desfoca a cada quadro ---
  
  
   
     
     

  // --- Passo B: calcular cada pixel do vidro ---
  const out = new ImageData(bw,bh), od = out.data;      // imagem de saída (vazia)
  const cx = bw/2, cy = bh/2;                           // centro da dock na região
  const hx = w/2 - r, hy = h/2 - r;                     // "miolo" reto do retângulo
  const bev = Math.min(state.bevel, h/2);               // bisel não pode passar da metade da altura
  const shine = state.shine/100;
  const ang = state.angle*Math.PI/180, LX = Math.cos(ang), LY = Math.sin(ang); // direção da luz
  const rimW = Math.max(1.5, 2.5*cv.width/1179);        // espessura do fio de luz, proporcional à imagem

  for (let py=0; py<bh; py++){
    for (let px=0; px<bw; px++){
      // Distância assinada à borda (SDF): negativa = dentro, positiva = fora
      const dx = px+0.5-cx, dy = py+0.5-cy;
      const qx = Math.abs(dx)-hx, qy = Math.abs(dy)-hy;
      const ox = Math.max(qx,0), oy = Math.max(qy,0);
      const len = Math.sqrt(ox*ox + oy*oy);
      const dist = len + Math.min(Math.max(qx,qy),0) - r;
      if (dist > 0.5) continue;                         // fora da dock: não pinta

      // Normal externa: direção para onde a borda "aponta" neste pixel
      let nx, ny;
      if (len > 0){ nx = ox/len*Math.sign(dx); ny = oy/len*Math.sign(dy); } // cantos
      else if (qx > qy){ nx = Math.sign(dx); ny = 0; }                      // laterais
      else { nx = 0; ny = Math.sign(dy); }                                  // topo/base

      const depth = Math.max(0, -dist);   // nunca negativo: pixels até meio pixel fora da forma gravavam NaN (preto) na tabela de refração e formavam os pontos escuros nos cantos
      //                              // distância da borda para dentro
      const idx = Math.min(127, Math.floor(depth/bevel*127)); // posição na tabela (0 = borda, 127 = fim do bisel)
      const disp = depth < bevel ? table[idx]*state.scale/100 : 0;                     // desvio em px (0 no miolo plano do vidro)

      // Refração com aberração cromática: R, G e B amostram posições levemente diferentes
      const i = (py*bw+px)*4;
      const col = COL;
      for (let c=0; c<3; c++){
        const k = disp*(1 + (c-1)*state.chroma/100);                // R desvia menos, B mais (aberração cromática)
        const sx = Math.min(GW-1, Math.max(0, bx + Math.round(px - nx*k)));   // bx/by convertem para coordenadas do canvas inteiro
        const sy = Math.min(cv.height-1, Math.max(0, by + Math.round(py - ny*k)));
        col[c] = GD[(sy*GW+sx)*4 + c];
      }

      // Película branca translúcida, mais forte no topo (como vidro fosco)
      const tint = 0.08 + 0.10*(1 - Math.min(1,Math.max(0,(py-M)/h)));

      // Brilho especular: luz vinda do canto superior esquerdo
      const dot = nx*LX + ny*LY;    // 1 = borda virada para a luz, -1 = lado oposto
      const rim = depth < rimW ? 1 - depth/rimW : 0;      // fio de luz fino colado na borda
      const lum = 0.3*col[0] + 0.59*col[1] + 0.11*col[2];            // luminosidade do pixel (base para saturar o reflexo)
      const hl = rim * (0.2 + 0.8*Math.abs(dot)) * (dot > 0 ? 1 : 0.6) * shine;

      for (let c=0; c<3; c++){
        let v = col[c]*(1-tint) + 255*tint;             // mistura com branco
        const sat = Math.min(255, Math.max(0, lum + (col[c]-lum)*2.5)); // cor do pixel com saturação reforçada
        v = v*(1-hl) + (127.5 + 0.5*sat)*hl;                    // mistura o reflexo (branco + cor saturada)
        od[i+c] = v;
      }
      od[i+3] = Math.min(1, Math.max(0, 0.5 - dist))*255; // alfa: suaviza a serrilha da borda
    }
  }

  // --- Passo C: colocar o resultado no canvas principal ---
  const tmp = document.createElement('canvas'); tmp.width = bw; tmp.height = bh;
  tmp.getContext('2d').putImageData(out,0,0);           // putImageData ignora transparência, por isso o canvas temporário
  ctx.drawImage(tmp, bx, by);                           // drawImage respeita o alfa
}

/* Agenda 1 redesenho por quadro: evita travar quando arrastas um slider */
let queued = false;
function schedule(){
  if (queued) return; queued = true;
  requestAnimationFrame(()=>{ queued = false; draw(); });
}

/* =====================================================================
   4. INTERFACE: cria os controlos e liga os eventos
   ===================================================================== */
document.getElementById('steppers').innerHTML = STEPPERS.map(([k,l]) => `
  <div class="row" style="margin-bottom:8px"><label for="n-${k}">${l}</label>
    <div class="step">
      <button data-k="${k}" data-d="-1" aria-label="${l} menos 1">−1</button>
      <input type="number" id="n-${k}" data-k="${k}">
      <button data-k="${k}" data-d="1" aria-label="${l} mais 1">+1</button>
    </div></div>`).join('');

document.getElementById('sliders').innerHTML = SLIDERS.map(([k,l,mn,mx,st=1]) => `
  <div style="margin-bottom:6px"><label for="s-${k}">${l}</label>
  <input type="range" id="s-${k}" data-k="${k}" min="${mn}" max="${mx}" step="${st}"></div>`).join('');

/* Sliders de enquadramento (mesma lógica dos outros: data-k liga ao estado) */
const FIT = [['wpBlur','Desfoque do papel de parede',0,60],['zoom','Zoom (corta mais)',100,400],['panX','Mover na horizontal',-100,100],['panY','Mover na vertical',-100,100]];
document.getElementById('fitSliders').innerHTML = FIT.map(([k,l,mn,mx]) => `
  <div style="margin-bottom:6px"><label for="s-${k}">${l}</label>
  <input type="range" id="s-${k}" data-k="${k}" min="${mn}" max="${mx}"></div>`).join('');

/* Mostra o valor atual ao lado do rótulo de cada slider */
function showVal(el){
  if (el.type !== 'range') return;
  const lab = el.previousElementSibling;               // o <label> logo acima do slider
  let o = lab.querySelector('output');
  if (!o){ o = document.createElement('output'); lab.append(o); lab.classList.add('lv'); }
  o.textContent = el.value;
}

/* Copia o estado para os campos (usado depois de carregar imagem) */
function syncInputs(){
  document.querySelectorAll('#panel input[data-k], #panel select[data-k]').forEach(el => { el.value = state[el.dataset.k]; showVal(el); });
}

const panel = document.getElementById('panel');
// Clique nos botões −1 / +1: soma ao valor e redesenha
panel.addEventListener('click', e => {
  const b = e.target.closest('button[data-d]'); if (!b || e.detail !== 0) return; // toque/clique do mouse é tratado no pointerdown (repete ao segurar); aqui só o teclado
  state[b.dataset.k] += Number(b.dataset.d);
  syncInputs(); schedule();
});
// Digitar num campo ou mexer num slider: atualiza o estado
panel.addEventListener('input', e => {
  const el = e.target; if (!el.dataset.k) return;
  state[el.dataset.k] = el.tagName === 'SELECT' ? el.value : Number(el.value); // select devolve texto
  showVal(el);
  schedule();
});

/* Carregar imagens: createImageBitmap respeita a rotação das fotos de telemóvel */
document.getElementById('fWall').addEventListener('change', async e => {
  if (e.target.files[0]) { setWall(await createImageBitmap(e.target.files[0])); document.getElementById('nWall').textContent = e.target.files[0].name; setThumb('tWall', e.target.files[0]); }
});

/* Exportar: gera o PNG em tamanho real, baixa (ou abre o Compartilhar no APK) e volta ao modo de edição */
function baixarPng(){
  draw(true);   // exporta no tamanho real, sem a redução da prévia
  cv.toBlob(blob => {
    const P = window.Capacitor && Capacitor.Plugins;
    if (P && P.Filesystem && P.Share && Capacitor.isNativePlatform()){   // dentro do APK: downloads por blob não funcionam, então abre o menu Compartilhar
      const r = new FileReader();
      r.onload = async () => {
        try {
          const f = await P.Filesystem.writeFile({ path: 'wallpaper-liquid-glass.png', data: r.result.split(',')[1], directory: 'CACHE' });
          await P.Share.share({ files: [f.uri], dialogTitle: 'Salvar wallpaper' });   // escolha Fotos / Galeria / Arquivos
        } catch (err) { console.error(err); }
        draw();
      };
      r.readAsDataURL(blob); return;
    }
    const a = document.createElement('a');
    a.href = URL.createObjectURL(blob);
    a.download = 'wallpaper-liquid-glass.png';
    a.click();
    setTimeout(() => URL.revokeObjectURL(a.href), 1000);
    draw();                                       // restaura a pré-visualização
  }, 'image/png');
}

/* Mudou o modo ou o tamanho personalizado: recalcula o quadro e redesenha */
['outMode','outW','outH'].forEach(id => document.getElementById(id).addEventListener('change', () => { applySize(); syncInputs(); draw(); }));

/* Segurar os botões −1 / +1 repete o ajuste (espera 350 ms, depois repete a cada 60 ms) */
let hold;
panel.addEventListener('pointerdown', e => {
  const b = e.target.closest('button[data-d]'); if (!b) return;
  const step = () => { state[b.dataset.k] += Number(b.dataset.d); syncInputs(); schedule(); };
  step();                                                     // primeiro ajuste imediato
  hold = setTimeout(function rep(){ step(); hold = setTimeout(rep, 60); }, 350);
});
['pointerup','pointercancel','pointerleave'].forEach(t => panel.addEventListener(t, () => clearTimeout(hold)));

/* Arrastar a dock direto na imagem (mouse ou dedo) */
let drag = null;
const toCanvas = e => {                                        // converte a posição da tela para pixels do canvas
  const r = cv.getBoundingClientRect();
  const s = Math.min(r.width/OUT.w, r.height/OUT.h);     // escala do object-fit: contain (sobram faixas nas laterais)
  return [(e.clientX - r.left - (r.width - OUT.w*s)/2)/s, (e.clientY - r.top - (r.height - OUT.h*s)/2)/s];   // sempre em pixels do tamanho real
};
cv.addEventListener('pointerdown', e => {
  const [px, py] = toCanvas(e), left = OUT.w/2 + state.x - state.w/2;
  if (px < left || px > left + state.w || py < state.y || py > state.y + state.h) return; // só se clicou na dock
  drag = { px, py, x: state.x, y: state.y };
  cv.setPointerCapture(e.pointerId); cv.style.cursor = 'grabbing';
});
cv.addEventListener('pointermove', e => {
  if (!drag) return;
  const [px, py] = toCanvas(e);
  state.x = Math.round(drag.x + px - drag.px);                // move pelo mesmo tanto que o ponteiro andou
  state.y = Math.round(drag.y + py - drag.py);
  document.getElementById('n-x').value = state.x; document.getElementById('n-y').value = state.y;   // atualiza só X e Y (mais leve que syncInputs)
  schedule();
});
['pointerup','pointercancel'].forEach(t => cv.addEventListener(t, () => { drag = null; cv.style.cursor = ''; }));


/* ---------- Configurações guardadas no aparelho (localStorage) ---------- */
const DEFAULTS = { ...state };                       // cópia dos valores de fábrica (usada em "Repor")
let needReset = true;                                // true = ainda falta calcular a dock inicial
let saveTimer;
function saveSoon(){                                 // grava 300 ms depois da última mudança
  clearTimeout(saveTimer);
  saveTimer = setTimeout(() => {
    try { localStorage.setItem('dockSettings', JSON.stringify({ state, W: OUT.w, H: OUT.h, mode: document.getElementById('outMode').value })); } catch (e) {}
  }, 300);
}
try {                                                // restaura a sessão anterior, se existir
  const s = JSON.parse(localStorage.getItem('dockSettings') || 'null');
  if (s && s.state){
    Object.assign(state, s.state);
    OUT.w = s.W; OUT.h = s.H;                 // tamanho em que a dock foi ajustada (applySize reescala se mudar)
    document.getElementById('outMode').value = s.mode || 'auto';
    document.getElementById('outW').value = s.W; document.getElementById('outH').value = s.H;
    needReset = false;                               // mantém a dock da sessão anterior
  }
} catch (e) {}
document.getElementById('btnReset').addEventListener('click', () => {
  try { localStorage.removeItem('dockSettings'); } catch (e) {}
  Object.assign(state, DEFAULTS);                    // volta aos valores de fábrica
  document.getElementById('outMode').value = (typeof DO !== 'undefined' && DO) ? 'device' : 'auto';   // no APK o padrão é a tela do aparelho
  applySize(); resetDock(); syncInputs(); draw();
});

/* Botão "Ampliar prévia" (celular): alterna entre prévia média e grande. Só muda o tamanho na tela; o PNG exportado não muda. */
const stageEl = document.querySelector('.stage'), bigBtn = document.getElementById('btnBig');
bigBtn.addEventListener('click', () => {
  const big = stageEl.classList.toggle('big');
  panel.classList.toggle('hide', big);           // ampliado: esconde abas e seções, fica só a barra de ações
  bigBtn.textContent = big ? 'Reduzir prévia' : 'Ampliar prévia';
  schedule();                                   // redesenha na nova resolução de prévia
});

/* Compatibilidade: WebViews Android antigos não têm ctx.roundRect; este substituto desenha o mesmo caminho com arcos */
if (!CanvasRenderingContext2D.prototype.roundRect){
  CanvasRenderingContext2D.prototype.roundRect = function(x, y, w, h, r){
    r = Math.min(r, w/2, h/2);
    this.moveTo(x+r, y); this.arcTo(x+w, y, x+w, y+h, r); this.arcTo(x+w, y+h, x, y+h, r);
    this.arcTo(x, y+h, x, y, r); this.arcTo(x, y, x+w, y, r); this.closePath();
  };
}

/* ---------- Botão "Alinhar sobre a tela inicial" (só aparece no APK) ---------- */
const liveBtn = document.getElementById('btnLive');
if (window.Capacitor && Capacitor.isNativePlatform && Capacitor.isNativePlatform()) liveBtn.style.display = 'block';

/* ---------- Alinhar sobre a tela inicial REAL (sobreposição nativa do Android) ----------
   O plugin DockOverlay (Java, instalado pelo patch-android.js) mostra o contorno da dock e uma barra de
   ajuste POR CIMA da tela inicial verdadeira. Ao tocar em "Concluir", os valores voltam para cá. */
const DO = window.Capacitor && Capacitor.Plugins && Capacitor.Plugins.DockOverlay;
async function alinhar(){
  const { granted } = await DO.hasPermission();               // precisa de "Exibir sobre outros apps"
  if (!granted){
    await DO.requestPermission();
    alert('Ative "Exibir sobre outros apps" para o Dock Líquida, volte ao app e toque em Alinhar de novo.');
    return;
  }
  document.getElementById('outMode').value = 'device';          // alinha na resolução física do aparelho (a mesma da tela inicial real)
  applySize(); syncInputs(); schedule();
  await DO.start({ x: state.x, y: state.y, w: state.w, h: state.h, r: state.r, outW: OUT.w, outH: OUT.h });   // abre a sobreposição e vai para a tela inicial
}
function aplicarResultado(json){                              // valores ajustados na sobreposição voltam para o app
  const v = typeof json === 'string' ? JSON.parse(json) : json;
  ['x','y','w','h','r'].forEach(k => { if (typeof v[k] === 'number') state[k] = v[k]; });
  syncInputs(); schedule();
}
async function pegarResultado(){ const r = await DO.getResult(); if (r.result) aplicarResultado(r.result); }
if (DO){
  liveBtn.addEventListener('click', alinhar);
  document.addEventListener('visibilitychange', () => { if (!document.hidden) pegarResultado(); });   // ao voltar para o app
  DO.addListener('done', d => aplicarResultado(d));
}

/* ---------- Aplicar direto como papel de parede (sem baixar nem salvar) ----------
   Gera o PNG em tamanho real e entrega ao Android (WallpaperManager), no plugin DockOverlay. */
async function aplicarWallpaper(){
  if (!DO) return;
  const alvo = await escolherAlvo();                        // pergunta onde aplicar: tela inicial, bloqueio ou ambas
  if (!alvo) return;
  try {
    draw(true);                                             // tamanho real
    const png = cv.toDataURL('image/png');
    draw();                                                 // volta para a prévia leve
    await DO.setWallpaper({ data: png.split(',')[1], target: alvo });
    alert('Papel de parede aplicado!');
  } catch (err) {
    draw();
    alert('Não foi possível aplicar: ' + (err && err.message ? err.message : err));
  }
}

/* ---------- v6: interface (miniaturas, mockup, handle e botão dividido) ---------- */
function setThumb(id, file){                                  // mostra a miniatura da imagem escolhida
  const t = document.getElementById(id);
  if (t._u) URL.revokeObjectURL(t._u);
  t._u = URL.createObjectURL(file);
  t.style.backgroundImage = 'url(' + t._u + ')'; t.classList.add('has');
}

/* Ajusta o tamanho da caixa da prévia para manter a proporção do wallpaper (sem moldura) */
const phone = document.getElementById('phone'), pwrap = document.getElementById('pwrap');
function fitPhone(){
  if (!OUT.w) return;
  const bez = 0, ar = OUT.w / OUT.h, aw = pwrap.clientWidth, ah = pwrap.clientHeight;
  let h = ah, w = (h - bez) * ar + bez;
  if (w > aw){ w = aw; h = (w - bez) / ar + bez; }
  w = Math.floor(w) + 'px'; h = Math.floor(h) + 'px';
  if (phone.style.width !== w) phone.style.width = w;         // só escreve se mudou (evita recalcular o layout à toa)
  if (phone.style.height !== h) phone.style.height = h;
}
const _draw = draw;                                           // a lógica do canvas fica intacta: só ajustamos o mockup antes de desenhar
draw = function (...a){ fitPhone(); return _draw(...a); };
new ResizeObserver(() => { fitPhone(); schedule(); }).observe(pwrap);

/* Entrega um arquivo: no APK abre o menu Compartilhar; no navegador baixa */
function entregar(blob, nome){
  const P = window.Capacitor && Capacitor.Plugins;
  if (P && P.Filesystem && P.Share && Capacitor.isNativePlatform()){
    return new Promise((ok, falha) => {
      const r = new FileReader();
      r.onload = async () => {
        try {
          const f = await P.Filesystem.writeFile({ path: nome, data: r.result.split(',')[1], directory: 'CACHE' });
          await P.Share.share({ files: [f.uri], dialogTitle: 'Salvar' }); ok();
        } catch (e) { falha(e); }
      };
      r.readAsDataURL(blob);
    });
  }
  const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = nome; a.click();
  setTimeout(() => URL.revokeObjectURL(a.href), 1000);
  return Promise.resolve();
}
async function baixarAltaRes(){                              // 2× o tamanho (limitado a 4096 px no lado maior)
  const f = Math.min(2, 4096 / Math.max(OUT.w, OUT.h));
  try {
    draw(f);
    const blob = await new Promise(res => cv.toBlob(res, 'image/png'));
    draw();
    await entregar(blob, 'wallpaper-liquid-glass-2x.png');
  } catch (e) { draw(); alert('Não foi possível gerar em alta resolução: ' + (e.message || e)); }
}
function salvarProjeto(){                                      // guarda todos os ajustes num arquivo .json
  const proj = { app: 'Dock Líquida', versao: 1, saida: { w: OUT.w, h: OUT.h }, ajustes: state };
  return entregar(new Blob([JSON.stringify(proj, null, 2)], { type: 'application/json' }), 'projeto-dock-liquida.json');
}

/* Botão dividido: o principal baixa o PNG; a seta abre as outras ações */
const arrow = document.getElementById('splitArrow'), menu = document.getElementById('splitMenu');
const fecharMenu = () => { menu.hidden = true; arrow.setAttribute('aria-expanded', 'false'); };
document.getElementById('splitMain').addEventListener('click', () => { fecharMenu(); baixarPng(); });
/* O menu abre para cima, ancorado na seta. Ele é "fixed" (relativo à tela) porque o painel rola por dentro
   e cortaria tudo o que passasse do topo dele. Se não couber, o próprio menu rola. */
function posicionarMenu(){
  const r = arrow.getBoundingClientRect();
  Object.assign(menu.style, {
    right: Math.max(8, innerWidth - r.right) + 'px',      // alinhado à direita da seta
    bottom: (innerHeight - r.top + 8) + 'px',             // logo acima do botão
    maxHeight: Math.max(140, r.top - 16) + 'px', overflowY: 'auto'
  });
}
arrow.addEventListener('click', e => {
  e.stopPropagation(); menu.hidden = !menu.hidden;
  arrow.setAttribute('aria-expanded', String(!menu.hidden));
  if (!menu.hidden) posicionarMenu();
});
addEventListener('resize', () => { if (!menu.hidden) posicionarMenu(); });
document.addEventListener('click', e => { if (!document.getElementById('split').contains(e.target)) fecharMenu(); });
menu.addEventListener('click', e => {
  const b = e.target.closest('button[data-act]'); if (!b) return;
  fecharMenu();
  ({ apply: aplicarWallpaper, hires: baixarAltaRes, project: salvarProjeto, open: () => document.getElementById('fProj').click() })[b.dataset.act]();
});
if (DO) document.getElementById('actApply').hidden = false;   // "Aplicar" só existe no APK

/* Abrir um projeto salvo (.json): restaura todos os ajustes e o tamanho de saída */
document.getElementById('fProj').addEventListener('change', async e => {
  const f = e.target.files[0]; e.target.value = '';
  if (!f) return;
  try {
    const p = JSON.parse(await f.text());
    Object.assign(state, p.ajustes);
    OUT.w = p.saida.w; OUT.h = p.saida.h;                       // assume o tamanho do projeto antes de recalcular
    document.getElementById('outMode').value = 'custom';
    document.getElementById('outW').value = OUT.w; document.getElementById('outH').value = OUT.h;
    applySize(); syncInputs(); schedule();
  } catch (err) { alert('Arquivo de projeto inválido.'); }
});

/* ---------- v8: abas Base | Dock, menu ☰ e cartões (inspirado no DockWalls) ---------- */
const dtabs = document.getElementById('dtabs');
function setTab(t){                                            // Base = Imagens + Papel de parede; Dock = Posição + Vidro
  panel.dataset.tab = t;                                       // o CSS mostra só os cartões da aba ativa
  [...dtabs.children].forEach(b => b.classList.toggle('on', b.dataset.t === t));
  panel.scrollTop = 0;
}
dtabs.addEventListener('click', e => { const b = e.target.closest('button'); if (b) setTab(b.dataset.t); });
setTab('base');


/* ---------- v9: cores dinâmicas (Material 3 / Material You) ----------
   No APK (Android 12+) usa a paleta real do sistema. No navegador, gera a paleta a partir do wallpaper carregado.
   Segue o tema claro/escuro do sistema. */
const SHADES = [[0,100],[10,99],[50,95],[100,90],[200,80],[300,70],[400,60],[500,50],[600,40],[700,30],[800,20],[900,10],[1000,0]];   // [número do Android, tom Material]
let SYS = null;                                    // paleta do sistema (a1, a2, n1, n2), se existir
let SEED = { h: 195, c: 0.08 };                    // matiz e croma de partida (verde-petróleo)

/* Conversões de cor em OKLCH (espaço perceptual, parecido com o HCT do Material) */
const toneL = t => Math.cbrt(t > 8 ? Math.pow((t + 16) / 116, 3) : t / 903.3);     // tom Material (0 a 100) -> luminosidade OKLab
const lin = v => { v /= 255; return v <= .04045 ? v / 12.92 : Math.pow((v + .055) / 1.055, 2.4); };
function toOklch(r, g, b){
  r = lin(r); g = lin(g); b = lin(b);
  const l = Math.cbrt(.4122214708*r + .5363325363*g + .0514459929*b), m = Math.cbrt(.2119034982*r + .6806995451*g + .1073969566*b), s = Math.cbrt(.0883024619*r + .2817188376*g + .6299787005*b);
  const L = .2104542553*l + .793617785*m - .0040720468*s, a = 1.9779984951*l - 2.428592205*m + .4505937099*s, bb = .0259040371*l + .7827717662*m - .808675766*s;
  return [L, Math.hypot(a, bb), (Math.atan2(bb, a) * 180 / Math.PI + 360) % 360];
}
function oklchHex(L, C, H){
  const r = H * Math.PI / 180;
  for (let c = C; c >= 0; c -= 0.005){             // reduz o croma até a cor caber no sRGB
    const a = c * Math.cos(r), b = c * Math.sin(r);
    const l = (L + .3963377774*a + .2158037573*b) ** 3, m = (L - .1055613458*a - .0638541728*b) ** 3, s = (L - .0894841775*a - 1.291485548*b) ** 3;
    const rgb = [4.0767416621*l - 3.3077115913*m + .2309699292*s, -1.2684380046*l + 2.6097574011*m - .3413193965*s, -.0041960863*l - .7034186147*m + 1.707614701*s];
    if (rgb.every(v => v >= -.001 && v <= 1.001) || c < .005)
      return '#' + rgb.map(v => { v = Math.min(1, Math.max(0, v)); v = v <= .0031308 ? 12.92*v : 1.055*Math.pow(v, 1/2.4) - .055; return Math.round(v * 255).toString(16).padStart(2, '0'); }).join('');
  }
}

/* Cor de uma paleta (a1 = primária, a2 = secundária, n1/n2 = neutras) em um tom Material */
function tone(p, t){
  if (SYS && SYS[p] && !(typeof PREF !== 'undefined' && PREF && PREF.cores === 'wallpaper')){                              // paleta do sistema: pega o tom mais próximo disponível
    let best = SHADES[0];
    for (const s of SHADES) if (Math.abs(s[1] - t) < Math.abs(best[1] - t)) best = s;
    return SYS[p][best[0]];
  }
  const C = { a1: Math.min(.16, Math.max(.08, SEED.c)), a2: .04, n1: .008, n2: .016 }[p];
  return oklchHex(toneL(t), C, SEED.h);
}

/* Papéis de cor do Material 3 ligados às variáveis do app */
function applyTheme(){
  const dark = (typeof PREF !== 'undefined' && PREF && PREF.tema !== 'auto') ? PREF.tema === 'escuro' : matchMedia('(prefers-color-scheme: dark)').matches, T = tone;
  const v = dark
    ? { '--bg': T('n1',6),  '--panel': T('n1',12), '--card': T('n1',17), '--line': T('n2',30), '--text': T('n1',90), '--mute': T('n2',80), '--acc': T('a1',80), '--acc-fg': T('a1',20),  '--sc': T('a2',30), '--stage': T('n1',4) }
    : { '--bg': T('n1',96), '--panel': T('n1',94), '--card': T('n1',98), '--line': T('n2',80), '--text': T('n1',10), '--mute': T('n2',30), '--acc': T('a1',40), '--acc-fg': T('a1',100), '--sc': T('a2',90), '--stage': T('n1',10) };
  for (const k in v) document.body.style.setProperty(k, v[k]);
  document.body.style.colorScheme = dark ? 'dark' : 'light';
}
matchMedia('(prefers-color-scheme: dark)').addEventListener('change', applyTheme);

/* Cor-semente: o matiz mais presente e colorido do wallpaper (ignora quase-preto, quase-branco e cinzas) */
function seedFrom(img){
  const c = document.createElement('canvas'); c.width = c.height = 48;
  const g = c.getContext('2d', { willReadFrequently: true }); g.drawImage(img, 0, 0, 48, 48);
  const d = g.getImageData(0, 0, 48, 48).data, bins = Array.from({ length: 36 }, () => ({ w: 0, c: 0, x: 0, y: 0 }));
  for (let i = 0; i < d.length; i += 4){
    const [L, C, H] = toOklch(d[i], d[i+1], d[i+2]);
    if (L < .2 || L > .95) continue;
    const w = C * C, b = bins[Math.floor(H / 10) % 36], r = H * Math.PI / 180;   // quanto mais colorido, maior o peso
    b.w += w; b.c += C * w; b.x += Math.cos(r) * w; b.y += Math.sin(r) * w;
  }
  const top = bins.reduce((a, b) => (b.w > a.w ? b : a));
  SEED = top.w < .02 ? { h: SEED.h, c: .03 }                                      // imagem quase cinza: tema discreto
                     : { h: (Math.atan2(top.y, top.x) * 180 / Math.PI + 360) % 360, c: top.c / top.w };
}
const _setWall = setWall;                          // novo wallpaper escolhido -> novo tema (o degradê padrão não conta)
setWall = function (img){
  _setWall(img);
  if (typeof ImageBitmap !== 'undefined' && img instanceof ImageBitmap){ try { seedFrom(img); applyTheme(); } catch (e) {} }
};
if (DO && DO.getSystemColors) DO.getSystemColors().then(c => { if (c && c.a1 && c.n1){ SYS = c; applyTheme(); } }).catch(() => {});   // APK: paleta real do sistema
applyTheme();

/* ---------- v10: áreas seguras reais (barras do sistema) ---------- */
async function sincronizarInsets(){                // pede ao Android quanto do WebView fica sob as barras de status/navegação
  if (!DO || !DO.getInsets) return;
  try {
    const i = await DO.getInsets(), s = document.documentElement.style;
    s.setProperty('--sat', (i.top || 0) + 'px'); s.setProperty('--sab', (i.bottom || 0) + 'px');
  } catch (e) {}
}
sincronizarInsets(); addEventListener('resize', sincronizarInsets); setTimeout(sincronizarInsets, 600);

/* ---------- v11: telas, galeria com histórico e preferências ----------
   As imagens ficam no IndexedDB do app (armazenamento privado, sobrevive a reinícios), com miniatura gerada já reduzida.
   A navegação usa a pilha de histórico do WebView, então o botão Voltar do Android funciona entre as telas. */
const $ = id => document.getElementById(id);
const APP_VERSION = '1.1.0';
const esc = s => String(s).replace(/[&<>"']/g, c => ({ '&':'&amp;', '<':'&lt;', '>':'&gt;', '"':'&quot;', "'":'&#39;' }[c]));

/* Preferências guardadas no aparelho */
const PREF_PADRAO = { tema: 'auto', cores: 'sistema', editorInicio: false, qualidade: 1, trocaAtiva: false, trocaHoras: 24, trocaAlvo: 'home', trocaGerado: null, dockIndividual: true };
var PREF = { ...PREF_PADRAO };                      // var: applyTheme() pode ser chamada antes desta linha
try { Object.assign(PREF, JSON.parse(localStorage.getItem('dockPrefs') || '{}')); } catch (e) {}
const salvarPrefs = () => { try { localStorage.setItem('dockPrefs', JSON.stringify(PREF)); } catch (e) {} };
const _pf = previewFactor;                          // "Qualidade da prévia" multiplica a resolução do desenho
previewFactor = function () { return Math.min(1, Math.max(.15, _pf() * PREF.qualidade)); };

/* Aviso rápido */
let toastT;
function toast(msg){ const t = $('toast'); t.textContent = msg; t.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(() => t.classList.remove('on'), 2600); }

/* ----- Banco de imagens (IndexedDB) ----- */
const DB = (() => {
  let dbp;
  const abrir = () => dbp || (dbp = new Promise((ok, erro) => {
    const r = indexedDB.open('dockliquida', 1);
    r.onupgradeneeded = () => r.result.createObjectStore('wallpapers', { keyPath: 'id' });
    r.onsuccess = () => ok(r.result); r.onerror = () => erro(r.error);
  }));
  const tx = async (modo, fn) => {
    const db = await abrir();
    return new Promise((ok, erro) => {
      const t = db.transaction('wallpapers', modo), req = fn(t.objectStore('wallpapers'));
      t.oncomplete = () => ok(req && req.result); t.onerror = t.onabort = () => erro(t.error);
    });
  };
  return { all: () => tx('readonly', s => s.getAll()), get: id => tx('readonly', s => s.get(id)),
           put: rec => tx('readwrite', s => s.put(rec)), del: id => tx('readwrite', s => s.delete(id)), clear: () => tx('readwrite', s => s.clear()) };
})();

const LIMITE = 20;                                  // máximo de imagens no histórico
let gal = [], curId = null, hasWall = false, FR = {};   // gal = histórico (mais recente primeiro); FR = enquadramento de cada wallpaper
try { FR = JSON.parse(localStorage.getItem('dockFrames') || '{}'); } catch (e) {}
const salvarFR = () => { try { localStorage.setItem('dockFrames', JSON.stringify(FR)); } catch (e) {} };

/* Dock individual: cada wallpaper guarda a PRÓPRIA Dock (posição, tamanho e vidro), como o enquadramento.
   Os valores ficam como fração do tamanho da imagem de saída, então continuam certos se esse tamanho mudar. */
let DK = {};                                                       // DK[id] = Dock guardada de cada wallpaper
try { DK = JSON.parse(localStorage.getItem('dockDocks') || '{}'); } catch (e) {}
const salvarDK = () => { try { localStorage.setItem('dockDocks', JSON.stringify(DK)); } catch (e) {} };
const DOCK_LARG = ['x', 'w', 'r', 'bevel', 'thick', 'blur', 'wpBlur'], DOCK_ALT = ['y', 'h'], DOCK_PURO = ['ior', 'scale', 'chroma', 'shine', 'angle', 'profile'];
const CAMPOS_DOCK = [...DOCK_LARG, ...DOCK_ALT, ...DOCK_PURO];
const dockAtual = () => {                                         // Dock aberta agora -> frações (medidas em px viram % da largura/altura)
  const d = {}; DOCK_LARG.forEach(k => d[k] = state[k] / OUT.w); DOCK_ALT.forEach(k => d[k] = state[k] / OUT.h); DOCK_PURO.forEach(k => d[k] = state[k]); return d;
};
const aplicarDock = d => {                                        // frações guardadas -> px no tamanho de saída atual
  DOCK_LARG.forEach(k => { if (d[k] != null) state[k] = Math.round(d[k] * OUT.w); });
  DOCK_ALT.forEach(k => { if (d[k] != null) state[k] = Math.round(d[k] * OUT.h); });
  DOCK_PURO.forEach(k => { if (d[k] != null) state[k] = d[k]; });
};
async function carregarGaleria(){
  try { gal = (await DB.all()).sort((a, b) => b.added - a.added); }
  catch (e) { gal = []; toast('Não foi possível ler o histórico.'); }
}

/* Identificador = hash dos primeiros 256 KB + tamanho: a mesma foto escolhida de novo não duplica */
async function idDe(f){
  if (window.crypto && crypto.subtle){
    const h = await crypto.subtle.digest('SHA-256', await f.slice(0, 262144).arrayBuffer());
    return [...new Uint8Array(h)].slice(0, 8).map(b => b.toString(16).padStart(2, '0')).join('') + '-' + f.size;
  }
  return f.name + '-' + f.size + '-' + f.lastModified;
}
async function miniatura(f){                         // decodifica já reduzido (360 px de largura): não carrega a foto inteira só para a miniatura
  const bmp = await createImageBitmap(f, { resizeWidth: 360, resizeQuality: 'medium' });
  const c = document.createElement('canvas'); c.width = bmp.width; c.height = bmp.height;
  c.getContext('2d').drawImage(bmp, 0, 0); if (bmp.close) bmp.close();
  return new Promise(ok => c.toBlob(ok, 'image/jpeg', .82));
}

async function adicionar(arquivos){
  let ultimo = null, falhas = 0;
  for (const f of arquivos){
    try {
      if (!/^image\//.test(f.type)) throw new Error('não é imagem');
      const id = await idDe(f), antigo = gal.find(g => g.id === id);
      await DB.put(antigo ? { ...antigo, added: Date.now() }                     // já existe: só sobe para o topo
                          : { id, name: f.name || 'imagem', blob: f, thumb: await miniatura(f), added: Date.now() });
      await carregarGaleria();
      ultimo = id;
    } catch (e) { falhas++; }
  }
  for (const extra of gal.slice(LIMITE)) if (extra.id !== curId){ await DB.del(extra.id).catch(() => {}); delete FR[extra.id]; delete DK[extra.id]; }   // mantém só os 20 mais recentes
  salvarFR(); salvarDK(); await carregarGaleria();
  const aviso = falhas ? (falhas === arquivos.length ? 'Não foi possível ler essa imagem.' : falhas + ' imagem(ns) não puderam ser lidas.') : '';
  const trocou = ultimo ? await selecionar(ultimo) : false;       // um único aviso: o de seleção e o de falha não se apagam
  if (trocou || aviso) toast([trocou ? 'Wallpaper selecionado.' : '', aviso].filter(Boolean).join(' '));
  redesenharTelas();
}

function guardarEnquadramento(){                                  // guarda o enquadramento (e a Dock, se for individual) do wallpaper aberto
  if (!curId) return;
  FR[curId] = { zoom: state.zoom, panX: state.panX, panY: state.panY }; salvarFR();
  if (PREF.dockIndividual && OUT.w){ DK[curId] = dockAtual(); salvarDK(); }
}
async function selecionar(id){
  guardarEnquadramento();                            // salva o enquadramento do wallpaper que está saindo
  const rec = gal.find(g => g.id === id);
  if (!rec) return false;
  try {
    const bmp = await createImageBitmap(rec.blob);
    setWall(bmp);                                    // troca o fundo (e o tema, se as cores vierem do wallpaper)
    const fr = FR[id] || { zoom: 100, panX: 0, panY: 0 };
    state.zoom = fr.zoom; state.panX = fr.panX; state.panY = fr.panY;   // enquadramento guardado desse wallpaper
    if (PREF.dockIndividual){                        // Dock individual: usa a Dock guardada desse wallpaper (ou começa com a que está aberta)
      if (DK[id]) aplicarDock(DK[id]); else { DK[id] = dockAtual(); salvarDK(); }
    }
    curId = id; hasWall = true;
    try { localStorage.setItem('dockCurrent', id); } catch (e) {}
    $('nWall').textContent = rec.name; setThumb('tWall', rec.blob);
    syncInputs(); schedule();
    return true;
  } catch (e) {                                      // arquivo indisponível ou corrompido
    await DB.del(id).catch(() => {}); delete FR[id]; delete DK[id]; salvarFR(); salvarDK(); await carregarGaleria();
    toast('Imagem indisponível. Removida do histórico.'); redesenharTelas();
    return false;
  }
}
async function remover(id){
  try { await DB.del(id); } catch (e) {}
  delete FR[id]; delete DK[id]; salvarFR(); salvarDK();
  if (id === curId){ curId = null; try { localStorage.removeItem('dockCurrent'); } catch (e) {} }
  await carregarGaleria(); redesenharTelas(); toast('Removida do histórico.');
}

/* Salvar o enquadramento enquanto ele é ajustado (aproveita o gancho de salvamento que já existe) */
const _saveSoon = saveSoon; let frT;
saveSoon = function(){ _saveSoon(); clearTimeout(frT); frT = setTimeout(guardarEnquadramento, 700); };
$('btnFitReset').addEventListener('click', () => { state.zoom = 100; state.panX = 0; state.panY = 0; syncInputs(); schedule(); });

/* ----- Telas ----- */
let urls = [];
const urlDe = rec => { const u = URL.createObjectURL(rec.thumb); urls.push(u); return u; };
const limparUrls = () => { urls.forEach(u => URL.revokeObjectURL(u)); urls = []; };

function desenharHome(){
  limparUrls();
  $('heroImg').hidden = !hasWall; $('heroEmpty').hidden = hasWall;
  if (hasWall){ draw(); $('heroImg').src = cv.toDataURL('image/jpeg', .85); draw(); }   // foto da prévia atual, com a Dock
  $('hPick').textContent = hasWall ? 'Trocar wallpaper' : 'Escolher wallpaper';
  $('hRecent').innerHTML = gal.length
    ? gal.slice(0, 8).map(g => `<button class="rc${g.id === curId ? ' sel' : ''}" type="button" data-id="${esc(g.id)}" aria-label="Usar ${esc(g.name)}"><img src="${urlDe(g)}" alt=""></button>`).join('')
    : '<div class="none">Os wallpapers que você escolher aparecem aqui.</div>';
}
function desenharGaleria(){
  limparUrls();
  $('gEmpty').hidden = gal.length > 0;
  $('gGrid').innerHTML = gal.map(g => `<div class="gi${g.id === curId ? ' sel' : ''}" data-id="${esc(g.id)}" role="button" tabindex="0" aria-label="Usar ${esc(g.name)}"><img src="${urlDe(g)}" alt=""><i class="chk">&#10003;</i><button class="x" type="button" aria-label="Remover do histórico">&times;</button></div>`).join('');
}
const marcarSeg = (id, v) => [...$(id).children].forEach(b => b.classList.toggle('on', b.dataset.v === v));
function desenharConfig(){
  marcarSeg('sTema', PREF.tema); marcarSeg('sCores', PREF.cores); marcarSeg('sQual', String(PREF.qualidade));
  $('sEditor').checked = PREF.editorInicio;
  $('sIndiv').checked = PREF.dockIndividual;
  $('rowCores').hidden = !SYS;                       // a paleta do sistema só existe no APK (Android 12+)
  $('sHistN').textContent = gal.length + (gal.length === 1 ? ' imagem' : ' imagens');
  $('sHistClear').disabled = !gal.length;
  $('sVer').textContent = APP_VERSION;
  desenharTroca();
}
const VISTAS = { home: ['v-home', desenharHome], gallery: ['v-gallery', desenharGaleria], settings: ['v-settings', desenharConfig] };
let vista = 'home';
function mostrar(nome){
  vista = VISTAS[nome] ? nome : (nome === 'editor' ? 'editor' : 'home');
  for (const k in VISTAS) $(VISTAS[k][0]).classList.toggle('on', k === vista);
  if (VISTAS[vista]) VISTAS[vista][1]();
  else { fitPhone(); schedule(); }                   // voltou ao editor
}
const redesenharTelas = () => { if (VISTAS[vista]) VISTAS[vista][1](); };
function ir(nome, substituir){
  if (nome === vista && !substituir) return;
  history[substituir ? 'replaceState' : 'pushState']({ v: nome }, '');
  mostrar(nome);
}
const voltar = () => { if (history.length > 1) history.back(); else ir('home', true); };
addEventListener('popstate', e => mostrar((e.state && e.state.v) || 'home'));

document.addEventListener('click', e => {           // botões de navegação de todas as telas
  const go = e.target.closest('[data-go]'), back = e.target.closest('[data-back]');
  if (go) ir(go.dataset.go);
  if (back) voltar();
});
$('eBack').addEventListener('click', () => ir('home'));
$('hDock').addEventListener('click', () => ir('editor'));
['hPick', 'gAdd', 'gAdd2'].forEach(id => $(id).addEventListener('click', () => $('gFile').click()));   // seletor de fotos do Android: não pede permissão de armazenamento
$('gFile').addEventListener('change', e => { const fs = [...e.target.files]; e.target.value = ''; if (fs.length) adicionar(fs); });
$('fWall').addEventListener('change', e => {         // o cartão "Papel de parede final" do editor também passa pelo histórico
  e.stopImmediatePropagation(); const fs = [...e.target.files]; e.target.value = ''; if (fs.length) adicionar(fs);
}, true);
$('hRecent').addEventListener('click', async e => { const b = e.target.closest('.rc'); if (b && await selecionar(b.dataset.id)) desenharHome(); });
$('gGrid').addEventListener('click', async e => {
  const item = e.target.closest('.gi'); if (!item) return;
  if (e.target.closest('.x')){ remover(item.dataset.id); return; }
  if (await selecionar(item.dataset.id)){ toast('Wallpaper selecionado.'); voltar(); }
});
$('gGrid').addEventListener('keydown', e => { if (e.key === 'Enter' && e.target.classList.contains('gi')) e.target.click(); });
$('splitMenu').addEventListener('click', e => {      // itens de navegação do menu do editor (captura: não chegam ao tratador antigo)
  const b = e.target.closest('button'); if (!b || !/^nav-/.test(b.dataset.act || '')) return;
  e.stopPropagation(); fecharMenu(); ir(b.dataset.act === 'nav-home' ? 'home' : 'settings');
}, true);

/* Configurações */
$('sTema').addEventListener('click', e => { const b = e.target.closest('button'); if (b){ PREF.tema = b.dataset.v; salvarPrefs(); applyTheme(); marcarSeg('sTema', PREF.tema); } });
$('sCores').addEventListener('click', e => { const b = e.target.closest('button'); if (b){ PREF.cores = b.dataset.v; salvarPrefs(); applyTheme(); marcarSeg('sCores', PREF.cores); } });
$('sQual').addEventListener('click', e => { const b = e.target.closest('button'); if (b){ PREF.qualidade = +b.dataset.v; salvarPrefs(); marcarSeg('sQual', b.dataset.v); schedule(); } });
$('sEditor').addEventListener('change', e => { PREF.editorInicio = e.target.checked; salvarPrefs(); });
$('sIndiv').addEventListener('change', e => {
  PREF.dockIndividual = e.target.checked; salvarPrefs();
  if (PREF.dockIndividual && curId){ DK[curId] = dockAtual(); salvarDK(); }   // ao ligar, o wallpaper aberto guarda a Dock que está aberta
  toast(PREF.dockIndividual ? 'Cada wallpaper guarda a própria Dock.' : 'Todos os wallpapers usam a mesma Dock.');
});
$('sHistClear').addEventListener('click', async () => {
  if (!gal.length || !confirm('Apagar todo o histórico de wallpapers? A imagem aberta no editor continua lá.')) return;
  try { await DB.clear(); } catch (e) {}
  FR = {}; DK = {}; salvarFR(); salvarDK(); curId = null; try { localStorage.removeItem('dockCurrent'); } catch (e) {}
  await carregarGaleria(); desenharConfig(); toast('Histórico apagado.');
});
$('sRestore').addEventListener('click', () => {
  if (!confirm('Restaurar todas as configurações para o padrão? O histórico de wallpapers não é apagado.')) return;
  const tinhaTroca = PREF.trocaAtiva || PREF.trocaGerado;
  Object.assign(PREF, PREF_PADRAO); salvarPrefs(); DK = {}; salvarDK();     // cada wallpaper volta a começar com a Dock padrão
  if (tinhaTroca && trocaOk()) desligarTroca().catch(() => {});      // a troca automática também volta ao padrão (desligada)
  $('btnReset').click();                             // reaproveita a restauração que já existia (Dock, vidro, tamanho)
  applyTheme(); desenharConfig(); toast('Configurações restauradas.');
});

/* Início do app: reabre o último wallpaper e escolhe a tela inicial */
(async () => {
  await carregarGaleria();
  let id = null; try { id = localStorage.getItem('dockCurrent'); } catch (e) {}
  if (id && gal.some(g => g.id === id)) await selecionar(id);
  const inicio = PREF.editorInicio && hasWall ? 'editor' : 'home';
  history.replaceState({ v: inicio }, ''); mostrar(inicio);
})();
applyTheme();                                        // aplica de novo agora que as preferências existem

/* ---------- v12: sem print de referência; destino do wallpaper escolhido na hora de aplicar ---------- */
function escolherAlvo(){                             // caixa "Aplicar em": devolve 'home', 'lock', 'both' ou null (cancelou)
  return new Promise(ok => {
    const d = $('alvoDlg'); d.hidden = false;
    const fim = v => { d.hidden = true; d.onclick = null; ok(v); };
    d.onclick = e => { const b = e.target.closest('[data-alvo]'); if (b) fim(b.dataset.alvo || null); else if (e.target === d) fim(null); };
  });
}
if (DO && !localStorage.getItem('dockSettings')) $('outMode').value = 'device';   // primeira vez no APK: o PNG sai na resolução da tela do aparelho

/* ---------- Troca automática de wallpaper (só no APK) ----------
   O vidro da Dock é desenhado aqui, em JavaScript, que não roda com o app fechado. Por isso o app GERA as imagens prontas
   (cada wallpaper do histórico + a Dock atual, em tamanho real) e entrega ao Android, que troca sozinho a cada N horas
   com o WorkManager (agendador do sistema: funciona com o app fechado e depois de reiniciar o celular). */
const trocaOk = () => !!(DO && DO.scheduleRotation);
const hashTroca = () => JSON.stringify([OUT.w, OUT.h, SCALED.map(k => state[k]), ['ior', 'scale', 'chroma', 'shine', 'angle', 'profile'].map(k => state[k]),
                                         gal.slice(0, LIMITE).map(g => [g.id, FR[g.id] || null]), PREF.dockIndividual, gal.slice(0, LIMITE).map(g => DK[g.id] || null)]);   // tudo o que muda o resultado das imagens
const trocaDesatualizada = () => !PREF.trocaGerado || PREF.trocaGerado.hash !== hashTroca();
const paraBase64 = blob => new Promise(ok => { const r = new FileReader(); r.onload = () => ok(r.result.split(',')[1]); r.readAsDataURL(blob); });
const mostrarProgresso = (sub) => { $('progS').textContent = sub; $('progDlg').hidden = false; };
const esconderProgresso = () => { $('progDlg').hidden = true; };

/* Desenha UM wallpaper do histórico com a Dock atual, em tamanho real (JPEG), sem mexer no que está aberto no editor. */
async function renderizarItem(rec){
  const bmp = await createImageBitmap(rec.blob);
  const salvo = { wall, zoom: state.zoom, panX: state.panX, panY: state.panY, dock: Object.fromEntries(CAMPOS_DOCK.map(k => [k, state[k]])) };
  try {
    wall = bmp; wallId++;                                          // wallId invalida o cache do fundo e do vidro
    const fr = FR[rec.id] || { zoom: 100, panX: 0, panY: 0 };     // cada wallpaper usa o enquadramento guardado dele
    state.zoom = fr.zoom; state.panX = fr.panX; state.panY = fr.panY;
    if (PREF.dockIndividual && DK[rec.id]) aplicarDock(DK[rec.id]);   // e a Dock guardada dele
    draw(true);
    return await new Promise(ok => cv.toBlob(ok, 'image/jpeg', .95));
  } finally {                                                      // devolve o editor ao estado anterior
    wall = salvo.wall; wallId++; state.zoom = salvo.zoom; state.panX = salvo.panX; state.panY = salvo.panY;
    CAMPOS_DOCK.forEach(k => state[k] = salvo.dock[k]);
    draw(); if (bmp.close) bmp.close();
  }
}
async function gerarRotacao(){
  const itens = gal.slice(0, LIMITE);
  if (!itens.length) throw new Error('Adicione wallpapers à galeria primeiro.');
  let feitas = 0;
  mostrarProgresso('0 de ' + itens.length);
  try {
    await DO.clearRotation();
    for (let i = 0; i < itens.length; i++){
      $('progS').textContent = (i + 1) + ' de ' + itens.length;
      try { await DO.saveRotationImage({ index: feitas, data: await paraBase64(await renderizarItem(itens[i])) }); feitas++; }
      catch (e) { /* imagem ilegível: pula */ }
    }
  } finally { esconderProgresso(); }
  if (!feitas) throw new Error('Nenhuma imagem pôde ser gerada.');
  PREF.trocaGerado = { n: feitas, em: Date.now(), hash: hashTroca() }; salvarPrefs();
}
async function desligarTroca(){
  await DO.cancelRotation(); await DO.clearRotation();             // cancela o agendamento e libera o espaço das imagens
  PREF.trocaAtiva = false; PREF.trocaGerado = null; salvarPrefs();
}
const reagendar = () => DO.scheduleRotation({ hours: PREF.trocaHoras, target: PREF.trocaAlvo });

function desenharTroca(){
  guardarEnquadramento();                                          // garante que o enquadramento e a Dock mais recentes entrem na conta
  $('cardTroca').hidden = !trocaOk();                              // só existe no APK
  if (!trocaOk()) return;
  const g = PREF.trocaGerado;
  $('tAtiva').checked = PREF.trocaAtiva;
  marcarSeg('tHoras', String(PREF.trocaHoras)); marcarSeg('tAlvo', PREF.trocaAlvo);
  $('tInfo').textContent = g ? g.n + (g.n === 1 ? ' imagem pronta' : ' imagens prontas') : 'Nenhuma imagem pronta';
  $('tInfo2').textContent = !g ? 'São geradas com a sua Dock atual.'
    : trocaDesatualizada() ? 'A Dock ou a galeria mudou: toque em Atualizar.'
    : 'Atualizadas em ' + new Date(g.em).toLocaleString('pt-BR', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' });
  $('tAgora').disabled = !PREF.trocaAtiva || !g;
}
$('tAtiva').addEventListener('change', async e => {
  const quer = e.target.checked;
  try {
    if (quer){
      if (trocaDesatualizada()) await gerarRotacao();             // gera as imagens na primeira vez (ou se algo mudou)
      await reagendar(); PREF.trocaAtiva = true; salvarPrefs();
      toast('Troca automática ligada: a cada ' + PREF.trocaHoras + ' h.');
    } else { await desligarTroca(); toast('Troca automática desligada.'); }
  } catch (err) { e.target.checked = PREF.trocaAtiva; toast(err.message || 'Não foi possível alterar a troca automática.'); }
  desenharConfig();
});
$('tHoras').addEventListener('click', async e => {
  const b = e.target.closest('button'); if (!b) return;
  PREF.trocaHoras = +b.dataset.v; salvarPrefs(); marcarSeg('tHoras', b.dataset.v);
  if (PREF.trocaAtiva) await reagendar().catch(() => toast('Não foi possível reagendar.'));
});
$('tAlvo').addEventListener('click', async e => {
  const b = e.target.closest('button'); if (!b) return;
  PREF.trocaAlvo = b.dataset.v; salvarPrefs(); marcarSeg('tAlvo', b.dataset.v);
  if (PREF.trocaAtiva) await reagendar().catch(() => toast('Não foi possível reagendar.'));
});
$('tAtualizar').addEventListener('click', async () => {
  try { await gerarRotacao(); toast('Imagens atualizadas.'); } catch (err) { toast(err.message || 'Não foi possível atualizar.'); }
  desenharConfig();
});
$('tAgora').addEventListener('click', async () => {
  try { const r = await DO.rotateNow({ target: PREF.trocaAlvo }); toast(r && r.applied ? 'Wallpaper trocado.' : 'Não há imagens prontas.'); }
  catch (err) { toast('Não foi possível trocar agora.'); }
});

/* Arranque */
setWall(wall);
