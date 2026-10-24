// Lista de animes do MAL e a cópia dela no Supabase (tabela user_mal_anime).
// O MAL manda: a app e o site mudam a lista por aqui (update/remove), que grava primeiro na cópia
// e depois no MAL. Se o MAL estiver em baixo, a linha fica com mal_pending = true e o próximo sync
// reenvia-a antes de ler a lista do MAL.
// Só a lista de ANIMES (/users/@me/animelist e /anime/{id}/my_list_status). Nunca os mangas.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2"
import { HttpError } from "../_shared/http.ts"
import { callMalApi, callMalPublicApi, getMalSession, type MalSession, MalUnavailableError } from "./mal_api.ts"

// Os 2 estados do Aoi. Watching, On Hold e Dropped do MAL passam a PLAN_TO_WATCH (no próprio MAL).
const AOI_TO_MAL_STATUS: Record<string, string> = {
  PLAN_TO_WATCH: "plan_to_watch",
  COMPLETED: "completed",
}
const MAL_STATUSES_TO_CONVERT = ["watching", "on_hold", "dropped"]
// A app 0.23 ainda manda WATCHING
const LEGACY_WATCHING_STATUS = "WATCHING"

const LIST_FIELDS = "list_status,num_episodes,media_type,start_date,alternative_titles,main_picture"
// O MAL devolve no máximo 1000 por página na lista do utilizador
const LIST_PAGE_SIZE = 1000
const SEARCH_RESULT_LIMIT = 30
// O PostgREST devolve no máximo 1000 linhas por pedido
const COPY_READ_PAGE_SIZE = 1000
const COPY_WRITE_BATCH_SIZE = 500
// Quantos pedidos ao MAL de cada vez ao converter On Hold / Dropped
const MAL_PARALLEL_REQUESTS = 5
const HTTP_NOT_FOUND = 404

type MalAnimeNode = {
  id: number
  title: string
  main_picture?: { medium?: string; large?: string }
  alternative_titles?: { en?: string }
  num_episodes?: number
  media_type?: string
  start_date?: string
}

type MalListEntry = {
  node: MalAnimeNode
  list_status: {
    status: string
    num_episodes_watched?: number
    updated_at?: string
  }
}

/** Colunas da cópia que vêm do MAL (as da pasta e da ordem ficam de fora: são só do Aoi). */
type CopyRow = {
  mal_id: number
  title: string
  title_english: string | null
  cover_url: string | null
  media_type: string | null
  num_episodes: number | null
  start_date: string | null
  status: string
  episodes_watched: number
  deleted: boolean
  mal_pending: boolean
  touched_at: string
}

const COPY_COLUMNS = "mal_id, title, title_english, cover_url, media_type, num_episodes, start_date, " +
  "status, episodes_watched, deleted, mal_pending, touched_at"

// Ficha do anime (sem o estado na lista), como vem na pesquisa e como se guarda na cópia
type AnimeDetails = {
  mal_id: number
  title: string
  title_english: string | null
  cover_url: string | null
  media_type: string | null
  num_episodes: number | null
  start_date: string | null
}

/** Ficha do anime a partir do nó do MAL. O MAL dá 0 episódios quando ainda não se sabe: fica null. */
function detailsFromNode(node: MalAnimeNode): AnimeDetails {
  const hasEpisodeCount = node.num_episodes !== undefined && node.num_episodes > 0
  return {
    mal_id: node.id,
    title: node.title,
    title_english: node.alternative_titles?.en || null,
    cover_url: node.main_picture?.large ?? node.main_picture?.medium ?? null,
    media_type: node.media_type ?? null,
    num_episodes: hasEpisodeCount ? node.num_episodes! : null,
    start_date: node.start_date ?? null,
  }
}

/** Estado do MAL para o do Aoi (Watching, On Hold, Dropped e qualquer outro passam a PLAN_TO_WATCH). */
function aoiStatusFromMal(malStatus: string): string {
  if (malStatus === "completed") {
    return "COMPLETED"
  }
  return "PLAN_TO_WATCH"
}

// ---------- Pesquisa ----------

/**
 * Pesquisa animes no MAL (só com o client_id, sem login), sem +18 (nsfw=false).
 * Devolve `{ results: AnimeDetails[] }`. Pesquisa vazia dá 400 `query_required`;
 * se o MAL recusar a pesquisa (por exemplo, curta demais) dá 400 `invalid_query`.
 */
export async function searchAnime(query: unknown) {
  const trimmedQuery = typeof query === "string" ? query.trim() : ""
  if (trimmedQuery === "") {
    throw new HttpError(400, "query_required")
  }

  const searchParams = new URLSearchParams({
    q: trimmedQuery,
    limit: String(SEARCH_RESULT_LIMIT),
    nsfw: "false",
    fields: "num_episodes,media_type,start_date,alternative_titles,main_picture",
  })
  const response = await callMalPublicApi(`/anime?${searchParams}`)
  if (!response.ok) {
    console.error("MAL recusou a pesquisa:", response.status, await response.text())
    throw new HttpError(400, "invalid_query")
  }

  const body = await response.json()
  const results: AnimeDetails[] = []
  for (const item of body.data ?? []) {
    results.push(detailsFromNode(item.node))
  }
  return { results: results }
}

// ---------- Ler e escrever a cópia ----------

/** Lê a cópia inteira da conta, de 1000 em 1000 linhas. */
async function readCopyRows(service: SupabaseClient, userId: string): Promise<CopyRow[]> {
  const allRows: CopyRow[] = []
  let firstRow = 0
  while (true) {
    const { data, error } = await service
      .from("user_mal_anime")
      .select(COPY_COLUMNS)
      .eq("user_id", userId)
      .order("mal_id")
      .range(firstRow, firstRow + COPY_READ_PAGE_SIZE - 1)
    if (error) {
      throw error
    }
    allRows.push(...(data as unknown as CopyRow[]))

    // página incompleta = chegou ao fim
    if (data.length < COPY_READ_PAGE_SIZE) {
      return allRows
    }
    firstRow += COPY_READ_PAGE_SIZE
  }
}

/** Grava linhas na cópia (insere ou atualiza), em lotes de 500. */
async function upsertCopyRows(service: SupabaseClient, rows: Record<string, unknown>[]): Promise<void> {
  for (let start = 0; start < rows.length; start += COPY_WRITE_BATCH_SIZE) {
    const batch = rows.slice(start, start + COPY_WRITE_BATCH_SIZE)
    const { error } = await service.from("user_mal_anime").upsert(batch, { onConflict: "user_id,mal_id" })
    if (error) {
      throw error
    }
  }
}

/**
 * Tira a marca mal_pending de uma linha, mas só se ninguém a mexeu depois (touched_at igual),
 * para não perder uma alteração mais recente que ainda tem de ir para o MAL.
 * Devolve true se tirou a marca, false se a linha foi mexida entretanto (continua pendente).
 */
async function clearPending(
  service: SupabaseClient,
  userId: string,
  malId: number,
  touchedAt: string,
): Promise<boolean> {
  const { data, error } = await service
    .from("user_mal_anime")
    .update({ mal_pending: false })
    .eq("user_id", userId)
    .eq("mal_id", malId)
    .eq("touched_at", touchedAt)
    .select("mal_id")
  if (error) {
    throw error
  }
  return data.length > 0
}

// ---------- Pedidos ao MAL ----------

/** Muda o estado e os episódios de um anime na lista do MAL (acrescenta-o se não estiver). */
function patchMalListStatus(
  service: SupabaseClient,
  session: MalSession,
  malId: number,
  fields: Record<string, string>,
): Promise<Response> {
  return callMalApi(service, session, `/anime/${malId}/my_list_status`, {
    method: "PATCH",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(fields),
  })
}

/** Tira um anime da lista do MAL. */
function deleteFromMalList(service: SupabaseClient, session: MalSession, malId: number): Promise<Response> {
  return callMalApi(service, session, `/anime/${malId}/my_list_status`, { method: "DELETE" })
}

/**
 * Envia ao MAL uma linha da cópia (removida -> DELETE; senão PATCH com estado e episódios).
 * Devolve true se o MAL aceitou (um DELETE de algo que já lá não está também conta).
 * MAL em baixo lança MalUnavailableError; outro 4xx devolve false e fica no log.
 */
async function sendRowToMal(service: SupabaseClient, session: MalSession, row: CopyRow): Promise<boolean> {
  let response: Response
  if (row.deleted) {
    response = await deleteFromMalList(service, session, row.mal_id)
  } else {
    response = await patchMalListStatus(service, session, row.mal_id, {
      // Uma linha que ainda está WATCHING (de antes da migration) vai como Plan to Watch
      status: AOI_TO_MAL_STATUS[row.status] ?? "plan_to_watch",
      num_watched_episodes: String(row.episodes_watched),
    })
  }

  const alreadyGone = row.deleted && response.status === HTTP_NOT_FOUND
  if (response.ok || alreadyGone) {
    await response.body?.cancel()
    return true
  }

  console.error(`MAL recusou a alteração do anime ${row.mal_id}:`, response.status, await response.text())
  return false
}

/** Lê a lista de animes inteira do utilizador no MAL, página a página (com os +18: a cópia fica completa). */
async function fetchWholeMalList(service: SupabaseClient, session: MalSession): Promise<MalListEntry[]> {
  const firstPageParams = new URLSearchParams({
    fields: LIST_FIELDS,
    limit: String(LIST_PAGE_SIZE),
    nsfw: "true",
  })
  let nextPage: string | undefined = `/users/@me/animelist?${firstPageParams}`
  const entries: MalListEntry[] = []

  while (nextPage) {
    const response = await callMalApi(service, session, nextPage)
    if (!response.ok) {
      console.error("MAL não devolveu a lista:", response.status, await response.text())
      throw new MalUnavailableError()
    }
    const page = await response.json()
    entries.push(...(page.data ?? []))
    nextPage = page.paging?.next
  }
  return entries
}

// ---------- Sync ----------

/**
 * Reenvia ao MAL as linhas da cópia que ficaram por enviar (mal_pending).
 * Se o MAL aceitar, ou recusar de vez (4xx), tira a marca: daí em diante o MAL manda nessa linha.
 * Devolve os mal_id que continuam pendentes (mexidos entretanto) e quantos foram enviados.
 */
async function sendPendingRows(service: SupabaseClient, session: MalSession, copyRows: CopyRow[]) {
  const stillPendingIds = new Set<number>()
  let sentCount = 0

  for (const row of copyRows) {
    if (!row.mal_pending) {
      continue
    }
    const acceptedByMal = await sendRowToMal(service, session, row)
    if (acceptedByMal) {
      sentCount += 1
    }
    const pendingCleared = await clearPending(service, session.userId, row.mal_id, row.touched_at)
    if (pendingCleared) {
      row.mal_pending = false
    } else {
      stillPendingIds.add(row.mal_id)
    }
  }

  return { stillPendingIds, sentCount }
}

/**
 * Passa a Plan to Watch, no próprio MAL, os animes que lá estão Watching, On Hold ou Dropped
 * (o Aoi só tem 2 estados). Não tem volta. Os episódios vistos ficam. Faz 5 pedidos de cada vez.
 * Devolve quantos converteu. MAL em baixo lança MalUnavailableError (o próximo sync continua).
 */
async function convertToPlanToWatch(
  service: SupabaseClient,
  session: MalSession,
  malEntries: MalListEntry[],
): Promise<number> {
  const entriesToConvert = malEntries.filter((entry) => MAL_STATUSES_TO_CONVERT.includes(entry.list_status.status))

  for (let start = 0; start < entriesToConvert.length; start += MAL_PARALLEL_REQUESTS) {
    const group = entriesToConvert.slice(start, start + MAL_PARALLEL_REQUESTS)
    await Promise.all(group.map((entry) => convertOneEntry(service, session, entry)))
  }
  return entriesToConvert.length
}

/** Converte um anime Watching / On Hold / Dropped em Plan to Watch no MAL (só o estado: os episódios ficam). */
async function convertOneEntry(service: SupabaseClient, session: MalSession, entry: MalListEntry): Promise<void> {
  const response = await patchMalListStatus(service, session, entry.node.id, { status: "plan_to_watch" })
  if (!response.ok) {
    console.error(`MAL recusou passar o anime ${entry.node.id} a Plan to Watch:`, response.status)
  }
  await response.body?.cancel()
  entry.list_status.status = "plan_to_watch"
}

/**
 * Compara a lista do MAL com a cópia e devolve só as linhas que mudaram (para não mexer no
 * updated_at de tudo a cada sync, senão a app voltava a descarregar a lista inteira).
 * O touched_at (modo Recentes) só muda quando muda o estado ou os episódios, ou o anime é novo.
 * Linhas ainda pendentes ficam de fora: a alteração do Aoi ainda tem de chegar ao MAL.
 */
function buildChangedRows(
  userId: string,
  malEntries: MalListEntry[],
  copyByMalId: Map<number, CopyRow>,
  stillPendingIds: Set<number>,
): Record<string, unknown>[] {
  const changedRows: Record<string, unknown>[] = []
  const now = new Date().toISOString()

  for (const entry of malEntries) {
    if (stillPendingIds.has(entry.node.id)) {
      continue
    }

    const details = detailsFromNode(entry.node)
    const status = aoiStatusFromMal(entry.list_status.status)
    const episodesWatched = entry.list_status.num_episodes_watched ?? 0
    const malUpdatedAt = entry.list_status.updated_at ?? now
    const copyRow = copyByMalId.get(entry.node.id)

    let touchedAt = malUpdatedAt
    if (copyRow !== undefined) {
      const listChanged = copyRow.status !== status ||
        copyRow.episodes_watched !== episodesWatched ||
        copyRow.deleted
      const detailsChanged = copyRow.title !== details.title ||
        copyRow.title_english !== details.title_english ||
        copyRow.cover_url !== details.cover_url ||
        copyRow.media_type !== details.media_type ||
        copyRow.num_episodes !== details.num_episodes ||
        copyRow.start_date !== details.start_date

      if (!listChanged && !detailsChanged) {
        continue
      }
      if (!listChanged) {
        touchedAt = copyRow.touched_at
      }
    }

    changedRows.push({
      user_id: userId,
      ...details,
      status: status,
      episodes_watched: episodesWatched,
      deleted: false,
      touched_at: touchedAt,
    })
  }
  return changedRows
}

/**
 * Marca como removidos (deleted = true) os animes da cópia que já não estão na lista do MAL
 * (tirados no site do MAL). Não toca nos pendentes.
 * Proteção: se o MAL devolver a lista vazia e a cópia tiver animes, não marca nada (pode ser uma
 * falha do MAL) e devolve skipped = true.
 */
async function markRemovedOnMal(
  service: SupabaseClient,
  userId: string,
  malEntries: MalListEntry[],
  copyRows: CopyRow[],
  stillPendingIds: Set<number>,
) {
  const malIds = new Set(malEntries.map((entry) => entry.node.id))
  const removedIds: number[] = []
  for (const row of copyRows) {
    const goneFromMal = !row.deleted && !malIds.has(row.mal_id) && !stillPendingIds.has(row.mal_id)
    if (goneFromMal) {
      removedIds.push(row.mal_id)
    }
  }

  const malListLooksBroken = malEntries.length === 0 && removedIds.length > 0
  if (malListLooksBroken) {
    console.error(`MAL devolveu a lista vazia; não se marcam ${removedIds.length} animes como removidos`)
    return { removedCount: 0, skipped: true }
  }

  for (let start = 0; start < removedIds.length; start += COPY_WRITE_BATCH_SIZE) {
    const batch = removedIds.slice(start, start + COPY_WRITE_BATCH_SIZE)
    const { error } = await service
      .from("user_mal_anime")
      .update({ deleted: true })
      .eq("user_id", userId)
      .eq("mal_pending", false)
      .in("mal_id", batch)
    if (error) {
      throw error
    }
  }
  return { removedCount: removedIds.length, skipped: false }
}

/**
 * Sync completo da lista de uma conta (ao abrir a app, ao entrar na aba Anime, quando volta a rede
 * e ao puxar para baixo; também serve de import numa instalação nova):
 * 1. reenvia ao MAL o que ficou pendente na cópia
 * 2. lê a lista inteira do MAL
 * 3. passa Watching / On Hold / Dropped a Plan to Watch no MAL
 * 4. grava na cópia só o que mudou
 * 5. marca como removido o que saiu do MAL
 * Depois a app lê a cópia (user_mal_anime) pelo PostgREST, como faz com o resto.
 * MAL em baixo: 503 `mal_unavailable` (a cópia fica como estava, fora o que já se reenviou).
 */
export async function syncList(service: SupabaseClient, userId: string) {
  const session = await getMalSession(service, userId)
  const copyRows = await readCopyRows(service, userId)

  const { stillPendingIds, sentCount } = await sendPendingRows(service, session, copyRows)
  const malEntries = await fetchWholeMalList(service, session)
  const convertedCount = await convertToPlanToWatch(service, session, malEntries)

  const copyByMalId = new Map<number, CopyRow>()
  for (const row of copyRows) {
    copyByMalId.set(row.mal_id, row)
  }
  const changedRows = buildChangedRows(userId, malEntries, copyByMalId, stillPendingIds)
  await upsertCopyRows(service, changedRows)

  const removal = await markRemovedOnMal(service, userId, malEntries, copyRows, stillPendingIds)
  await deleteEmptyFolders(service, userId)

  return {
    total: malEntries.length,
    sent: sentCount,
    converted: convertedCount,
    changed: changedRows.length,
    removed: removal.removedCount,
    removal_skipped: removal.skipped,
  }
}

// ---------- Alterações vindas da app e do site ----------

/** Lê um inteiro >= `minimum` do corpo do pedido; senão 400 com `errorCode`. */
function readInteger(value: unknown, minimum: number, errorCode: string): number {
  const isValid = typeof value === "number" && Number.isInteger(value) && value >= minimum
  if (!isValid) {
    throw new HttpError(400, errorCode)
  }
  return value
}

/** Texto opcional da ficha: string ou null; qualquer outra coisa conta como "não enviado". */
function isOptionalText(value: unknown): value is string | null {
  return typeof value === "string" || value === null
}

/**
 * Tenta enviar a linha ao MAL logo a seguir a gravá-la na cópia.
 * Devolve `{ mal_synced, mal_error? }`. Se não der, a linha fica pendente para o próximo sync.
 */
async function trySendToMal(service: SupabaseClient, userId: string, row: CopyRow) {
  let session: MalSession
  try {
    session = await getMalSession(service, userId)
  } catch (error) {
    if (error instanceof HttpError) {
      return { mal_synced: false, mal_error: error.code }
    }
    throw error
  }

  try {
    const acceptedByMal = await sendRowToMal(service, session, row)
    if (!acceptedByMal) {
      return { mal_synced: false, mal_error: "mal_rejected" }
    }
  } catch (error) {
    if (error instanceof HttpError) {
      return { mal_synced: false, mal_error: error.code }
    }
    throw error
  }

  // Se foi mexida entretanto, a alteração mais recente fica pendente para o próximo sync
  await clearPending(service, userId, row.mal_id, row.touched_at)
  return { mal_synced: true }
}

/**
 * Acrescenta um anime à lista ou muda o estado / episódios (body: mal_id, status, episodes_watched
 * e, ao acrescentar, a ficha: title, title_english, cover_url, media_type, num_episodes, start_date).
 * Grava primeiro na cópia (pendente) e depois no MAL. Devolve `{ saved, mal_synced, mal_error? }`.
 */
export async function updateEntry(service: SupabaseClient, userId: string, body: Record<string, unknown>) {
  const malId = readInteger(body.mal_id, 1, "invalid_mal_id")
  const status = body.status === LEGACY_WATCHING_STATUS ? "PLAN_TO_WATCH" : body.status
  const isValidStatus = typeof status === "string" && status in AOI_TO_MAL_STATUS
  if (!isValidStatus) {
    throw new HttpError(400, "invalid_status")
  }
  const episodesWatched = readInteger(body.episodes_watched, 0, "invalid_episodes_watched")
  const touchedAt = new Date().toISOString()

  const rowToSave: Record<string, unknown> = {
    user_id: userId,
    mal_id: malId,
    status: status,
    episodes_watched: episodesWatched,
    deleted: false,
    mal_pending: true,
    touched_at: touchedAt,
  }
  for (const textField of ["title", "title_english", "cover_url", "media_type", "start_date"]) {
    if (isOptionalText(body[textField])) {
      rowToSave[textField] = body[textField]
    }
  }
  const hasEpisodeCount = typeof body.num_episodes === "number" || body.num_episodes === null
  if (hasEpisodeCount) {
    rowToSave.num_episodes = body.num_episodes
  }

  const { data: savedRow, error } = await service
    .from("user_mal_anime")
    .upsert(rowToSave, { onConflict: "user_id,mal_id" })
    .select(COPY_COLUMNS)
    .single<CopyRow>()
  if (error) {
    // 23502 = coluna not null em falta: anime novo sem title
    if (error.code === "23502") {
      throw new HttpError(400, "title_required")
    }
    throw error
  }

  const malResult = await trySendToMal(service, userId, savedRow)
  return { saved: true, ...malResult }
}

/**
 * Remove um anime da lista (body: mal_id). Na cópia só marca deleted (sai também da pasta, pelo
 * trigger) e depois tira-o do MAL. Devolve `{ saved, mal_synced, mal_error? }`.
 */
export async function removeEntry(service: SupabaseClient, userId: string, body: Record<string, unknown>) {
  const malId = readInteger(body.mal_id, 1, "invalid_mal_id")
  const touchedAt = new Date().toISOString()

  const { data: savedRow, error } = await service
    .from("user_mal_anime")
    .update({ deleted: true, mal_pending: true, touched_at: touchedAt })
    .eq("user_id", userId)
    .eq("mal_id", malId)
    .select(COPY_COLUMNS)
    .maybeSingle<CopyRow>()
  if (error) {
    throw error
  }

  // Remover tirou o anime da pasta (trigger): se ela ficou vazia, apaga-se
  if (savedRow !== null) {
    await deleteEmptyFolders(service, userId)
  }

  // Não estava na cópia: tira-o só do MAL
  if (savedRow === null) {
    const rowOnlyOnMal = { mal_id: malId, deleted: true, touched_at: touchedAt } as CopyRow
    const session = await getMalSession(service, userId)
    const acceptedByMal = await sendRowToMal(service, session, rowOnlyOnMal)
    return { saved: false, mal_synced: acceptedByMal }
  }

  const malResult = await trySendToMal(service, userId, savedRow)
  return { saved: true, ...malResult }
}

// ---------- Pastas vazias ----------

/**
 * Apaga as pastas da conta que ficaram sem animes (decisão do Afonso: pasta vazia apaga-se sozinha).
 * Corre depois do sync (animes tirados no site do MAL) e de remover um anime. A app e o site fazem o mesmo
 * do lado deles depois de mexer nas pastas.
 */
async function deleteEmptyFolders(service: SupabaseClient, userId: string): Promise<void> {
  const { data: folders, error: foldersError } = await service
    .from("user_anime_folders")
    .select("id")
    .eq("user_id", userId)
  if (foldersError) {
    throw foldersError
  }
  if (folders.length === 0) {
    return
  }

  const usedFolderIds = new Set<string>()
  let firstRow = 0
  while (true) {
    const { data, error } = await service
      .from("user_mal_anime")
      .select("folder_id")
      .eq("user_id", userId)
      .not("folder_id", "is", null)
      .order("mal_id")
      .range(firstRow, firstRow + COPY_READ_PAGE_SIZE - 1)
    if (error) {
      throw error
    }
    for (const row of data) {
      usedFolderIds.add(row.folder_id)
    }
    // página incompleta = chegou ao fim
    if (data.length < COPY_READ_PAGE_SIZE) {
      break
    }
    firstRow += COPY_READ_PAGE_SIZE
  }

  const emptyFolderIds = folders.map((folder) => folder.id).filter((id) => !usedFolderIds.has(id))
  if (emptyFolderIds.length === 0) {
    return
  }
  const { error } = await service.from("user_anime_folders").delete().eq("user_id", userId).in("id", emptyFolderIds)
  if (error) {
    throw error
  }
}
