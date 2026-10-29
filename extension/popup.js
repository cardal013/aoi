// Popup do ícone da extensão: mostra a conta ligada (ou como ligar) e os botões Open Aoi e Disconnect.

/** Pede uma coisa ao background e devolve o resultado (ou null se falhar). */
async function askBackground(message) {
  const reply = await chrome.runtime.sendMessage(message);
  if (!reply || !reply.ok) {
    return null;
  }
  return reply.result;
}

/** Escreve o estado da ligação no popup e mostra o Disconnect só quando está ligada. */
async function showStatus() {
  const status = await askBackground({ type: 'status' });
  const statusText = document.getElementById('statusText');
  const disconnectButton = document.getElementById('disconnect');

  document.getElementById('version').textContent = status ? `v${status.version}` : '';

  const isConnected = Boolean(status && status.connected);
  if (isConnected) {
    statusText.textContent = `Connected as ${status.username || 'your account'}. Open a chapter of a manga in your library and Aoi shows up in the corner.`;
  } else {
    statusText.textContent = 'Not connected. Open the Aoi site and sign in: the extension connects by itself.';
  }
  disconnectButton.classList.toggle('hidden', !isConnected);
}

document.getElementById('openSite').addEventListener('click', () => {
  chrome.tabs.create({ url: AOI_SITE_URL });
});

document.getElementById('disconnect').addEventListener('click', async () => {
  await askBackground({ type: 'disconnect' });
  await showStatus();
});

showStatus();
