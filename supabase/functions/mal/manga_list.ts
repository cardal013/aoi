// Mangas do Aoi com o MyAnimeList: só o estado e os capítulos lidos andam entre os dois.
// O MAL nunca cria nem apaga mangas da biblioteca do Aoi (decisão do Afonso, 29/10/2026).
// - Cada manga da biblioteca liga-se a um manga do MAL (user_manga_mal): pelo tracker do Mihon, pela
//   pesquisa no MAL (título igual) ou escolhido no site quando há dúvida.
// - PREVIEW só compara e guarda o relatório; ON sincroniza. Na primeira vez de cada manga ganha o Aoi no
//   estado e fica o maior número de capítulos. Depois ganha quem mudou (os dois: o Aoi). Os capítulos nunca recuam.
// - On Hold do MAL conta como Plan to Read (no MAL fica como está, a não ser que o Aoi o mude).
// - Mangas acrescentados pelo site (library_requests): bind cria o manga na cloud quando já se sabe o URL
//   da extensão (MangaDex, mangas que já estão na cloud, ou o telemóvel que o encontrou).

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2"
import { HttpError } from "../_shared/http.ts"
import { callMalApi, callMalPublicApi, getMalSession, type MalSession, MalUnavailableError } from "./mal_api.ts"
import { md5 } from "./md5.ts"

const MAL_TO_AOI_STATUS: Record<string, string> = {
  reading: "READING",
  completed: "COMPLETED",
  dropped: "DROPPED",
  plan_to_read: "PLAN_TO_READ",
  on_hold: "PLAN_TO_READ",
}
const AOI_TO_MAL_STATUS: Record<string, string> = {
  READING: "reading",
  COMPLETED: "completed",
  DROPPED: "dropped",
  PLAN_TO_READ: "plan_to_read",
}
// Novels não são mangas que se leiam no Aoi: ficam de fora ao ligar
const NOVEL_TYPES = ["light_novel", "novel"]

const NODE_FIELDS = "num_chapters,media_type,start_date,alternative_titles,main_picture"
const LIST_FIELDS = `list_status,${NODE_FIELDS}`
// O MAL devolve no máximo 1000 por página na lista do utilizador
const LIST_PAGE_SIZE = 1000
const SEARCH_RESULT_LIMIT = 30
const LINK_SEARCH_LIMIT = 10
const MAX_CANDIDATES = 6
// O MAL só aceita pesquisas de 3 a 64 letras
const MAL_QUERY_MIN = 3
const MAL_QUERY_MAX = 64
// Um sync não pode demorar muito (limite das edge functions): o resto fica para o seguinte
const LINKS_PER_SYNC = 25
const MAL_WRITES_PER_SYNC = 40
// O PostgREST devolve no máximo 1000 linhas por pedido
const READ_PAGE_SIZE = 1000
const WRITE_BATCH_SIZE = 500
const HTTP_NOT_FOUND = 404

const MANGADEX_API = "https://api.mangadex.org"
const MANGADEX_RATINGS = ["safe", "suggestive", "erotica", "pornographic"]

type MalMangaNode = {
  id: number
  title: string
  main_picture?: { medium?: string; large?: string }
  alternative_titles?: { en?: string; ja?: string; synonyms?: string[] }
  num_chapters?: number
  media_type?: string
  start_date?: string
}

type MalListEntry = {
  node: MalMangaNode
  list_status: {
    status: string
    num_chapters_read?: number
    updated_at?: string
  }
}

type MangaDetails = {
  mal_id: number
  title: string
  title_english: string | null
  cover_url: string | null
  media_type: string | null
  num_chapters: number | null
  start_year: number | null
}

type LibraryEntry = {
  manga_id: string
  source_id: string
  title: string
  status: string
  chapters_read: number
}

type LinkRow = {
  manga_id: string
  mal_id: number | null
  link_state: string
  synced_status: string | null
  synced_chapters: number | null
}

type Settings = { state: string }

type PlanItem = {
  manga_id: string
  source_id: string
  mal_id: number
  title: string
  aoi: { status: string; chapters: number }
  mal: { status: string; mal_status: string; chapters: number } | null
  target: { status: string; chapters: number }
  set_mal: boolean
  set_aoi_status: boolean
  set_aoi_chapters: boolean
  removed_on_mal: boolean
  snapshot_changed: boolean
}

/** Ficha do manga a partir do nó do MAL. O MAL dá 0 capítulos quando ainda não se sabe: fica null. */
function detailsFromNode(node: MalMangaNode): MangaDetails {
  const hasChapterCount = node.num_chapters !== undefined && node.num_chapters > 0
  const year = node.start_date ? Number.parseInt(node.start_date.slice(0, 4), 10) : Number.NaN
  return {
    mal_id: node.id,
    title: node.title,
    title_english: node.alternative_titles?.en || null,
    cover_url: node.main_picture?.large ?? node.main_picture?.medium ?? null,
    media_type: node.media_type ?? null,
    num_chapters: hasChapterCount ? node.num_chapters! : null,
    start_year: Number.isNaN(year) ? null : year,
  }
}

// ---------- Títulos ----------

/** Título sem acentos, maiúsculas, espaços nem pontuação, para comparar. */
export function normalizeTitle(title: string): string {
  return title.normalize("NFKD").replace(/\p{M}/gu, "").toLowerCase().replace(/[^\p{L}\p{N}]/gu, "")
}

/** Tira o que vem entre parênteses ou parênteses retos ("(Official)", "[Webtoon]"), que as fontes acrescentam. */
function cleanTitle(title: string): string {
  return title.replace(/[[(【].*?[\])】]/g, " ").replace(/\s+/g, " ").trim()
}

function nodeTitles(node: MalMangaNode): string[] {
  const alternative = node.alternative_titles
  return [node.title, alternative?.en, alternative?.ja, ...(alternative?.synonyms ?? [])]
    .filter((title): title is string => typeof title === "string" && title.length > 0)
}

/**
 * Escolhe o manga do MAL de um título do Aoi entre os resultados da pesquisa (sem novels):
 * LINKED com o único de título igual (o principal, o inglês, o japonês ou um sinónimo); CONFIRM com as
 * opções se houver vários ou nenhum igual; NOT_FOUND sem resultados.
 */
export function pickMalManga(aoiTitle: string, nodes: MalMangaNode[]) {
  const mangas = nodes.filter((node) => !NOVEL_TYPES.includes(node.media_type ?? ""))
  const wanted = normalizeTitle(cleanTitle(aoiTitle))
  const matches = mangas.filter((node) => nodeTitles(node).some((title) => normalizeTitle(cleanTitle(title)) === wanted))
  if (matches.length === 1) {
    return { link_state: "LINKED", mal_id: matches[0].id, candidates: [] as MangaDetails[] }
  }
  if (mangas.length === 0) {
    return { link_state: "NOT_FOUND", mal_id: null, candidates: [] as MangaDetails[] }
  }
  const options = matches.length > 1 ? matches : mangas
  return { link_state: "CONFIRM", mal_id: null, candidates: options.slice(0, MAX_CANDIDATES).map(detailsFromNode) }
}

// ---------- Pesquisa ----------

/** Pesquisa mangas no MAL para acrescentar (só com o client_id), sem +18 como nos animes. */
export async function searchManga(query: unknown) {
  const trimmedQuery = typeof query === "string" ? query.trim() : ""
  if (trimmedQuery === "") {
    throw new HttpError(400, "query_required")
  }

  const searchParams = new URLSearchParams({
    q: trimmedQuery.slice(0, MAL_QUERY_MAX),
    limit: String(SEARCH_RESULT_LIMIT),
    nsfw: "false",
    fields: NODE_FIELDS,
  })
  const response = await callMalPublicApi(`/manga?${searchParams}`)
  if (!response.ok) {
    console.error("MAL recusou a pesquisa de mangas:", response.status, await response.text())
    throw new HttpError(400, "invalid_query")
  }

  const body = await response.json()
  const results = (body.data ?? []).map((item: { node: MalMangaNode }) => detailsFromNode(item.node))
  return { results: results }
}

// ---------- Leituras ----------

async function readAllPages<T>(build: (from: number, to: number) => PromiseLike<{ data: unknown; error: unknown }>) {
  const rows: T[] = []
  for (let from = 0; ; from += READ_PAGE_SIZE) {
    const { data, error } = await build(from, from + READ_PAGE_SIZE - 1)
    if (error) {
      throw error
    }
    const page = data as T[]
    rows.push(...page)
    // página incompleta = chegou ao fim
    if (page.length < READ_PAGE_SIZE) {
      return rows
    }
  }
}

async function readSettings(service: SupabaseClient, userId: string): Promise<Settings | null> {
  const { data, error } = await service
    .from("user_mal_manga_sync")
    .select("state")
    .eq("user_id", userId)
    .maybeSingle()
  if (error) {
    throw error
  }
  return data
}

function readLibrary(service: SupabaseClient, userId: string): Promise<LibraryEntry[]> {
  return readAllPages<LibraryEntry>((from, to) =>
    service.rpc("aoi_library_mal_state", { p_user: userId }).order("manga_id").range(from, to)
  )
}

function readLinks(service: SupabaseClient, userId: string): Promise<LinkRow[]> {
  return readAllPages<LinkRow>((from, to) =>
    service
      .from("user_manga_mal")
      .select("manga_id, mal_id, link_state, synced_status, synced_chapters")
      .eq("user_id", userId)
      .order("manga_id")
      .range(from, to)
  )
}

/** Lê a lista de mangas inteira do utilizador no MAL (com os +18: a comparação fica completa). */
async function fetchWholeMalList(service: SupabaseClient, session: MalSession): Promise<MalListEntry[]> {
  const firstPageParams = new URLSearchParams({ fields: LIST_FIELDS, limit: String(LIST_PAGE_SIZE), nsfw: "true" })
  let nextPage: string | undefined = `/users/@me/mangalist?${firstPageParams}`
  const entries: MalListEntry[] = []
  while (nextPage) {
    const response = await callMalApi(service, session, nextPage)
    if (!response.ok) {
      console.error("MAL não devolveu a lista de mangas:", response.status, await response.text())
      throw new MalUnavailableError()
    }
    const page = await response.json()
    entries.push(...(page.data ?? []))
    nextPage = page.paging?.next
  }
  return entries
}

// ---------- Ligações ----------

/** Cria a ligação (PENDING) dos mangas da biblioteca que ainda não a têm. */
async function createMissingLinks(
  service: SupabaseClient,
  userId: string,
  library: LibraryEntry[],
  links: Map<string, LinkRow>,
) {
  const missing = library.filter((entry) => !links.has(entry.manga_id))
  for (let start = 0; start < missing.length; start += WRITE_BATCH_SIZE) {
    const batch = missing.slice(start, start + WRITE_BATCH_SIZE)
      .map((entry) => ({ user_id: userId, manga_id: entry.manga_id, link_state: "PENDING" }))
    const { error } = await service
      .from("user_manga_mal")
      .upsert(batch, { onConflict: "user_id,manga_id", ignoreDuplicates: true })
    if (error) {
      throw error
    }
  }
  for (const entry of missing) {
    links.set(entry.manga_id, {
      manga_id: entry.manga_id,
      mal_id: null,
      link_state: "PENDING",
      synced_status: null,
      synced_chapters: null,
    })
  }
}

async function saveLink(service: SupabaseClient, userId: string, mangaId: string, values: Record<string, unknown>) {
  const { error } = await service.from("user_manga_mal").update(values).eq("user_id", userId).eq("manga_id", mangaId)
  if (error) {
    throw error
  }
}

/**
 * Liga os PENDING: os que já trazem o mal_id (do tracker do Mihon) ficam LINKED; os outros procuram-se no
 * MAL pelo título, no máximo LINKS_PER_SYNC de cada vez. Devolve quantos ficam por procurar.
 */
async function resolvePendingLinks(
  service: SupabaseClient,
  userId: string,
  library: LibraryEntry[],
  links: Map<string, LinkRow>,
): Promise<number> {
  const pending = library.filter((entry) => links.get(entry.manga_id)?.link_state === "PENDING")
  let searched = 0
  let left = 0
  for (const entry of pending) {
    const link = links.get(entry.manga_id)!
    if (link.mal_id !== null) {
      await saveLink(service, userId, entry.manga_id, { link_state: "LINKED", candidates: [] })
      link.link_state = "LINKED"
      continue
    }
    if (searched >= LINKS_PER_SYNC) {
      left += 1
      continue
    }
    searched += 1

    const query = cleanTitle(entry.title).slice(0, MAL_QUERY_MAX)
    let result = { link_state: "NOT_FOUND", mal_id: null as number | null, candidates: [] as MangaDetails[] }
    if (query.length >= MAL_QUERY_MIN) {
      const params = new URLSearchParams({ q: query, limit: String(LINK_SEARCH_LIMIT), nsfw: "true", fields: NODE_FIELDS })
      const response = await callMalPublicApi(`/manga?${params}`)
      if (response.ok) {
        const body = await response.json()
        result = pickMalManga(entry.title, (body.data ?? []).map((item: { node: MalMangaNode }) => item.node))
      } else {
        await response.body?.cancel()
      }
    }
    await saveLink(service, userId, entry.manga_id, result)
    link.link_state = result.link_state
    link.mal_id = result.mal_id
  }
  return left
}

// ---------- Cópia da lista do MAL ----------

/** Guarda a lista do MAL em user_mal_manga (só o que mudou) e tira o que já lá não está. */
async function refreshMalCopy(service: SupabaseClient, userId: string, entries: MalListEntry[]) {
  const copy = await readAllPages<Record<string, unknown>>((from, to) =>
    service
      .from("user_mal_manga")
      .select("mal_id, title, title_english, cover_url, media_type, num_chapters, mal_status, chapters_read, mal_updated_at")
      .eq("user_id", userId)
      .order("mal_id")
      .range(from, to)
  )
  const copyById = new Map(copy.map((row) => [row.mal_id as number, row]))

  const changed: Record<string, unknown>[] = []
  for (const entry of entries) {
    const details = detailsFromNode(entry.node)
    const row = {
      mal_id: details.mal_id,
      title: details.title,
      title_english: details.title_english,
      cover_url: details.cover_url,
      media_type: details.media_type,
      num_chapters: details.num_chapters,
      mal_status: entry.list_status.status,
      chapters_read: entry.list_status.num_chapters_read ?? 0,
      mal_updated_at: entry.list_status.updated_at ?? null,
    }
    const old = copyById.get(row.mal_id)
    const same = old !== undefined && Object.entries(row).every(([key, value]) =>
      key === "mal_updated_at" ? sameInstant(old[key] as string | null, value as string | null) : old[key] === value
    )
    if (!same) {
      changed.push({ user_id: userId, ...row })
    }
  }
  for (let start = 0; start < changed.length; start += WRITE_BATCH_SIZE) {
    const { error } = await service
      .from("user_mal_manga")
      .upsert(changed.slice(start, start + WRITE_BATCH_SIZE), { onConflict: "user_id,mal_id" })
    if (error) {
      throw error
    }
  }

  // Lista vazia com a cópia cheia pode ser uma falha do MAL: não se apaga nada
  const malIds = new Set(entries.map((entry) => entry.node.id))
  const gone = copy.map((row) => row.mal_id as number).filter((id) => !malIds.has(id))
  if (entries.length === 0 && gone.length > 0) {
    return
  }
  for (let start = 0; start < gone.length; start += WRITE_BATCH_SIZE) {
    const { error } = await service
      .from("user_mal_manga")
      .delete()
      .eq("user_id", userId)
      .in("mal_id", gone.slice(start, start + WRITE_BATCH_SIZE))
    if (error) {
      throw error
    }
  }
}

function sameInstant(a: string | null, b: string | null): boolean {
  if (a === null || b === null) {
    return a === b
  }
  return new Date(a).getTime() === new Date(b).getTime()
}

// ---------- Comparar ----------

/**
 * O que fazer com cada manga ligado. Estado: na primeira vez ganha o Aoi; depois ganha quem mudou desde o
 * último sync (os dois: o Aoi). Capítulos: o maior dos dois. Um manga já sincronizado que saiu do MAL deixa
 * de sincronizar (removed_on_mal); nunca sai do Aoi. Dois mangas do Aoi com o mesmo do MAL: só o mais
 * avançado escreve no MAL.
 */
export function buildPlan(library: LibraryEntry[], links: Map<string, LinkRow>, entries: MalListEntry[]): PlanItem[] {
  const malById = new Map(entries.map((entry) => [entry.node.id, entry]))
  const malWritten = new Set<number>()
  const plan: PlanItem[] = []

  const sorted = [...library].sort((a, b) => b.chapters_read - a.chapters_read)
  for (const entry of sorted) {
    const link = links.get(entry.manga_id)
    if (!link || link.link_state !== "LINKED" || link.mal_id === null) {
      continue
    }
    const aoiStatus = entry.status in AOI_TO_MAL_STATUS ? entry.status : "READING"
    const aoi = { status: aoiStatus, chapters: entry.chapters_read }
    const malEntry = malById.get(link.mal_id)
    const firstTime = link.synced_status === null

    const base = { manga_id: entry.manga_id, source_id: entry.source_id, mal_id: link.mal_id, title: entry.title, aoi: aoi }
    if (!malEntry) {
      plan.push({
        ...base,
        mal: null,
        target: aoi,
        set_mal: firstTime && !malWritten.has(link.mal_id),
        set_aoi_status: false,
        set_aoi_chapters: false,
        removed_on_mal: !firstTime,
        snapshot_changed: firstTime,
      })
      malWritten.add(link.mal_id)
      continue
    }

    const mal = {
      status: MAL_TO_AOI_STATUS[malEntry.list_status.status] ?? "PLAN_TO_READ",
      mal_status: malEntry.list_status.status,
      chapters: malEntry.list_status.num_chapters_read ?? 0,
    }
    let status = aoi.status
    if (!firstTime && aoi.status === link.synced_status && mal.status !== link.synced_status) {
      status = mal.status
    }
    const chapters = Math.max(aoi.chapters, mal.chapters)
    const malBehind = mal.status !== status || mal.chapters < chapters
    plan.push({
      ...base,
      mal: mal,
      target: { status: status, chapters: chapters },
      set_mal: malBehind && !malWritten.has(link.mal_id),
      set_aoi_status: aoi.status !== status,
      set_aoi_chapters: aoi.chapters < chapters,
      removed_on_mal: false,
      snapshot_changed: link.synced_status !== status || link.synced_chapters !== chapters,
    })
    malWritten.add(link.mal_id)
  }
  return plan
}

function countLinks(links: Map<string, LinkRow>, library: LibraryEntry[]) {
  const counts: Record<string, number> = { LINKED: 0, CONFIRM: 0, NOT_FOUND: 0, PENDING: 0, IGNORED: 0 }
  for (const entry of library) {
    const state = links.get(entry.manga_id)?.link_state ?? "PENDING"
    counts[state] = (counts[state] ?? 0) + 1
  }
  return counts
}

function buildPreview(plan: PlanItem[], counts: Record<string, number>, onMalOnly: number) {
  const changes = plan
    .filter((item) => item.set_mal || item.set_aoi_status || item.set_aoi_chapters || item.removed_on_mal)
    .map((item) => ({
      title: item.title,
      mal_id: item.mal_id,
      aoi: item.aoi,
      mal: item.mal && { status: item.mal.status, chapters: item.mal.chapters },
      after: item.target,
      mal_changes: item.set_mal,
      aoi_changes: item.set_aoi_status || item.set_aoi_chapters,
      removed_on_mal: item.removed_on_mal,
    }))
  return {
    generated_at: new Date().toISOString(),
    links: counts,
    on_mal_only: onMalOnly,
    unchanged: plan.length - changes.length,
    changes: changes,
  }
}

// ---------- Aplicar ----------

async function patchMalManga(
  service: SupabaseClient,
  session: MalSession,
  malId: number,
  fields: Record<string, string>,
): Promise<Response> {
  return await callMalApi(service, session, `/manga/${malId}/my_list_status`, {
    method: "PATCH",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(fields),
  })
}

/**
 * Faz o plano: escreve no MAL (no máximo MAL_WRITES_PER_SYNC), no Aoi (estado na user_library e capítulos
 * lidos na user_chapter_progress, que a app recebe no import) e guarda o estado sincronizado.
 * Um manga cuja escrita no MAL fica para depois não guarda o estado: volta a ser tratado no sync seguinte.
 */
async function applyPlan(service: SupabaseClient, session: MalSession, userId: string, plan: PlanItem[]) {
  const result = { mal_sent: 0, aoi_status: 0, aoi_chapters: 0, removed_on_mal: 0, writes_left: 0, mal_rejected: 0 }
  let malWrites = 0
  let malDown = false

  for (const item of plan) {
    if (item.removed_on_mal) {
      await saveLink(service, userId, item.manga_id, { link_state: "IGNORED" })
      result.removed_on_mal += 1
      continue
    }

    if (item.set_mal) {
      if (malDown || malWrites >= MAL_WRITES_PER_SYNC) {
        result.writes_left += 1
        continue
      }
      malWrites += 1
      const fields: Record<string, string> = { num_chapters_read: String(item.target.chapters) }
      // On Hold no MAL e Plan to Read no Aoi são o mesmo: não se muda o estado do MAL só por isso
      const keepOnHold = item.mal?.mal_status === "on_hold" && item.target.status === "PLAN_TO_READ"
      if (!keepOnHold) {
        fields.status = AOI_TO_MAL_STATUS[item.target.status]
      }
      let response: Response
      try {
        response = await patchMalManga(service, session, item.mal_id, fields)
      } catch (error) {
        if (error instanceof MalUnavailableError) {
          malDown = true
          result.writes_left += 1
          continue
        }
        throw error
      }
      if (!response.ok) {
        console.error(`MAL recusou o manga ${item.mal_id}:`, response.status, await response.text())
        result.mal_rejected += 1
        // Um id que o MAL não conhece: liga-se outra vez
        if (response.status === HTTP_NOT_FOUND) {
          await saveLink(service, userId, item.manga_id, { link_state: "NOT_FOUND", mal_id: null })
        }
        continue
      }
      await response.body?.cancel()
      result.mal_sent += 1
    }

    if (item.set_aoi_status) {
      const { error } = await service
        .from("user_library")
        .update({ status: item.target.status })
        .eq("user_id", userId)
        .eq("manga_id", item.manga_id)
      if (error) {
        throw error
      }
      result.aoi_status += 1
    }
    if (item.set_aoi_chapters) {
      const { error } = await service.rpc("aoi_mark_read_up_to", {
        p_user: userId,
        p_source_id: item.source_id,
        p_chapter: item.target.chapters,
      })
      if (error) {
        throw error
      }
      result.aoi_chapters += 1
    }
    if (item.snapshot_changed || item.set_mal || item.set_aoi_status || item.set_aoi_chapters) {
      await saveLink(service, userId, item.manga_id, {
        synced_status: item.target.status,
        synced_chapters: item.target.chapters,
        synced_at: new Date().toISOString(),
      })
    }
  }
  return result
}

// ---------- Sync ----------

/**
 * Sync dos mangas de uma conta (a app chama depois de cada sync com a cloud; o site no painel do MAL):
 * cria e procura as ligações, lê a lista do MAL, guarda a cópia e compara. Em PREVIEW guarda só o
 * relatório; em ON aplica. `more` = ficou trabalho para o próximo sync (ligações ou escritas no MAL).
 */
export async function syncManga(service: SupabaseClient, userId: string) {
  const settings = await readSettings(service, userId)
  if (settings === null || settings.state === "OFF") {
    return { state: "OFF" }
  }
  const session = await getMalSession(service, userId)
  const library = await readLibrary(service, userId)
  const links = new Map((await readLinks(service, userId)).map((link) => [link.manga_id, link]))

  await createMissingLinks(service, userId, library, links)
  const linksLeft = await resolvePendingLinks(service, userId, library, links)
  const entries = await fetchWholeMalList(service, session)
  await refreshMalCopy(service, userId, entries)

  const plan = buildPlan(library, links, entries)
  const counts = countLinks(links, library)
  const linkedMalIds = new Set([...links.values()].filter((link) => link.link_state === "LINKED").map((link) => link.mal_id))
  const onMalOnly = entries.filter((entry) => !linkedMalIds.has(entry.node.id)).length
  const now = new Date().toISOString()

  if (settings.state === "PREVIEW") {
    const preview = buildPreview(plan, counts, onMalOnly)
    const { error } = await service
      .from("user_mal_manga_sync")
      .update({ preview: preview, last_sync_at: now })
      .eq("user_id", userId)
    if (error) {
      throw error
    }
    return { state: "PREVIEW", more: linksLeft > 0, links: counts, changes: preview.changes.length }
  }

  const applied = await applyPlan(service, session, userId, plan)
  const { error } = await service.from("user_mal_manga_sync").update({ last_sync_at: now }).eq("user_id", userId)
  if (error) {
    throw error
  }
  return { state: "ON", more: linksLeft > 0 || applied.writes_left > 0, links: counts, ...applied }
}

// ---------- Acrescentar pelo site ----------

function readInteger(value: unknown, minimum: number, errorCode: string): number {
  const isValid = typeof value === "number" && Number.isInteger(value) && value >= minimum
  if (!isValid) {
    throw new HttpError(400, errorCode)
  }
  return value
}

function readText(value: unknown, errorCode: string): string {
  if (typeof value !== "string" || value.trim() === "") {
    throw new HttpError(400, errorCode)
  }
  return value
}

/**
 * Manga acrescentado pelo site (body: mal_id, status): com o sync ligado, põe-o também na lista do MAL.
 * Só o estado (um manga que já lá esteja não perde os capítulos lidos).
 */
export async function addMangaToMal(service: SupabaseClient, userId: string, body: Record<string, unknown>) {
  const malId = readInteger(body.mal_id, 1, "invalid_mal_id")
  const status = typeof body.status === "string" ? AOI_TO_MAL_STATUS[body.status] : undefined
  if (!status) {
    throw new HttpError(400, "invalid_status")
  }
  const settings = await readSettings(service, userId)
  if (settings?.state !== "ON") {
    return { mal_synced: false, skipped: true }
  }
  const session = await getMalSession(service, userId)
  const response = await patchMalManga(service, session, malId, { status: status })
  const accepted = response.ok
  if (!accepted) {
    console.error(`MAL recusou acrescentar o manga ${malId}:`, response.status, await response.text())
  } else {
    await response.body?.cancel()
  }
  return { mal_synced: accepted }
}

/** UUID v3 como o UUID.nameUUIDFromBytes do Java (MD5 do texto). Tem de dar o mesmo que a app. */
export function nameUuid(text: string): string {
  const bytes = md5(new TextEncoder().encode(text))
  bytes[6] = (bytes[6] & 0x0f) | 0x30
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("")
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * Liga um manga acrescentado pelo site à extensão (body: request_id, source_id, source_name, url, title,
 * thumbnail_url): cria o manga na cloud com os IDs da app (manga:, source:), põe-o na biblioteca com o estado
 * pedido, liga-o ao MAL e dá o pedido por acrescentado. O telemóvel recebe-o no import seguinte.
 */
export async function bindManga(service: SupabaseClient, userId: string, body: Record<string, unknown>) {
  const requestId = readText(body.request_id, "invalid_request_id")
  const sourceId = readText(body.source_id, "invalid_source_id")
  const url = readText(body.url, "invalid_url")
  const title = readText(body.title, "invalid_title")
  const sourceName = typeof body.source_name === "string" ? body.source_name : sourceId
  const thumbnailUrl = typeof body.thumbnail_url === "string" ? body.thumbnail_url : null

  const { data: request, error: requestError } = await service
    .from("library_requests")
    .select("id, mal_id, reading_status")
    .eq("id", requestId)
    .eq("user_id", userId)
    .maybeSingle()
  if (requestError) {
    throw requestError
  }
  if (!request) {
    throw new HttpError(404, "request_not_found")
  }

  const mangaId = nameUuid(`manga:${sourceId}:${url}`)
  const mangaSourceId = nameUuid(`source:${sourceId}:${url}`)
  const steps: [string, Record<string, unknown>, string][] = [
    ["extensions", { id: sourceId, name: sourceName, is_active: true }, "id"],
    ["manga", { id: mangaId, title: title, thumbnail_url: thumbnailUrl }, "id"],
    ["manga_sources", { id: mangaSourceId, manga_id: mangaId, extension_id: sourceId, source_manga_id: url }, "id"],
    [
      "user_library",
      {
        user_id: userId,
        manga_id: mangaId,
        status: request.reading_status,
        is_favorite: true,
        source_id: mangaSourceId,
        added_at: new Date().toISOString(),
      },
      "user_id,manga_id",
    ],
  ]
  // O que já existir (outro utilizador, ou já na biblioteca) fica como está
  for (const [table, row, onConflict] of steps) {
    const { error } = await service.from(table).upsert(row, { onConflict: onConflict, ignoreDuplicates: true })
    if (error) {
      throw error
    }
  }
  if (request.mal_id !== null) {
    const { error } = await service.from("user_manga_mal").upsert(
      { user_id: userId, manga_id: mangaId, mal_id: request.mal_id, link_state: "LINKED", candidates: [] },
      { onConflict: "user_id,manga_id" },
    )
    if (error) {
      throw error
    }
  }
  const { error } = await service
    .from("library_requests")
    .update({ state: "ADDED", manga_id: mangaId, message: null })
    .eq("id", requestId)
  if (error) {
    throw error
  }
  return { manga_id: mangaId }
}

/**
 * Procura na MangaDex o manga com este id do MAL (body: mal_id, titles). A extensão guarda o manga como
 * "/manga/<id da MangaDex>", por isso o site liga-o logo, sem o telemóvel. Devolve `{ id, title, cover_url }` ou `{}`.
 */
export async function findOnMangaDex(body: Record<string, unknown>) {
  const malId = String(readInteger(body.mal_id, 1, "invalid_mal_id"))
  const titles = Array.isArray(body.titles) ? body.titles.filter((title) => typeof title === "string" && title) : []
  for (const title of titles.slice(0, 3)) {
    const params = new URLSearchParams({ title: String(title).slice(0, 200), limit: "20", "includes[]": "cover_art" })
    for (const rating of MANGADEX_RATINGS) {
      params.append("contentRating[]", rating)
    }
    let response: Response
    try {
      response = await fetch(`${MANGADEX_API}/manga?${params}`, {
        headers: { "User-Agent": "Aoi (https://aoi-mangas.vercel.app)" },
        signal: AbortSignal.timeout(15_000),
      })
    } catch {
      throw new HttpError(503, "mangadex_unavailable")
    }
    if (!response.ok) {
      await response.body?.cancel()
      continue
    }
    const page = await response.json()
    for (const manga of page.data ?? []) {
      if (manga.attributes?.links?.mal !== malId) {
        continue
      }
      const cover = (manga.relationships ?? []).find((relation: { type: string }) => relation.type === "cover_art")
      const fileName = cover?.attributes?.fileName
      const localizedTitle = manga.attributes?.title ?? {}
      return {
        id: manga.id,
        title: localizedTitle.en ?? Object.values(localizedTitle)[0] ?? String(title),
        cover_url: fileName ? `https://uploads.mangadex.org/covers/${manga.id}/${fileName}` : null,
      }
    }
  }
  return {}
}
