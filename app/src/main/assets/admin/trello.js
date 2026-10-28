// Trello dentro do Admin, pela API REST do Trello (api.trello.com aceita pedidos de qualquer origem).
// A API key e o token ficam só no localStorage deste aparelho, nunca na cloud.
window.Trello = (function(){
  const API = 'https://api.trello.com/1';
  const KEY = '__trelloKey', TOKEN = '__trelloToken', BOARD = '__trelloBoard';
  const page = $('pageTrello');
  const LABEL_COLORS = {
    green:'#4bce97', yellow:'#f5cd47', orange:'#fea362', red:'#f87168', purple:'#9f8fef', blue:'#579dff',
    sky:'#6cc3e0', lime:'#94c748', pink:'#e774bb', black:'#8590a2',
    green_dark:'#1f845a', yellow_dark:'#946f00', orange_dark:'#c25100', red_dark:'#c9372c', purple_dark:'#6e5dc6',
    blue_dark:'#0c66e4', sky_dark:'#227d9b', lime_dark:'#5b7f24', pink_dark:'#ae4787', black_dark:'#626f86',
    green_light:'#baf3db', yellow_light:'#f8e6a0', orange_light:'#fedec8', red_light:'#ffd5d2', purple_light:'#dfd8fd',
    blue_light:'#cce0ff', sky_light:'#c6edfb', lime_light:'#d3f1a7', pink_light:'#fdd0ec', black_light:'#dcdfe4',
  };
  const labelColor = c => LABEL_COLORS[c] || '#626f86';

  const css = document.createElement('style');
  css.textContent = `
  #pageTrello{padding:0!important}
  .tr-top{display:flex;gap:8px;align-items:center;padding:0 12px 10px}
  .tr-top .pill-btn{height:36px;font-size:14px}
  .tr-top .grow{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;text-align:left;font-weight:600}
  .tr-board{height:calc(100dvh - 128px);min-height:380px;overflow:hidden;background:#0c66e4 center/cover;border-radius:14px 14px 0 0;display:flex;flex-direction:column}
  .tr-cols{flex:1;display:flex;gap:10px;align-items:flex-start;overflow-x:auto;overflow-y:hidden;padding:12px;scroll-snap-type:x mandatory;-webkit-overflow-scrolling:touch}
  .tr-col{flex:0 0 min(290px,82vw);max-height:100%;background:#101204;background:#101214;border-radius:14px;display:flex;flex-direction:column;scroll-snap-align:start}
  .tr-col-h{display:flex;align-items:center;gap:6px;padding:10px 10px 6px 14px;font-weight:600;font-size:14px}
  .tr-col-h span{flex:1;min-width:0;overflow:hidden;text-overflow:ellipsis;cursor:pointer}
  .tr-col-h small{color:var(--muted);font-weight:500}
  .tr-col-h button,.tr-mini{background:none;border:none;color:var(--muted);font-size:18px;cursor:pointer;padding:2px 6px;border-radius:6px}
  .tr-cards{flex:1;overflow-y:auto;padding:2px 8px 4px;display:flex;flex-direction:column;gap:8px;min-height:8px}
  .tr-card{background:#22272b;border-radius:10px;padding:8px 10px;font-size:14px;line-height:1.35;cursor:pointer;box-shadow:0 1px 0 rgba(0,0,0,.4);touch-action:pan-x pan-y;user-select:none;-webkit-user-select:none;word-break:break-word}
  .tr-card:hover{background:#2c333a}
  .tr-card.ghost{opacity:.35}
  .tr-card.done .tr-name{text-decoration:line-through;color:var(--muted)}
  .tr-drag{position:fixed;z-index:30;pointer-events:none;transform:rotate(3deg);box-shadow:0 12px 30px rgba(0,0,0,.6)}
  .tr-labels{display:flex;flex-wrap:wrap;gap:4px;margin-bottom:5px}
  .tr-labels i{display:block;height:8px;min-width:36px;border-radius:4px}
  .tr-labels b{font-size:11px;font-weight:600;padding:1px 7px;border-radius:4px;color:#1d2125}
  .tr-badges{display:flex;flex-wrap:wrap;gap:10px;margin-top:6px;font-size:12px;color:#9fadbc;align-items:center}
  .tr-due{border-radius:4px;padding:1px 6px}
  .tr-due.soon{background:#f5cd47;color:#533f04}
  .tr-due.late{background:#f87168;color:#5d1f1a}
  .tr-due.ok{background:#4bce97;color:#164b35}
  .tr-add{background:none;border:none;color:#9fadbc;text-align:left;padding:10px 14px;font-size:14px;cursor:pointer;border-radius:0 0 14px 14px}
  .tr-add:hover{background:rgba(255,255,255,.06)}
  .tr-newcol{flex:0 0 min(290px,82vw);background:rgba(255,255,255,.22);color:#fff;border:none;border-radius:14px;padding:12px 14px;text-align:left;font-size:14px;cursor:pointer;scroll-snap-align:start}
  .tr-msg{padding:24px 18px;color:var(--muted);font-size:14px;text-align:center}
  .tr-msg.err{color:var(--danger)}
  .tr-sheet{max-width:640px!important;max-height:92%!important}
  .tr-sheet h3{text-transform:none!important}
  .tr-sec{margin-top:16px}
  .tr-sec>b{display:flex;align-items:center;gap:8px;font-size:14px;margin-bottom:8px}
  .tr-sec>b small{color:var(--muted);font-weight:500}
  .tr-chips{display:flex;flex-wrap:wrap;gap:6px}
  .tr-chip{border:none;border-radius:8px;padding:6px 10px;font-size:13px;cursor:pointer;background:var(--card2);color:var(--text)}
  .tr-sheet textarea{width:100%;min-height:90px;background:var(--card2);border:1px solid transparent;color:var(--text);border-radius:12px;padding:12px;font-size:15px;outline:none;resize:vertical;font-family:inherit}
  .tr-sheet textarea:focus{border-color:var(--accent)}
  .tr-sheet select{width:100%;background:var(--card2);border:1px solid transparent;color:var(--text);border-radius:12px;padding:12px;font-size:15px;color-scheme:dark}
  .tr-item{display:flex;align-items:center;gap:10px;padding:6px 0;font-size:14px}
  .tr-item input[type=checkbox]{width:18px;height:18px;flex:none;accent-color:#4bce97}
  .tr-item span{flex:1;word-break:break-word}
  .tr-item.done span{text-decoration:line-through;color:var(--muted)}
  .tr-bar{height:6px;background:var(--card2);border-radius:3px;margin-bottom:6px}
  .tr-bar div{height:6px;background:#4bce97;border-radius:3px}
  .tr-comment{background:var(--card2);border-radius:10px;padding:8px 10px;margin-top:8px;font-size:14px;white-space:pre-wrap;word-break:break-word}
  .tr-comment small{display:block;color:var(--muted);font-size:12px;margin-bottom:3px}
  .tr-att{display:flex;align-items:center;gap:8px;padding:6px 0;font-size:14px}
  .tr-att a{flex:1;color:var(--accent);cursor:pointer;word-break:break-all}
  .tr-pick{display:flex;align-items:center;gap:10px;width:100%;background:var(--card2);border:none;color:var(--text);border-radius:10px;padding:10px 12px;margin-top:6px;font-size:14px;cursor:pointer;text-align:left}
  .tr-pick.on{outline:2px solid var(--accent)}
  .tr-pick i{width:40px;height:18px;border-radius:4px;flex:none}
  #trCal .day{min-height:76px}
  #trCal .chip{cursor:pointer}
  `;
  document.head.appendChild(css);

  const hasLogin = () => !!(localStorage.getItem(KEY) && localStorage.getItem(TOKEN));
  function setLogin(key, token){
    if(key && token){ localStorage.setItem(KEY, key); localStorage.setItem(TOKEN, token); }
    else { localStorage.removeItem(KEY); localStorage.removeItem(TOKEN); boards = null; board = null; }
  }

  async function api(method, path, params = {}, body){
    const u = new URL(API + path);
    u.searchParams.set('key', localStorage.getItem(KEY) || '');
    u.searchParams.set('token', localStorage.getItem(TOKEN) || '');
    Object.entries(params).forEach(([k,v]) => { if(v !== undefined) u.searchParams.set(k, v === null ? '' : v); });
    let r;
    try{ r = await fetch(u, { method, body }); }
    catch(e){ throw new Error('Sem ligação ao Trello'); }
    if(r.status === 401) throw new Error('API key ou token inválidos (Definições → Trello)');
    if(!r.ok) throw new Error(`Erro do Trello (${r.status})`);
    const text = await r.text();
    return text ? JSON.parse(text) : null;
  }
  const fail = e => alert(e.message);

  /* ---------- Estado ---------- */
  let boards = null, board = null, view = 'board', calView = new Date(), labelFilter = new Set(), dueFilter = '';
  let openCardId = null;

  const cardsOf = listId => board.cards.filter(c => c.idList === listId && passes(c)).sort((a,b) => a.pos - b.pos);
  function passes(c){
    if(labelFilter.size && !c.idLabels.some(id => labelFilter.has(id))) return false;
    if(!dueFilter) return true;
    const st = dueState(c);
    if(dueFilter === 'late') return st === 'late';
    if(dueFilter === 'week') return c.due && !c.dueComplete && new Date(c.due) - Date.now() < 7*864e5;
    if(dueFilter === 'none') return !c.due;
    return true;
  }
  function dueState(c){
    if(!c.due) return '';
    if(c.dueComplete) return 'ok';
    const ms = new Date(c.due) - Date.now();
    return ms < 0 ? 'late' : ms < 864e5 ? 'soon' : '';
  }
  const dueText = d => new Date(d).toLocaleDateString('pt-PT', { day:'numeric', month:'short' });
  const dueFull = d => new Date(d).toLocaleString('pt-PT', { weekday:'short', day:'numeric', month:'short', hour:'2-digit', minute:'2-digit' });

  /* ---------- Carregar ---------- */
  async function loadBoards(){
    boards = await api('GET', '/members/me/boards', { filter:'open', fields:'name,prefs,closed' });
  }
  async function loadBoard(id){
    const b = await api('GET', `/boards/${id}`, {
      fields:'name,prefs,url', lists:'open', list_fields:'name,pos,closed', cards:'open', labels:'all', label_fields:'name,color',
      card_fields:'name,desc,due,dueComplete,idList,idLabels,pos,badges,closed,shortUrl',
    });
    b.lists.sort((a,b) => a.pos - b.pos);
    board = b;
    localStorage.setItem(BOARD, id);
  }
  async function show(){
    if(!hasLogin()){
      page.innerHTML = `<div class="tr-msg">Cola a API key e o token do Trello nas Definições.<br><br><button class="btn" id="trGoSet">Abrir Definições</button></div>`;
      $('trGoSet').onclick = () => go('settings');
      return;
    }
    if(!board) page.innerHTML = '<div class="tr-msg">A carregar o Trello…</div>';
    try{
      if(!boards) await loadBoards();
      if(!boards.length){ page.innerHTML = '<div class="tr-msg">Ainda não tens quadros no Trello.</div>'; return; }
      const id = board ? board.id : (boards.find(b => b.id === localStorage.getItem(BOARD)) || boards[0]).id;
      await loadBoard(id);
      render();
    }catch(e){
      page.innerHTML = `<div class="tr-msg err">${esc(e.message)}</div>`;
    }
  }
  async function refresh(){ try{ await loadBoard(board.id); render(); if(openCardId) openCard(openCardId); }catch(e){ fail(e); } }

  /* ---------- Quadro ---------- */
  function boardBg(){
    const p = board.prefs || {};
    if(p.backgroundImage){
      const scaled = (p.backgroundImageScaled || []).find(s => s.width >= 960);
      return `url("${(scaled || {}).url || p.backgroundImage}") center/cover, ${p.backgroundBottomColor || '#0c66e4'}`;
    }
    return p.backgroundColor || '#0c66e4';
  }
  function render(){
    const filtered = labelFilter.size || dueFilter;
    page.innerHTML = `
      <div class="tr-top">
        <button class="pill-btn grow" id="trBoardBtn">${esc(board.name)} ▾</button>
        <button class="pill-btn" id="trViewBtn">${view === 'board' ? '📅' : '📋'}</button>
        <button class="pill-btn" id="trFilterBtn" style="${filtered ? 'border-color:var(--accent);color:var(--accent)' : ''}">⏷</button>
        <button class="pill-btn" id="trRefresh">⟳</button>
      </div>
      <div class="tr-board" style="background:${boardBg()}"></div>`;
    $('trBoardBtn').onclick = boardMenu;
    $('trViewBtn').onclick = () => { view = view === 'board' ? 'calendar' : 'board'; render(); };
    $('trFilterBtn').onclick = filterMenu;
    $('trRefresh').onclick = refresh;
    const area = page.querySelector('.tr-board');
    if(view === 'calendar') return renderCalendar(area);
    const cols = document.createElement('div'); cols.className = 'tr-cols';
    board.lists.forEach(l => cols.appendChild(listEl(l)));
    const add = document.createElement('button'); add.className = 'tr-newcol'; add.textContent = '+ Adicionar lista';
    add.onclick = async () => {
      const name = prompt('Nome da lista'); if(!name || !name.trim()) return;
      try{ const l = await api('POST', '/lists', { idBoard:board.id, name:name.trim(), pos:'bottom' }); board.lists.push(l); render(); scrollEnd(); }catch(e){ fail(e); }
    };
    cols.appendChild(add);
    area.appendChild(cols);
  }
  const scrollEnd = () => { const c = page.querySelector('.tr-cols'); if(c) c.scrollLeft = c.scrollWidth; };

  function listEl(l){
    const col = document.createElement('div'); col.className = 'tr-col'; col.dataset.list = l.id;
    const cards = cardsOf(l.id);
    col.innerHTML = `<div class="tr-col-h"><span>${esc(l.name)}</span><small>${cards.length}</small><button aria-label="Opções da lista">⋯</button></div><div class="tr-cards"></div>`;
    col.querySelector('.tr-col-h span').onclick = () => renameList(l);
    col.querySelector('.tr-col-h button').onclick = () => listMenu(l);
    const box = col.querySelector('.tr-cards');
    cards.forEach(c => box.appendChild(cardEl(c)));
    const add = document.createElement('button'); add.className = 'tr-add'; add.textContent = '+ Adicionar cartão';
    add.onclick = async () => {
      const name = prompt(`Novo cartão em "${l.name}"`); if(!name || !name.trim()) return;
      try{ const c = await api('POST', '/cards', { idList:l.id, name:name.trim(), pos:'bottom' }); board.cards.push(c); render(); }catch(e){ fail(e); }
    };
    col.appendChild(add);
    return col;
  }

  function cardEl(c){
    const el = document.createElement('div');
    el.className = 'tr-card' + (c.dueComplete ? ' done' : '');
    el.dataset.card = c.id;
    const labels = c.idLabels.map(id => board.labels.find(x => x.id === id)).filter(Boolean);
    const b = c.badges || {};
    let badges = '';
    if(c.due) badges += `<span class="tr-due ${dueState(c)}">🕒 ${dueText(c.due)}</span>`;
    if(c.desc) badges += '<span>≡</span>';
    if(b.checkItems) badges += `<span>☑ ${b.checkItemsChecked}/${b.checkItems}</span>`;
    if(b.comments) badges += `<span>💬 ${b.comments}</span>`;
    if(b.attachments) badges += `<span>📎 ${b.attachments}</span>`;
    el.innerHTML = (labels.length ? `<div class="tr-labels">${labels.map(x => x.name
        ? `<b style="background:${labelColor(x.color)}">${esc(x.name)}</b>` : `<i style="background:${labelColor(x.color)}"></i>`).join('')}</div>` : '')
      + `<div class="tr-name">${esc(c.name)}</div>` + (badges ? `<div class="tr-badges">${badges}</div>` : '');
    attachDrag(el, c);
    return el;
  }

  /* ---------- Arrastar cartões (carregar sem largar no telemóvel; arrastar logo com o rato) ---------- */
  let drag = null;
  function attachDrag(el, c){
    let timer = null, start = null, moved = false;
    const begin = (x, y) => {
      const r = el.getBoundingClientRect();
      const ghost = el.cloneNode(true); ghost.classList.add('tr-drag');
      ghost.style.width = r.width + 'px'; ghost.style.left = r.left + 'px'; ghost.style.top = r.top + 'px';
      document.body.appendChild(ghost);
      el.classList.add('ghost');
      drag = { el, c, ghost, dx: x - r.left, dy: y - r.top };
      if(navigator.vibrate) navigator.vibrate(25);
    };
    el.addEventListener('pointerdown', e => {
      if(e.button > 0) return;
      start = { x:e.clientX, y:e.clientY, mouse:e.pointerType === 'mouse' }; moved = false;
      if(!start.mouse) timer = setTimeout(() => { timer = null; begin(start.x, start.y); }, 380);
    });
    el.addEventListener('pointermove', e => {
      if(!start) return;
      const far = Math.hypot(e.clientX - start.x, e.clientY - start.y) > 8;
      if(far) moved = true;
      if(!drag && far){
        if(start.mouse) begin(start.x, start.y);
        else { clearTimeout(timer); timer = null; start = null; }
      }
    });
    el.addEventListener('pointerup', () => {
      clearTimeout(timer); timer = null;
      if(start && !drag && !moved) openCard(c.id);
      start = null;
    });
    el.addEventListener('pointercancel', () => { clearTimeout(timer); timer = null; start = null; });
    el.addEventListener('contextmenu', e => e.preventDefault());
  }
  let edgeTimer = null;
  function dragMove(x, y){
    if(!drag) return;
    drag.ghost.style.left = (x - drag.dx) + 'px';
    drag.ghost.style.top = (y - drag.dy) + 'px';
    const under = document.elementFromPoint(x, y);
    const col = under && under.closest('.tr-col');
    if(col){
      const box = col.querySelector('.tr-cards');
      const after = [...box.querySelectorAll('.tr-card:not(.ghost)')].find(n => { const r = n.getBoundingClientRect(); return y < r.top + r.height / 2; });
      if(after) box.insertBefore(drag.el, after); else box.appendChild(drag.el);
    }
    // Junto às margens o quadro anda sozinho para os lados e a lista para cima/baixo
    clearInterval(edgeTimer);
    const cols = page.querySelector('.tr-cols');
    const w = window.innerWidth, list = col && col.querySelector('.tr-cards');
    const dxs = x < 40 ? -12 : x > w - 40 ? 12 : 0;
    const lr = list && list.getBoundingClientRect();
    const dys = lr ? (y < lr.top + 30 ? -10 : y > lr.bottom - 30 ? 10 : 0) : 0;
    if(dxs || dys) edgeTimer = setInterval(() => { if(dxs) cols.scrollLeft += dxs; if(dys) list.scrollTop += dys; }, 16);
  }
  async function dragEnd(){
    if(!drag) return;
    clearInterval(edgeTimer);
    const { el, c, ghost } = drag; drag = null;
    ghost.remove(); el.classList.remove('ghost');
    const col = el.closest('.tr-col'); if(!col) return render();
    const idList = col.dataset.list;
    const prev = el.previousElementSibling, next = el.nextElementSibling;
    const posOf = n => n && n.dataset.card ? board.cards.find(x => x.id === n.dataset.card).pos : null;
    const a = posOf(prev), b = posOf(next);
    const pos = a === null && b === null ? 65536 : a === null ? b / 2 : b === null ? a + 65536 : (a + b) / 2;
    if(idList === c.idList && pos === c.pos) return;
    c.idList = idList; c.pos = pos;
    render();
    try{ await api('PUT', `/cards/${c.id}`, { idList, pos }); }catch(e){ fail(e); refresh(); }
  }
  window.addEventListener('pointermove', e => dragMove(e.clientX, e.clientY));
  window.addEventListener('pointerup', dragEnd);
  // Enquanto se arrasta, o dedo não faz scroll da página
  window.addEventListener('touchmove', e => { if(drag && e.cancelable) e.preventDefault(); }, { passive:false });

  /* ---------- Listas ---------- */
  async function renameList(l){
    const name = prompt('Nome da lista', l.name); if(!name || !name.trim() || name === l.name) return;
    try{ await api('PUT', `/lists/${l.id}`, { name:name.trim() }); l.name = name.trim(); render(); }catch(e){ fail(e); }
  }
  function listMenu(l){
    const i = board.lists.indexOf(l);
    sheet(l.name, [
      ['Mudar o nome', () => renameList(l)],
      i > 0 && ['Mover para a esquerda', () => moveList(l, i - 1)],
      i < board.lists.length - 1 && ['Mover para a direita', () => moveList(l, i + 1)],
      ['Arquivar lista', async () => {
        if(!confirm(`Arquivar a lista "${l.name}" e os seus cartões?`)) return;
        try{ await api('PUT', `/lists/${l.id}/closed`, { value:true }); board.lists.splice(i, 1); render(); }catch(e){ fail(e); }
      }],
    ]);
  }
  async function moveList(l, to){
    const others = board.lists.filter(x => x !== l);
    others.splice(to, 0, l);
    const a = others[to - 1], b = others[to + 1];
    const pos = !a ? b.pos / 2 : !b ? a.pos + 65536 : (a.pos + b.pos) / 2;
    l.pos = pos; board.lists = others; render();
    try{ await api('PUT', `/lists/${l.id}`, { pos }); }catch(e){ fail(e); refresh(); }
  }

  /* ---------- Folhas (menus) ---------- */
  function modal(html, cls = ''){
    const m = document.createElement('div'); m.className = 'modal show tr-modal';
    m.innerHTML = `<div class="sheet ${cls}">${html}</div>`;
    m.onclick = e => { if(e.target === m) close(m); };
    document.querySelector('.app').appendChild(m);
    return m;
  }
  function close(m){ m.remove(); if(m.dataset.card) openCardId = null; }
  function sheet(title, entries){
    const m = modal(`<h3>${esc(title)}</h3><div class="tr-list"></div><div class="actions"><button class="btn ghost">Fechar</button></div>`);
    entries.filter(Boolean).forEach(([text, fn]) => {
      const b = document.createElement('button'); b.className = 'tr-pick'; b.textContent = text;
      b.onclick = () => { close(m); fn(); };
      m.querySelector('.tr-list').appendChild(b);
    });
    m.querySelector('.actions .btn').onclick = () => close(m);
  }
  function boardMenu(){
    sheet('Quadros', boards.map(b => [b.name + (b.id === board.id ? '  ✓' : ''), async () => {
      try{ await loadBoard(b.id); labelFilter.clear(); dueFilter = ''; render(); }catch(e){ fail(e); }
    }]).concat([
      ['＋ Criar quadro', async () => {
        const name = prompt('Nome do quadro novo'); if(!name || !name.trim()) return;
        try{ const b = await api('POST', '/boards', { name:name.trim(), defaultLists:true }); await loadBoards(); await loadBoard(b.id); render(); }catch(e){ fail(e); }
      }],
      ['Abrir no Trello', () => openUrl(board.url)],
    ]));
  }
  function filterMenu(){
    const m = modal(`<h3>Filtrar</h3><div class="tr-sec"><b>Etiquetas</b><div id="trFl"></div></div>
      <div class="tr-sec"><b>Prazo</b><div id="trFd"></div></div>
      <div class="actions"><button class="btn ghost" id="trFclear">Limpar</button><button class="btn" id="trFok">Feito</button></div>`);
    const fl = m.querySelector('#trFl');
    board.labels.forEach(x => {
      const b = document.createElement('button'); b.className = 'tr-pick' + (labelFilter.has(x.id) ? ' on' : '');
      b.innerHTML = `<i style="background:${labelColor(x.color)}"></i>${esc(x.name || x.color || 'Sem cor')}`;
      b.onclick = () => { labelFilter.has(x.id) ? labelFilter.delete(x.id) : labelFilter.add(x.id); b.classList.toggle('on'); };
      fl.appendChild(b);
    });
    if(!board.labels.length) fl.innerHTML = '<p style="color:var(--muted);font-size:13px">Este quadro não tem etiquetas.</p>';
    const fd = m.querySelector('#trFd');
    [['late','Atrasados'],['week','Próximos 7 dias'],['none','Sem prazo']].forEach(([k, t]) => {
      const b = document.createElement('button'); b.className = 'tr-pick' + (dueFilter === k ? ' on' : ''); b.textContent = t;
      b.onclick = () => { dueFilter = dueFilter === k ? '' : k; fd.querySelectorAll('.tr-pick').forEach(n => n.classList.toggle('on', n === b && dueFilter === k)); };
      fd.appendChild(b);
    });
    m.querySelector('#trFclear').onclick = () => { labelFilter.clear(); dueFilter = ''; close(m); render(); };
    m.querySelector('#trFok').onclick = () => { close(m); render(); };
  }

  /* ---------- Calendário ---------- */
  function renderCalendar(area){
    area.style.background = 'var(--bg)';
    area.innerHTML = `<div style="padding:0 12px;overflow:auto;flex:1">
      <div class="cal-nav"><button id="trCp">‹</button><div class="mid"><h2 id="trCl"></h2><button class="today-btn" id="trCt">Hoje</button></div><button id="trCn">›</button></div>
      <div class="cal" id="trCal"><div class="weekdays"><div>Seg</div><div>Ter</div><div>Qua</div><div>Qui</div><div>Sex</div><div>Sáb</div><div>Dom</div></div><div class="days" id="trCd"></div></div></div>`;
    const due = board.cards.filter(c => c.due && passes(c));
    const byDay = key => due.filter(c => fmt(new Date(c.due)) === key);
    renderGrid($('trCd'), $('trCl'), calView, key => byDay(key).map(c => c.name), key => {
      const list = byDay(key);
      if(list.length === 1) openCard(list[0].id);
      else if(list.length) sheet(fmtPT(key), list.map(c => [c.name, () => openCard(c.id)]));
    });
    $('trCp').onclick = () => { calView.setMonth(calView.getMonth() - 1); render(); };
    $('trCn').onclick = () => { calView.setMonth(calView.getMonth() + 1); render(); };
    $('trCt').onclick = () => { calView = new Date(); calView.setDate(1); render(); };
  }

  /* ---------- Cartão aberto ---------- */
  async function openCard(id){
    document.querySelectorAll('.tr-modal').forEach(m => m.remove());
    openCardId = id;
    const m = modal('<div class="tr-msg">A abrir…</div>', 'tr-sheet');
    m.dataset.card = id;
    let card;
    try{
      card = await api('GET', `/cards/${id}`, {
        fields:'name,desc,due,dueComplete,idList,idLabels,pos,badges,shortUrl', checklists:'all', attachments:'true',
        attachment_fields:'name,url,date,isUpload', actions:'commentCard', actions_limit:'50', action_memberCreator_fields:'fullName,username',
      });
    }catch(e){ m.querySelector('.sheet').innerHTML = `<div class="tr-msg err">${esc(e.message)}</div>`; return; }
    if(openCardId !== id) return;
    const local = board.cards.find(c => c.id === id);
    const sync = patch => { if(local) Object.assign(local, patch); Object.assign(card, patch); };
    const put = async patch => { try{ await api('PUT', `/cards/${id}`, patch); sync(patch); render(); }catch(e){ fail(e); } };
    const rerender = () => draw();
    function draw(){
      const s = m.querySelector('.sheet');
      const labels = card.idLabels.map(x => board.labels.find(l => l.id === x)).filter(Boolean);
      const lists = board.lists.map(l => `<option value="${l.id}" ${l.id === card.idList ? 'selected' : ''}>${esc(l.name)}</option>`).join('');
      const dueLocal = card.due ? (() => { const d = new Date(card.due); d.setMinutes(d.getMinutes() - d.getTimezoneOffset()); return d.toISOString().slice(0,16); })() : '';
      s.innerHTML = `
        <div style="display:flex;gap:8px;align-items:flex-start">
          <textarea id="tcName" style="min-height:0;height:auto;font-size:18px;font-weight:600;flex:1" rows="2">${esc(card.name)}</textarea>
          <button class="icon-btn" id="tcClose" aria-label="Fechar">✕</button>
        </div>
        <div class="tr-sec"><b>Lista</b><select id="tcList">${lists}</select></div>
        <div class="tr-sec"><b>Etiquetas</b><div class="tr-chips" id="tcLabels">${labels.map(x =>
          `<span class="tr-chip" style="background:${labelColor(x.color)};color:#1d2125">${esc(x.name || ' ')}</span>`).join('')}<button class="tr-chip" id="tcLabelBtn">＋</button></div></div>
        <div class="tr-sec"><b>Prazo ${card.due ? `<small>${dueFull(card.due)}</small>` : ''}</b>
          <div class="row" style="margin-top:0;align-items:center">
            <input type="datetime-local" id="tcDue" value="${dueLocal}">
            ${card.due ? '<button class="btn ghost" id="tcDueX">Tirar</button>' : ''}
          </div>
          ${card.due ? `<label class="tr-item ${card.dueComplete ? 'done' : ''}"><input type="checkbox" id="tcDone" ${card.dueComplete ? 'checked' : ''}><span>Concluído</span></label>` : ''}
        </div>
        <div class="tr-sec"><b>Descrição</b><textarea id="tcDesc" placeholder="Acrescenta uma descrição…">${esc(card.desc)}</textarea></div>
        <div id="tcChecklists"></div>
        <div class="tr-sec"><button class="tr-chip" id="tcAddChecklist">☑ Adicionar checklist</button></div>
        <div class="tr-sec"><b>Anexos</b><div id="tcAtts"></div>
          <div class="row"><input type="url" id="tcLink" placeholder="Colar um link"><button class="btn ghost" id="tcLinkAdd">Juntar</button></div>
          <label class="tr-chip" style="display:inline-block;margin-top:8px">📎 Juntar ficheiro<input type="file" id="tcFile" style="display:none"></label>
        </div>
        <div class="tr-sec"><b>Comentários</b>
          <textarea id="tcComment" placeholder="Escreve um comentário…" style="min-height:60px"></textarea>
          <div class="actions" style="margin-top:6px"><button class="btn" id="tcSend">Comentar</button></div>
          <div id="tcComments"></div>
        </div>
        <div class="actions" style="justify-content:space-between">
          <button class="btn ghost" id="tcOpen">Abrir no Trello</button>
          <button class="btn danger" id="tcArchive">Arquivar</button>
        </div>`;
      s.querySelector('#tcClose').onclick = () => close(m);
      s.querySelector('#tcName').onchange = e => { const v = e.target.value.trim(); if(v && v !== card.name) put({ name:v }); };
      s.querySelector('#tcList').onchange = e => put({ idList:e.target.value, pos:'bottom' }).then(() => { if(local) local.pos = Date.now(); render(); });
      s.querySelector('#tcLabelBtn').onclick = labelPicker;
      s.querySelector('#tcDue').onchange = e => { if(e.target.value) put({ due:new Date(e.target.value).toISOString() }).then(rerender); };
      const dx = s.querySelector('#tcDueX'); if(dx) dx.onclick = () => put({ due:null, dueComplete:false }).then(() => { card.due = null; if(local) local.due = null; rerender(); });
      const dn = s.querySelector('#tcDone'); if(dn) dn.onchange = e => put({ dueComplete:e.target.checked }).then(rerender);
      s.querySelector('#tcDesc').onchange = e => put({ desc:e.target.value });
      drawChecklists(s.querySelector('#tcChecklists'));
      s.querySelector('#tcAddChecklist').onclick = async () => {
        const name = prompt('Nome da checklist', 'Checklist'); if(!name || !name.trim()) return;
        try{ const cl = await api('POST', `/cards/${id}/checklists`, { name:name.trim() }); card.checklists.push(cl); rerender(); }catch(e){ fail(e); }
      };
      drawAttachments(s.querySelector('#tcAtts'));
      s.querySelector('#tcLinkAdd').onclick = async () => {
        const url = s.querySelector('#tcLink').value.trim(); if(!url) return;
        try{ const a = await api('POST', `/cards/${id}/attachments`, { url }); card.attachments.push(a); bumpBadge('attachments', 1); rerender(); }catch(e){ fail(e); }
      };
      s.querySelector('#tcFile').onchange = async e => {
        const f = e.target.files[0]; if(!f) return;
        const form = new FormData(); form.append('file', f); form.append('name', f.name);
        try{ const a = await api('POST', `/cards/${id}/attachments`, {}, form); card.attachments.push(a); bumpBadge('attachments', 1); rerender(); }catch(err){ fail(err); }
      };
      drawComments(s.querySelector('#tcComments'));
      s.querySelector('#tcSend').onclick = async () => {
        const text = s.querySelector('#tcComment').value.trim(); if(!text) return;
        try{
          const a = await api('POST', `/cards/${id}/actions/comments`, { text });
          card.actions.unshift(a); bumpBadge('comments', 1); rerender();
        }catch(e){ fail(e); }
      };
      s.querySelector('#tcOpen').onclick = () => openUrl(card.shortUrl);
      s.querySelector('#tcArchive').onclick = async () => {
        if(!confirm('Arquivar este cartão?')) return;
        try{ await api('PUT', `/cards/${id}`, { closed:true }); board.cards = board.cards.filter(c => c.id !== id); close(m); render(); }catch(e){ fail(e); }
      };
    }
    function bumpBadge(k, n){ if(local){ local.badges[k] = (local.badges[k] || 0) + n; render(); } }
    function syncChecks(){
      if(!local) return;
      const items = card.checklists.flatMap(cl => cl.checkItems);
      local.badges.checkItems = items.length;
      local.badges.checkItemsChecked = items.filter(i => i.state === 'complete').length;
      render();
    }
    function drawChecklists(box){
      card.checklists.sort((a,b) => a.pos - b.pos).forEach(cl => {
        const done = cl.checkItems.filter(i => i.state === 'complete').length, total = cl.checkItems.length;
        const sec = document.createElement('div'); sec.className = 'tr-sec';
        sec.innerHTML = `<b>☑ ${esc(cl.name)} <small>${done}/${total}</small><span style="flex:1"></span><button class="tr-mini" aria-label="Apagar checklist">🗑️</button></b>
          <div class="tr-bar"><div style="width:${total ? done / total * 100 : 0}%"></div></div><div class="tr-its"></div>
          <div class="row"><input type="text" placeholder="Adicionar item"><button class="btn ghost">＋</button></div>`;
        sec.querySelector('b button').onclick = async () => {
          if(!confirm(`Apagar a checklist "${cl.name}"?`)) return;
          try{ await api('DELETE', `/checklists/${cl.id}`); card.checklists = card.checklists.filter(x => x !== cl); syncChecks(); rerender(); }catch(e){ fail(e); }
        };
        cl.checkItems.sort((a,b) => a.pos - b.pos).forEach(it => {
          const row = document.createElement('label'); row.className = 'tr-item' + (it.state === 'complete' ? ' done' : '');
          row.innerHTML = `<input type="checkbox" ${it.state === 'complete' ? 'checked' : ''}><span>${esc(it.name)}</span><button class="tr-mini" aria-label="Apagar item">✕</button>`;
          row.querySelector('input').onchange = async e => {
            const state = e.target.checked ? 'complete' : 'incomplete';
            try{ await api('PUT', `/cards/${id}/checkItem/${it.id}`, { state }); it.state = state; syncChecks(); rerender(); }catch(err){ fail(err); }
          };
          row.querySelector('button').onclick = async e => {
            e.preventDefault();
            try{ await api('DELETE', `/checklists/${cl.id}/checkItems/${it.id}`); cl.checkItems = cl.checkItems.filter(x => x !== it); syncChecks(); rerender(); }catch(err){ fail(err); }
          };
          sec.querySelector('.tr-its').appendChild(row);
        });
        const input = sec.querySelector('.row input');
        const addItem = async () => {
          const name = input.value.trim(); if(!name) return;
          try{ const it = await api('POST', `/checklists/${cl.id}/checkItems`, { name, pos:'bottom' }); cl.checkItems.push(it); syncChecks(); rerender(); }catch(e){ fail(e); }
        };
        sec.querySelector('.row button').onclick = addItem;
        input.onkeydown = e => { if(e.key === 'Enter') addItem(); };
        box.appendChild(sec);
      });
    }
    function drawAttachments(box){
      if(!card.attachments.length){ box.innerHTML = '<p style="color:var(--muted);font-size:13px">Sem anexos.</p>'; return; }
      card.attachments.forEach(a => {
        const row = document.createElement('div'); row.className = 'tr-att';
        row.innerHTML = `<span>${a.isUpload ? '📄' : '🔗'}</span><a>${esc(a.name || a.url)}</a><button class="tr-mini" aria-label="Apagar anexo">✕</button>`;
        row.querySelector('a').onclick = () => openUrl(a.url);
        row.querySelector('button').onclick = async () => {
          if(!confirm('Apagar este anexo?')) return;
          try{ await api('DELETE', `/cards/${id}/attachments/${a.id}`); card.attachments = card.attachments.filter(x => x !== a); bumpBadge('attachments', -1); rerender(); }catch(e){ fail(e); }
        };
        box.appendChild(row);
      });
    }
    function drawComments(box){
      card.actions.filter(a => a.type === 'commentCard').forEach(a => {
        const div = document.createElement('div'); div.className = 'tr-comment';
        const who = a.memberCreator ? (a.memberCreator.fullName || a.memberCreator.username) : '';
        div.innerHTML = `<small>${esc(who)} · ${new Date(a.date).toLocaleString('pt-PT', { day:'numeric', month:'short', hour:'2-digit', minute:'2-digit' })}</small>${esc(a.data.text)}`;
        box.appendChild(div);
      });
    }
    function labelPicker(){
      const p = modal(`<h3>Etiquetas</h3><div id="tlp"></div>
        <div class="row"><input type="text" id="tlpName" placeholder="Nova etiqueta"><button class="btn ghost" id="tlpAdd">Criar</button></div>
        <div class="actions"><button class="btn">Feito</button></div>`);
      const box = p.querySelector('#tlp');
      const drawPick = () => {
        box.innerHTML = '';
        board.labels.forEach(x => {
          const on = card.idLabels.includes(x.id);
          const b = document.createElement('button'); b.className = 'tr-pick' + (on ? ' on' : '');
          b.innerHTML = `<i style="background:${labelColor(x.color)}"></i>${esc(x.name || x.color || 'Sem cor')}`;
          b.onclick = async () => {
            try{
              if(on) await api('DELETE', `/cards/${id}/idLabels/${x.id}`); else await api('POST', `/cards/${id}/idLabels`, { value:x.id });
              const ids = on ? card.idLabels.filter(v => v !== x.id) : card.idLabels.concat(x.id);
              sync({ idLabels:ids }); render(); drawPick();
            }catch(e){ fail(e); }
          };
          box.appendChild(b);
        });
      };
      drawPick();
      p.querySelector('#tlpAdd').onclick = async () => {
        const name = p.querySelector('#tlpName').value.trim(); if(!name) return;
        const colors = ['green','yellow','orange','red','purple','blue','sky','lime','pink'];
        try{
          const l = await api('POST', '/labels', { idBoard:board.id, name, color:colors[board.labels.length % colors.length] });
          board.labels.push(l); p.querySelector('#tlpName').value = ''; drawPick();
        }catch(e){ fail(e); }
      };
      p.querySelector('.actions .btn').onclick = () => { close(p); rerender(); };
    }
    draw();
  }

  // Voltar do Android: fecha primeiro as folhas do Trello
  function back(){
    const m = [...document.querySelectorAll('.tr-modal')].pop();
    if(m){ close(m); return true; }
    if(current === 'trello' && view === 'calendar'){ view = 'board'; render(); return true; }
    return false;
  }

  return { show, back, hasLogin, setLogin };
})();
