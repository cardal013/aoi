// Watch do site: leva ao episódio certo nos sites que não se chegam lá só com o ID do MAL.
//   /api/watch?site=anikoto&mal=52991&episode=3&title=Sousou%20no%20Frieren   (também anisuge)
//   /api/watch?site=lunar&mal=52991&episode=3
// Responde com um redirecionamento (o separador abre logo dentro do clique). Se não encontrar o anime,
// manda para a pesquisa do site.

const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
const LUNAR = 'https://lunarx.to';
// Sites com o mesmo motor: pesquisa em /filter?keyword= e episódios em <link do anime>/ep-N
const SEARCH_SITES = {
  anikoto: 'https://anikototv.to',
  anisuge: 'https://anisuge.tv',
};

function norm(s) {
  return String(s || '').toLowerCase().normalize('NFKD').replace(/[^a-z0-9]/g, '');
}

function decode(s) {
  return s.replace(/&#0*39;/g, "'").replace(/&quot;/g, '"').replace(/&amp;/g, '&').replace(/&lt;/g, '<').replace(/&gt;/g, '>');
}

async function anilist(mal) {
  const r = await fetch('https://graphql.anilist.co', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Accept: 'application/json' },
    body: JSON.stringify({
      query: 'query($mal:Int){Media(idMal:$mal,type:ANIME){id title{romaji english} synonyms}}',
      variables: { mal: Number(mal) },
    }),
    signal: AbortSignal.timeout(6000),
  });
  if (!r.ok) return null;
  return ((await r.json()).data || {}).Media || null;
}

// Resultados da pesquisa: link, título japonês (data-jp) e inglês
async function search(base, keyword) {
  const r = await fetch(`${base}/filter?keyword=${encodeURIComponent(keyword)}`, {
    headers: { 'User-Agent': UA },
    signal: AbortSignal.timeout(6000),
  });
  if (!r.ok) return [];
  const html = await r.text();
  const re = /<a\b[^>]*?href="(https?:\/\/[^"]+\/(?:watch|anime)\/[^"\/]+)(?:\/ep-\d+)?"[^>]*?data-jp="([^"]*)"[^>]*>([^<]*)/g;
  const items = [];
  for (let m; (m = re.exec(html));) items.push({ url: m[1], jp: decode(m[2]), en: decode(m[3]).trim() });
  return items;
}

async function searchSite(base, mal, title, episode) {
  const media = mal ? await anilist(mal).catch(() => null) : null;
  const names = new Set([title, media?.title?.romaji, media?.title?.english, ...(media?.synonyms || [])].map(norm).filter(Boolean));
  const keywords = [...new Set([title, media?.title?.romaji, media?.title?.english].filter(Boolean))];
  for (const keyword of keywords) {
    const items = await search(base, keyword).catch(() => []);
    const hit = items.find(it => names.has(norm(it.jp)) || names.has(norm(it.en)));
    if (hit) return `${hit.url}/ep-${episode}`;
  }
  return `${base}/filter?keyword=${encodeURIComponent(title || keywords[0] || '')}`;
}

async function lunar(mal, title, episode) {
  const media = mal ? await anilist(mal).catch(() => null) : null;
  if (media?.id) return `${LUNAR}/anime/${media.id}/1/${episode}`;
  return `${LUNAR}/anime/search?search=${encodeURIComponent(title || '')}`;
}

module.exports = async (req, res) => {
  const { site, mal, title = '' } = req.query;
  const episode = Math.max(1, parseInt(req.query.episode, 10) || 1);
  let target;
  if (SEARCH_SITES[site]) target = await searchSite(SEARCH_SITES[site], mal, title, episode);
  else if (site === 'lunar') target = await lunar(mal, title, episode);
  if (!target) {
    res.statusCode = 400;
    res.end('Unknown site');
    return;
  }
  res.statusCode = 302;
  res.setHeader('Location', target);
  res.setHeader('Cache-Control', 'public, s-maxage=86400');
  res.end();
};
