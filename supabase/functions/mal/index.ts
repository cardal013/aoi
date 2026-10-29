// Edge function `mal`: tudo o que o Aoi faz com o MyAnimeList (lista de animes; dos mangas só estado e capítulos).
// A app e o site não falam diretamente com o MAL (CORS e client_secret): falam com esta função.
//
// Rotas:
//   GET  /functions/v1/mal/callback   regresso do login no MAL (sem JWT; a conta vem do state)
//   POST /functions/v1/mal            com JWT do Aoi e { action, ... } no corpo:
//     status       -> { connected, mal_username }
//     login        -> { url } para abrir no browser (return_to: "app" ou "site")
//     disconnect   -> desliga o MAL da conta
//     search       -> { results } (q: texto a pesquisar)
//     sync         -> reenvia o pendente, lê a lista do MAL e atualiza a cópia user_mal_anime
//     update       -> acrescenta ou muda um anime (mal_id, status, episodes_watched, ficha)
//     remove       -> tira um anime (mal_id)
//     manga_search -> { results } (q: texto a pesquisar)
//     manga_sync   -> liga os mangas da biblioteca ao MAL, compara (PREVIEW) ou sincroniza (ON)
//     manga_add    -> com o sync ligado, põe no MAL um manga acrescentado pelo site (mal_id, status)
//     manga_bind   -> cria na cloud um manga acrescentado pelo site, já com a extensão (ver manga_list.ts)
//     mangadex_find -> { id } da MangaDex de um manga do MAL (mal_id, titles)
//
// Deploy com --no-verify-jwt (o callback chega sem JWT). As ações POST validam o JWT no código.
// Secrets: MAL_CLIENT_ID e MAL_CLIENT_SECRET.

import { corsHeaders, errorResponse, HttpError, jsonResponse, readJsonBody } from "../_shared/http.ts"
import { createServiceClient, getCallerUser } from "../_shared/supabase.ts"
import { disconnect, getConnectionStatus, handleCallback, startLogin } from "./oauth.ts"
import { removeEntry, searchAnime, syncList, updateEntry } from "./anime_list.ts"
import { addMangaToMal, bindManga, findOnMangaDex, searchManga, syncManga } from "./manga_list.ts"

/** Corre a ação pedida pela app ou pelo site, já com a conta validada. */
async function runAction(req: Request): Promise<unknown> {
  const caller = await getCallerUser(req)
  const body = await readJsonBody(req)
  const service = createServiceClient()

  switch (body.action) {
    case "status":
      return await getConnectionStatus(service, caller.id)
    case "login":
      return { url: await startLogin(service, caller.id, body.return_to) }
    case "disconnect":
      return await disconnect(service, caller.id)
    case "search":
      return await searchAnime(body.q)
    case "sync":
      return await syncList(service, caller.id)
    case "update":
      return await updateEntry(service, caller.id, body)
    case "remove":
      return await removeEntry(service, caller.id, body)
    case "manga_search":
      return await searchManga(body.q)
    case "manga_sync":
      return await syncManga(service, caller.id)
    case "manga_add":
      return await addMangaToMal(service, caller.id, body)
    case "manga_bind":
      return await bindManga(service, caller.id, body)
    case "mangadex_find":
      return await findOnMangaDex(body)
    default:
      throw new HttpError(400, "unknown_action")
  }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  const path = new URL(req.url).pathname
  const isOAuthCallback = req.method === "GET" && path.endsWith("/callback")

  try {
    if (isOAuthCallback) {
      return await handleCallback(createServiceClient(), req)
    }
    if (req.method !== "POST") {
      throw new HttpError(405, "method_not_allowed")
    }
    return jsonResponse(await runAction(req))
  } catch (error) {
    return errorResponse(error)
  }
})
