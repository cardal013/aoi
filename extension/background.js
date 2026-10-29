// Service worker da extensão: guarda o token que o site do Aoi deu e fala com o Supabase.
// O overlay (nas páginas das fontes), a ponte com o site e o popup pedem-lhe tudo por mensagens
// (chrome.runtime.sendMessage), porque só aqui há permissão para chamar o Supabase.
// As funções SQL chamadas estão em supabase/migrations/20261029_chrome_extension.sql.

importScripts('config.js');

// De quanto em quanto tempo se voltam a pedir os sites das fontes da biblioteca
const HOSTS_MAX_AGE_MS = 30 * 60 * 1000;
// Código que as funções SQL devolvem quando o token já não vale (apagado ou substituído)
const INVALID_TOKEN_CODE = '28000';

/**
 * Lê o que está guardado na extensão (token, conta, sites das fontes).
 * Devolve: { token, userId, username, hosts, hostsAt } (campos em falta ficam undefined)
 */
async function readSaved() {
  return chrome.storage.local.get(['token', 'userId', 'username', 'hosts', 'hostsAt']);
}

/**
 * Chama uma função SQL do Supabase (POST /rest/v1/rpc/<nome>) como anon, só com a chave pública.
 * Recebe: nome da função e os parâmetros. Devolve: a resposta já em JSON.
 * Se o token já não valer, apaga-o (a extensão fica desligada até se abrir o site outra vez).
 */
async function callRpc(functionName, parameters) {
  const response = await fetch(`${AOI_SUPABASE_URL}/rest/v1/rpc/${functionName}`, {
    method: 'POST',
    headers: { apikey: AOI_SUPABASE_KEY, 'Content-Type': 'application/json' },
    body: JSON.stringify(parameters),
  });
  const body = await response.json().catch(() => null);

  if (response.ok) {
    return body;
  }

  const tokenIsInvalid = (body && body.code === INVALID_TOKEN_CODE);
  if (tokenIsInvalid) {
    await chrome.storage.local.remove(['token', 'userId', 'username', 'hosts', 'hostsAt']);
  }
  throw new Error((body && body.message) || `HTTP ${response.status}`);
}

/**
 * Sites (host, ex.: "mangafire.to") das fontes da biblioteca. Usa a cópia guardada se for recente.
 * Devolve: lista de hosts (vazia se a extensão não estiver ligada).
 */
async function getHosts() {
  const saved = await readSaved();
  if (!saved.token) {
    return [];
  }

  const copyIsRecent = (saved.hosts && saved.hostsAt && Date.now() - saved.hostsAt < HOSTS_MAX_AGE_MS);
  if (copyIsRecent) {
    return saved.hosts;
  }

  const hosts = await callRpc('aoi_ext_hosts', { p_token: saved.token });
  await chrome.storage.local.set({ hosts: hosts, hostsAt: Date.now() });
  return hosts;
}

/** Guarda o token que o site criou para esta extensão (e a conta a que pertence). */
async function connect(message) {
  await chrome.storage.local.set({
    token: message.token,
    userId: message.userId,
    username: message.username,
    hosts: null,
    hostsAt: 0,
  });
  return { connected: true };
}

/** Desliga a extensão: apaga o token no Supabase (se der) e na extensão. */
async function disconnect() {
  const saved = await readSaved();
  if (saved.token) {
    await callRpc('aoi_ext_revoke', { p_token: saved.token }).catch(() => null);
  }
  await chrome.storage.local.remove(['token', 'userId', 'username', 'hosts', 'hostsAt']);
  return { connected: false };
}

/** Estado da ligação, para o site e para o popup. */
async function getStatus() {
  const saved = await readSaved();
  return {
    version: chrome.runtime.getManifest().version,
    connected: Boolean(saved.token),
    userId: saved.userId || null,
    username: saved.username || null,
  };
}

/**
 * Estado do capítulo aberto na página (ou null se não for um capítulo da biblioteca).
 * Só pergunta ao Supabase se o site da página for o de uma fonte da biblioteca.
 */
async function getChapterState(message) {
  const saved = await readSaved();
  if (!saved.token) {
    return null;
  }

  const pageHost = new URL(message.url).hostname.replace(/^www\./, '');
  const hosts = await getHosts();
  if (!hosts.includes(pageHost)) {
    return null;
  }

  return callRpc('aoi_ext_chapter_state', { p_token: saved.token, p_url: message.url });
}

/** Marca ou desmarca um capítulo como lido. Devolve o estado novo do capítulo. */
async function setChapterRead(message) {
  const saved = await readSaved();
  if (!saved.token) {
    throw new Error('Not connected');
  }
  return callRpc('aoi_ext_set_read', {
    p_token: saved.token,
    p_chapter_id: message.chapterId,
    p_read: message.read,
  });
}

// Cada tipo de mensagem e a função que lhe responde
const HANDLERS = {
  'connect': connect,
  'disconnect': disconnect,
  'status': getStatus,
  'chapter-state': getChapterState,
  'set-read': setChapterRead,
};

// Responde às mensagens. Devolve sempre { ok, result } ou { ok: false, error } para o lado de lá saber se falhou.
chrome.runtime.onMessage.addListener((message, sender, sendResponse) => {
  const handler = HANDLERS[message && message.type];
  if (!handler) {
    return false;
  }

  handler(message)
    .then((result) => sendResponse({ ok: true, result: result }))
    .catch((error) => sendResponse({ ok: false, error: String(error.message || error) }));

  // true = a resposta vem mais tarde (a função é assíncrona)
  return true;
});
