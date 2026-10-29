// Ponte entre o site do Aoi e a extensão. Corre só nas páginas do site.
// 1) Deixa uma marca na página (data-aoi-extension = versão) para o site saber que a extensão está instalada.
// 2) Passa mensagens: o site pergunta o estado (aoi-ext-hello), dá um token novo (aoi-ext-connect)
//    ou pede para desligar (aoi-ext-disconnect); a resposta volta como aoi-ext-status.

document.documentElement.dataset.aoiExtension = chrome.runtime.getManifest().version;

/**
 * Pede uma coisa ao background e devolve o resultado (ou null se falhar).
 * Recebe: a mensagem ({ type, ... }).
 */
async function askBackground(message) {
  const reply = await chrome.runtime.sendMessage(message);
  if (!reply || !reply.ok) {
    return null;
  }
  return reply.result;
}

/** Manda o estado da extensão ao site (versão, se está ligada e a que conta). */
async function sendStatusToSite() {
  const status = await askBackground({ type: 'status' });
  window.postMessage({ source: 'aoi-extension', type: 'aoi-ext-status', status: status }, window.location.origin);
}

window.addEventListener('message', async (event) => {
  // Só mensagens da própria página do Aoi (não de iframes nem de outros sites)
  const comesFromThisPage = (event.source === window && event.origin === window.location.origin);
  const isForExtension = (event.data && event.data.source === 'aoi-site');
  if (!comesFromThisPage || !isForExtension) {
    return;
  }

  if (event.data.type === 'aoi-ext-hello') {
    await sendStatusToSite();
    return;
  }

  if (event.data.type === 'aoi-ext-connect') {
    await askBackground({
      type: 'connect',
      token: event.data.token,
      userId: event.data.userId,
      username: event.data.username,
    });
    await sendStatusToSite();
    return;
  }

  if (event.data.type === 'aoi-ext-disconnect') {
    await askBackground({ type: 'disconnect' });
    await sendStatusToSite();
  }
});
