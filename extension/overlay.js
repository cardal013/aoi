// Overlay do Aoi nas páginas dos capítulos das fontes (MangaFire, Webtoons, ...).
// Corre em todas as páginas, mas só aparece se a página for um capítulo de um manga da biblioteca
// (quem decide é o background, com o Supabase). Fica minimizado numa pílula ("Ch. 47 · 13 left");
// ao clicar abre o cartão com o capítulo, a página, "Mark read" e "Next". Arrasta-se para qualquer canto.
// Marca o capítulo como lido sozinho quando se chega à última página, ou ao carregar em Next.
// Fora da biblioteca não mostra nada.

(function startAoiOverlay() {
  // De quanto em quanto tempo se vê se o URL mudou (leitores que mudam de capítulo sem recarregar a página)
  const URL_CHECK_INTERVAL_MS = 1000;
  // Tempo mínimo no capítulo antes de o marcar sozinho (evita marcar ao abrir e saltar logo para o fim)
  const MIN_TIME_BEFORE_AUTO_MARK_MS = 10 * 1000;
  // Distância ao fim da última página a partir da qual já conta como "chegou ao fim"
  const END_MARGIN_PX = 80;
  // Uma imagem conta como página do capítulo se tiver pelo menos esta largura (fração do ecrã) e altura
  const MIN_PAGE_WIDTH_RATIO = 0.35;
  const MIN_PAGE_HEIGHT_PX = 200;
  // Com menos páginas do que isto na mesma página web, o leitor é página a página: aí só o Next marca
  const MIN_PAGES_FOR_SCROLL_READER = 3;
  // Mexer o rato mais do que isto com o botão carregado conta como arrastar (e não como clique)
  const DRAG_THRESHOLD_PX = 5;
  // Distância mínima do overlay às margens do ecrã
  const SCREEN_EDGE_PX = 8;
  const DEFAULT_POSITION = { right: 16, bottom: 16 };
  const POSITION_STORAGE_KEY = 'overlayPosition';

  let chapterState = null;
  let currentUrl = window.location.href;
  let chapterOpenedAt = Date.now();
  let userHasScrolled = false;
  let unmarkedByHand = false;
  let isExpanded = false;
  let isSaving = false;
  let statusMessage = '';
  let ui = null;

  /**
   * Pede uma coisa ao background (que fala com o Supabase).
   * Recebe: a mensagem ({ type, ... }). Devolve: o resultado. Lança erro se falhar.
   */
  async function askBackground(message) {
    const reply = await chrome.runtime.sendMessage(message);
    if (!reply || !reply.ok) {
      throw new Error((reply && reply.error) || 'No reply');
    }
    return reply.result;
  }

  /** Mostra um número de capítulo sem zeros a mais (45, 45.5). */
  function formatChapterNumber(number) {
    return String(Number(number));
  }

  // ---------- Construir o overlay ----------

  /**
   * Cria o overlay dentro de um Shadow DOM (o CSS do site não lhe mexe e o nosso não mexe no site).
   * Devolve: as referências aos elementos que se atualizam.
   */
  function createOverlay() {
    const host = document.createElement('div');
    host.id = 'aoi-extension-overlay';
    const shadow = host.attachShadow({ mode: 'closed' });

    shadow.innerHTML = `
      <style>
        :host { all: initial; }
        .box { position: fixed; z-index: 2147483647; font: 13px/1.4 system-ui, sans-serif;
          --blue: #0253D2; --card: #ffffff; --text: #1c1c1a; --muted: #6b6a65; --border: #d8d6cf; --green: #2e7d32; }
        @media (prefers-color-scheme: dark) {
          .box { --card: #242422; --text: #eeeeea; --muted: #a3a29c; --border: #3a3a37; --green: #7bc67e; }
        }
        .hidden { display: none !important; }
        .logo { width: 22px; height: 22px; border-radius: 6px; background: var(--blue); color: #fff; flex: none;
          display: flex; align-items: center; justify-content: center; font-size: 12px; }
        .pill { display: flex; align-items: center; gap: 8px; padding: 5px 12px 5px 5px; border-radius: 999px;
          background: var(--card); color: var(--text); border: 1px solid var(--border); cursor: grab;
          font: inherit; user-select: none; }
        .card { width: 240px; padding: 12px 14px; border-radius: 12px; background: var(--card); color: var(--text);
          border: 1px solid var(--border); }
        .head { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; font-weight: 500; cursor: grab;
          user-select: none; }
        .title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
        .minimize { margin-left: auto; border: none; background: none; color: var(--muted); font-size: 18px;
          line-height: 1; cursor: pointer; padding: 0 2px; }
        .muted { color: var(--muted); font-size: 12px; }
        .read { color: var(--green); }
        .bar { height: 4px; border-radius: 2px; background: var(--border); margin: 8px 0 4px; }
        .bar div { height: 100%; width: 0; border-radius: 2px; background: var(--blue); }
        .buttons { display: flex; gap: 8px; margin-top: 10px; }
        .buttons button { flex: 1; padding: 7px 0; border-radius: 6px; font: inherit; font-size: 12px; cursor: pointer;
          background: transparent; color: var(--text); border: 1px solid var(--border); }
        .buttons .primary { background: var(--blue); border-color: var(--blue); color: #fff; }
        .buttons button:disabled { opacity: .5; cursor: default; }
        .status { margin-top: 6px; }
      </style>
      <div class="box hidden">
        <button class="pill" type="button"><span class="logo">青</span><span class="pill-text"></span></button>
        <div class="card hidden">
          <div class="head">
            <span class="logo">青</span><span class="title"></span>
            <button class="minimize" type="button" aria-label="Minimize">–</button>
          </div>
          <div><span class="chapter-name"></span> <span class="muted chapter-read"></span></div>
          <div class="bar"><div></div></div>
          <div class="muted progress-text"></div>
          <div class="buttons">
            <button type="button" class="mark">Mark read</button>
            <button type="button" class="primary next">Next →</button>
          </div>
          <div class="muted status hidden"></div>
        </div>
      </div>`;

    document.documentElement.appendChild(host);

    return {
      box: shadow.querySelector('.box'),
      pill: shadow.querySelector('.pill'),
      pillText: shadow.querySelector('.pill-text'),
      card: shadow.querySelector('.card'),
      head: shadow.querySelector('.head'),
      title: shadow.querySelector('.title'),
      minimize: shadow.querySelector('.minimize'),
      chapterName: shadow.querySelector('.chapter-name'),
      chapterRead: shadow.querySelector('.chapter-read'),
      progressBar: shadow.querySelector('.bar div'),
      progressText: shadow.querySelector('.progress-text'),
      markButton: shadow.querySelector('.mark'),
      nextButton: shadow.querySelector('.next'),
      status: shadow.querySelector('.status'),
    };
  }

  // ---------- Páginas do capítulo e progresso ----------

  /**
   * Imagens grandes da página web, por ordem: são as páginas do capítulo.
   * Devolve: lista de elementos (img ou canvas).
   */
  function findPageImages() {
    const minimumWidth = window.innerWidth * MIN_PAGE_WIDTH_RATIO;
    const pages = [];

    for (const element of document.querySelectorAll('img, canvas')) {
      const size = element.getBoundingClientRect();
      const isBigEnough = (size.width >= minimumWidth && size.height >= MIN_PAGE_HEIGHT_PX);
      if (isBigEnough) {
        pages.push(element);
      }
    }
    return pages;
  }

  /**
   * Em que página se está (a que ocupa o meio do ecrã) e quantas há.
   * Devolve: { current, total } ou null se a página web não tiver imagens de capítulo.
   */
  function getPageProgress() {
    const pages = findPageImages();
    if (pages.length === 0) {
      return null;
    }

    const middleOfScreen = window.innerHeight / 2;
    let current = 1;
    for (let index = 0; index < pages.length; index++) {
      const top = pages[index].getBoundingClientRect().top;
      if (top <= middleOfScreen) {
        current = index + 1;
      }
    }
    return { current: current, total: pages.length };
  }

  /**
   * Diz se já se chegou ao fim do capítulo: o fundo da última página está à vista.
   * Só conta em leitores com scroll (várias páginas na mesma página web).
   */
  function hasReachedEndOfChapter() {
    const pages = findPageImages();
    const isScrollReader = (pages.length >= MIN_PAGES_FOR_SCROLL_READER);
    if (!isScrollReader) {
      return false;
    }

    const lastPage = pages[pages.length - 1];
    const lastPageBottom = lastPage.getBoundingClientRect().bottom;
    return lastPageBottom <= window.innerHeight + END_MARGIN_PX;
  }

  // ---------- Desenhar ----------

  /** Atualiza a pílula e o cartão com o estado do capítulo. */
  function render() {
    if (!chapterState) {
      ui.box.classList.add('hidden');
      return;
    }

    const chapter = chapterState.chapter;
    const unreadText = `${chapterState.unread} left`;
    ui.box.classList.remove('hidden');
    ui.pillText.textContent = `Ch. ${formatChapterNumber(chapter.number)} · ${unreadText}`;
    ui.title.textContent = chapterState.title;
    ui.chapterName.textContent = chapter.name || `Chapter ${formatChapterNumber(chapter.number)}`;

    if (chapterState.read) {
      ui.chapterRead.textContent = '· read ✓';
      ui.chapterRead.className = 'muted chapter-read read';
    } else {
      ui.chapterRead.textContent = '· not read yet';
      ui.chapterRead.className = 'muted chapter-read';
    }

    renderProgress();

    ui.markButton.textContent = chapterState.read ? 'Mark unread' : 'Mark read';
    ui.markButton.disabled = isSaving;
    ui.nextButton.disabled = isSaving || !chapterState.next;
    ui.nextButton.title = chapterState.next ? '' : "The next chapter isn't in Aoi yet. Update the manga on your phone.";

    ui.status.textContent = statusMessage;
    ui.status.classList.toggle('hidden', statusMessage === '');

    ui.pill.classList.toggle('hidden', isExpanded);
    ui.card.classList.toggle('hidden', !isExpanded);
  }

  /** Barra e texto do progresso: "Page 7 of 18 · 14 unread" (ou percentagem da página, sem imagens). */
  function renderProgress() {
    const progress = getPageProgress();
    const unreadText = `${chapterState.unread} unread`;

    if (progress) {
      const percentage = Math.round((progress.current / progress.total) * 100);
      ui.progressBar.style.width = `${percentage}%`;
      ui.progressText.textContent = `Page ${progress.current} of ${progress.total} · ${unreadText}`;
      return;
    }

    const scrollableHeight = document.documentElement.scrollHeight - window.innerHeight;
    let percentage = 100;
    if (scrollableHeight > 0) {
      percentage = Math.round((window.scrollY / scrollableHeight) * 100);
    }
    ui.progressBar.style.width = `${percentage}%`;
    ui.progressText.textContent = unreadText;
  }

  // ---------- Ações ----------

  /**
   * Marca ou desmarca o capítulo atual na cloud e atualiza o overlay com a resposta.
   * Devolve: true se gravou.
   */
  async function saveRead(read) {
    isSaving = true;
    statusMessage = '';
    render();

    let saved = false;
    try {
      chapterState = await askBackground({ type: 'set-read', chapterId: chapterState.chapter.id, read: read });
      saved = true;
    } catch (error) {
      statusMessage = "Couldn't save. Try again.";
    }

    isSaving = false;
    render();
    return saved;
  }

  /** Botão Mark read / Mark unread. Desmarcar à mão desliga a marcação automática neste capítulo. */
  async function toggleRead() {
    const willMarkRead = !chapterState.read;
    if (!willMarkRead) {
      unmarkedByHand = true;
    }
    await saveRead(willMarkRead);
  }

  /** Botão Next: marca o atual como lido (se ainda não estiver) e abre o seguinte. */
  async function goToNextChapter() {
    const next = chapterState.next;
    if (!next) {
      return;
    }

    if (!chapterState.read) {
      const saved = await saveRead(true);
      if (!saved) {
        return;
      }
    }
    window.location.href = next.web_url;
  }

  /** Marca sozinho quando se chega à última página (uma vez por capítulo, e nunca contra um "Mark unread"). */
  async function autoMarkIfFinished() {
    if (!chapterState || chapterState.read || isSaving || unmarkedByHand || !userHasScrolled) {
      return;
    }

    const spentEnoughTime = (Date.now() - chapterOpenedAt >= MIN_TIME_BEFORE_AUTO_MARK_MS);
    if (spentEnoughTime && hasReachedEndOfChapter()) {
      await saveRead(true);
    }
  }

  // ---------- Capítulo da página ----------

  /** Pergunta ao background se a página atual é um capítulo da biblioteca e mostra (ou esconde) o overlay. */
  async function loadChapterForPage() {
    chapterOpenedAt = Date.now();
    userHasScrolled = false;
    unmarkedByHand = false;
    statusMessage = '';

    try {
      chapterState = await askBackground({ type: 'chapter-state', url: window.location.href });
    } catch (error) {
      chapterState = null;
    }
    render();
  }

  /** Leitores que mudam de capítulo sem recarregar a página: quando o URL muda, volta a perguntar. */
  function watchUrlChanges() {
    setInterval(() => {
      const urlChanged = (window.location.href !== currentUrl);
      if (urlChanged) {
        currentUrl = window.location.href;
        loadChapterForPage();
      }
    }, URL_CHECK_INTERVAL_MS);
  }

  // ---------- Arrastar e posição ----------

  /** Põe o overlay na posição guardada (distância à direita e ao fundo do ecrã). */
  async function restorePosition() {
    const saved = await chrome.storage.local.get(POSITION_STORAGE_KEY);
    const position = saved[POSITION_STORAGE_KEY] || DEFAULT_POSITION;
    applyPosition(position);
  }

  /** Mete o overlay a { right, bottom } píxeis das margens, sem o deixar sair do ecrã. */
  function applyPosition(position) {
    const maximumRight = window.innerWidth - ui.box.offsetWidth - SCREEN_EDGE_PX;
    const maximumBottom = window.innerHeight - ui.box.offsetHeight - SCREEN_EDGE_PX;
    const right = Math.min(Math.max(position.right, SCREEN_EDGE_PX), Math.max(maximumRight, SCREEN_EDGE_PX));
    const bottom = Math.min(Math.max(position.bottom, SCREEN_EDGE_PX), Math.max(maximumBottom, SCREEN_EDGE_PX));
    ui.box.style.right = `${right}px`;
    ui.box.style.bottom = `${bottom}px`;
  }

  /**
   * Deixa arrastar o overlay pela pílula ou pelo cabeçalho do cartão.
   * Um clique sem arrastar na pílula abre o cartão. A posição fica guardada para as outras páginas.
   */
  function enableDragging(handle, onClick) {
    handle.addEventListener('pointerdown', (downEvent) => {
      const clickedMinimize = (downEvent.target === ui.minimize);
      if (downEvent.button !== 0 || clickedMinimize) {
        return;
      }

      const startX = downEvent.clientX;
      const startY = downEvent.clientY;
      const startRight = parseFloat(ui.box.style.right) || DEFAULT_POSITION.right;
      const startBottom = parseFloat(ui.box.style.bottom) || DEFAULT_POSITION.bottom;
      let isDragging = false;
      handle.setPointerCapture(downEvent.pointerId);

      function onMove(moveEvent) {
        const movedX = moveEvent.clientX - startX;
        const movedY = moveEvent.clientY - startY;
        if (!isDragging && Math.hypot(movedX, movedY) > DRAG_THRESHOLD_PX) {
          isDragging = true;
        }
        if (isDragging) {
          applyPosition({ right: startRight - movedX, bottom: startBottom - movedY });
        }
      }

      function onUp() {
        handle.removeEventListener('pointermove', onMove);
        handle.removeEventListener('pointerup', onUp);
        if (isDragging) {
          const position = { right: parseFloat(ui.box.style.right), bottom: parseFloat(ui.box.style.bottom) };
          chrome.storage.local.set({ [POSITION_STORAGE_KEY]: position });
        } else if (onClick) {
          onClick();
        }
      }

      handle.addEventListener('pointermove', onMove);
      handle.addEventListener('pointerup', onUp);
    });
  }

  /** Abre o cartão ou volta à pílula, sem deixar o overlay sair do ecrã (o cartão é maior). */
  function setExpanded(expanded) {
    isExpanded = expanded;
    render();
    applyPosition({ right: parseFloat(ui.box.style.right), bottom: parseFloat(ui.box.style.bottom) });
  }

  // ---------- Arranque ----------

  ui = createOverlay();
  enableDragging(ui.pill, () => setExpanded(true));
  enableDragging(ui.head, null);
  ui.minimize.addEventListener('click', () => setExpanded(false));
  ui.markButton.addEventListener('click', toggleRead);
  ui.nextButton.addEventListener('click', goToNextChapter);

  window.addEventListener('scroll', () => {
    userHasScrolled = true;
    if (chapterState) {
      renderProgress();
      autoMarkIfFinished();
    }
  }, { passive: true });
  window.addEventListener('resize', () => {
    applyPosition({ right: parseFloat(ui.box.style.right), bottom: parseFloat(ui.box.style.bottom) });
  });

  restorePosition();
  loadChapterForPage();
  watchUrlChanges();
})();
