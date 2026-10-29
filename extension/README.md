# Extensão do Aoi para o Chrome

Mostra um overlay nas páginas dos capítulos das fontes (MangaFire, Webtoons, ...) dos mangas da biblioteca do Aoi e marca os capítulos lidos na cloud. O telemóvel recebe as marcações no sync automático, como as do site.

- Minimizado: pílula no canto ("Ch. 47 · 13 left"). Clicar abre o cartão: capítulo, página, **Mark read** / **Mark unread** e **Next →** (marca o atual e abre o seguinte)
- Marca sozinho ao chegar ao fim da última página (leitores com scroll; só depois de 10 s no capítulo e nunca depois de um "Mark unread")
- Arrasta-se pela pílula ou pelo cabeçalho do cartão; a posição fica guardada
- Fora da biblioteca (ou fora dos sites das fontes) não aparece

## Instalar

1. `aoi-extension.zip` (no site: `https://aoi-mangas.vercel.app/aoi-extension.zip`), descomprimir
2. `chrome://extensions` → *Developer mode* → *Load unpacked* → pasta `aoi-extension`
3. Abrir o site do Aoi com sessão: a extensão liga-se sozinha à conta

## Como funciona

| Ficheiro | O que faz |
|-|-|
| `manifest.json` | Manifest V3. Permissões: `storage` e o Supabase. Os content scripts correm em todas as páginas, mas só perguntam pelos sites das fontes da biblioteca |
| `config.js` | URL e chave pública do Supabase, link do site |
| `background.js` | Guarda o token e chama as funções SQL (`aoi_ext_*`) |
| `site-bridge.js` | No site: põe `data-aoi-extension` na página e recebe o token do site (`window.postMessage`) |
| `overlay.js` | Nas fontes: overlay num Shadow DOM, páginas = imagens grandes da página, URL vigiado para leitores sem recarregar |
| `popup.html` / `popup.js` | Popup do ícone: conta ligada, abrir o site, desligar |

O token é próprio da extensão (criado pelo site com `aoi_ext_create_token`), não é a sessão do site: o refresh token do Supabase roda e, partilhado, um dos dois ficava deslogado. O SQL está em `supabase/migrations/20261029_chrome_extension.sql`.

## Gerar o zip

Da raiz do repo: `bash extension/make-zip.sh` (cria `aoi-extension.zip`). Ao mudar a extensão: subir `version` no `manifest.json` e `EXTENSION_LATEST_VERSION` no `index.html` (o site avisa quem tem uma versão antiga).
