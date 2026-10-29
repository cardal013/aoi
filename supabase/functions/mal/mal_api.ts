// Ligação à API do MyAnimeList (lista de animes; dos mangas só estado e capítulos, ver manga_list.ts).
// Trata dos pedidos HTTP ao MAL, do token de cada conta (tabela mal_tokens, só service role)
// e da renovação do token quando está quase a expirar ou o MAL o recusa.
// Documentação: https://myanimelist.net/apiconfig/references/api/v2 e .../authorization

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2"
import { HttpError, requireEnv } from "../_shared/http.ts"

export const MAL_API_URL = "https://api.myanimelist.net/v2"
export const MAL_AUTHORIZE_URL = "https://myanimelist.net/v1/oauth2/authorize"
const MAL_TOKEN_URL = "https://myanimelist.net/v1/oauth2/token"

// Redirect URL registado na app do MAL (myanimelist.net/apiconfig). Tem de ser exatamente igual.
export const MAL_REDIRECT_URI = "https://lmwsecuiylqkpeabckdv.supabase.co/functions/v1/mal/callback"

const MAL_TIMEOUT_MS = 15_000
// Renova o token se faltar menos de 1 dia (o do MAL dura cerca de 31 dias)
const REFRESH_MARGIN_MS = 24 * 60 * 60 * 1000
const HTTP_UNAUTHORIZED = 401
const HTTP_TOO_MANY_REQUESTS = 429
const HTTP_SERVER_ERROR = 500
// Token de uma conta com o MAL desligado (a linha de mal_tokens fica para lembrar a conta do MAL)
export const DISCONNECTED_TOKEN = ""

/** MAL em baixo (sem rede, timeout, 5xx ou 429). A app guarda a alteração na fila e tenta depois. */
export class MalUnavailableError extends HttpError {
  constructor() {
    super(503, "mal_unavailable")
  }
}

/** Conta do Aoi sem MAL ligado (ou com o token revogado): a app mostra "Connect MyAnimeList". */
export class MalNotConnectedError extends HttpError {
  constructor() {
    super(409, "mal_not_connected")
  }
}

/** Token de uma conta, já válido. `accessToken` muda se for preciso renovar a meio. */
export type MalSession = {
  userId: string
  accessToken: string
  refreshToken: string
}

type MalTokenResponse = {
  access_token: string
  refresh_token: string
  expires_in: number
}

type MalTokenRow = {
  access_token: string
  refresh_token: string
  expires_at: string
}

/**
 * Faz um pedido ao MAL com timeout.
 * Sem rede, timeout, 5xx ou 429 lança MalUnavailableError; os 4xx voltam para quem chamou decidir.
 */
export async function fetchFromMal(url: string, init: RequestInit): Promise<Response> {
  let response: Response
  try {
    response = await fetch(url, { ...init, signal: AbortSignal.timeout(MAL_TIMEOUT_MS) })
  } catch {
    throw new MalUnavailableError()
  }

  const isServerError = response.status >= HTTP_SERVER_ERROR
  const isRateLimited = response.status === HTTP_TOO_MANY_REQUESTS
  if (isServerError || isRateLimited) {
    await response.body?.cancel()
    throw new MalUnavailableError()
  }
  return response
}

/**
 * Pede tokens ao MAL (troca do code do login ou renovação).
 * Recebe os campos do grant (grant_type, code/refresh_token, ...); junta o client_id e o client_secret.
 * Devolve os tokens, ou null se o MAL recusar o grant (code ou refresh token inválido).
 */
async function requestTokens(grantFields: Record<string, string>): Promise<MalTokenResponse | null> {
  const form = new URLSearchParams({
    client_id: requireEnv("MAL_CLIENT_ID"),
    client_secret: requireEnv("MAL_CLIENT_SECRET"),
    ...grantFields,
  })

  const response = await fetchFromMal(MAL_TOKEN_URL, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: form,
  })

  if (!response.ok) {
    console.error("MAL recusou o pedido de token:", response.status, await response.text())
    return null
  }
  return await response.json()
}

/** Troca o code do login (com o code_verifier do PKCE) por tokens. null se o MAL recusar. */
export function exchangeAuthorizationCode(code: string, codeVerifier: string): Promise<MalTokenResponse | null> {
  return requestTokens({
    grant_type: "authorization_code",
    code: code,
    code_verifier: codeVerifier,
    redirect_uri: MAL_REDIRECT_URI,
  })
}

/** Converte o expires_in (segundos) do MAL na data em que o token expira. */
export function expiryDate(tokens: MalTokenResponse): string {
  return new Date(Date.now() + tokens.expires_in * 1000).toISOString()
}

/**
 * Lê a linha de mal_tokens da conta, ou null se nunca teve o MAL ligado.
 * Uma linha com os tokens vazios é um MAL desligado (a linha fica para lembrar a conta do MAL, ver clearTokens).
 */
async function readTokenRow(service: SupabaseClient, userId: string): Promise<MalTokenRow | null> {
  const { data, error } = await service
    .from("mal_tokens")
    .select("access_token, refresh_token, expires_at")
    .eq("user_id", userId)
    .maybeSingle()
  if (error) {
    throw error
  }
  return data
}

/**
 * Devolve o token da conta, renovado se estiver quase a expirar.
 * Lança MalNotConnectedError se a conta não tiver o MAL ligado.
 */
export async function getMalSession(service: SupabaseClient, userId: string): Promise<MalSession> {
  const row = await readTokenRow(service, userId)
  const isConnected = row !== null && row.refresh_token !== DISCONNECTED_TOKEN
  if (!isConnected) {
    throw new MalNotConnectedError()
  }

  const session: MalSession = {
    userId: userId,
    accessToken: row.access_token,
    refreshToken: row.refresh_token,
  }

  const millisecondsLeft = new Date(row.expires_at).getTime() - Date.now()
  if (millisecondsLeft < REFRESH_MARGIN_MS) {
    await refreshSession(service, session)
  }
  return session
}

/**
 * Renova o token no MAL e grava-o. Atualiza `session` com os tokens novos.
 * Se o MAL recusar o refresh token, vê primeiro se outro pedido já o renovou entretanto
 * (o MAL troca o refresh token a cada renovação). Se não, o token já não serve: limpa os tokens
 * e lança MalNotConnectedError (a app volta a mostrar "Connect MyAnimeList").
 */
async function refreshSession(service: SupabaseClient, session: MalSession): Promise<void> {
  const usedRefreshToken = session.refreshToken
  const tokens = await requestTokens({ grant_type: "refresh_token", refresh_token: usedRefreshToken })

  if (tokens === null) {
    const latestRow = await readTokenRow(service, session.userId)
    const renewedByOtherRequest = latestRow !== null &&
      latestRow.refresh_token !== DISCONNECTED_TOKEN &&
      latestRow.refresh_token !== usedRefreshToken
    if (renewedByOtherRequest) {
      session.accessToken = latestRow.access_token
      session.refreshToken = latestRow.refresh_token
      return
    }

    await clearTokens(service, session.userId)
    throw new MalNotConnectedError()
  }

  const { error } = await service
    .from("mal_tokens")
    .update({
      access_token: tokens.access_token,
      refresh_token: tokens.refresh_token,
      expires_at: expiryDate(tokens),
    })
    .eq("user_id", session.userId)
  if (error) {
    throw error
  }

  session.accessToken = tokens.access_token
  session.refreshToken = tokens.refresh_token
}

/**
 * Desliga o MAL da conta sem apagar a linha de mal_tokens: os tokens ficam vazios e o mal_user_id fica,
 * para, ao voltar a ligar, se saber se é a mesma conta do MAL (decisão do Afonso; sem tabela nova).
 */
export async function clearTokens(service: SupabaseClient, userId: string): Promise<void> {
  const { error } = await service
    .from("mal_tokens")
    .update({
      access_token: DISCONNECTED_TOKEN,
      refresh_token: DISCONNECTED_TOKEN,
      expires_at: new Date(0).toISOString(),
    })
    .eq("user_id", userId)
  if (error) {
    throw error
  }
}

/**
 * Pedido à API do MAL em nome da conta (com o token dela).
 * `pathOrUrl` é um caminho ("/users/@me/animelist?...") ou um URL completo (o paging.next do MAL).
 * Se o MAL disser 401, renova o token uma vez e repete. Devolve a resposta (2xx ou 4xx).
 */
export async function callMalApi(
  service: SupabaseClient,
  session: MalSession,
  pathOrUrl: string,
  init: RequestInit = {},
): Promise<Response> {
  const url = pathOrUrl.startsWith("https://") ? pathOrUrl : MAL_API_URL + pathOrUrl

  const firstResponse = await sendWithToken(url, init, session.accessToken)
  if (firstResponse.status !== HTTP_UNAUTHORIZED) {
    return firstResponse
  }

  await firstResponse.body?.cancel()
  await refreshSession(service, session)
  return await sendWithToken(url, init, session.accessToken)
}

/** Envia um pedido ao MAL com o cabeçalho Authorization do token. */
function sendWithToken(url: string, init: RequestInit, accessToken: string): Promise<Response> {
  const headers = new Headers(init.headers)
  headers.set("Authorization", `Bearer ${accessToken}`)
  return fetchFromMal(url, { ...init, headers: headers })
}

/** Pedido público à API do MAL (pesquisa), só com o client_id e sem login. */
export function callMalPublicApi(path: string): Promise<Response> {
  return fetchFromMal(MAL_API_URL + path, {
    headers: { "X-MAL-CLIENT-ID": requireEnv("MAL_CLIENT_ID") },
  })
}
