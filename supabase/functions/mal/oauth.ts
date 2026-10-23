// Login no MyAnimeList (OAuth 2 com PKCE) e estado da ligação de cada conta do Aoi.
// Fluxo: a app/site pede `login` (com JWT) -> recebe o URL do MAL e abre-o no browser ->
// o utilizador autoriza -> o MAL volta a /functions/v1/mal/callback (sem JWT) com code e state ->
// o callback encontra o login pelo state (tabela mal_oauth_states), troca o code por tokens,
// guarda-os em mal_tokens e redireciona de volta para a app ou para o site.
// O MAL só aceita PKCE "plain": o code_challenge é o próprio code_verifier.

import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2"
import { HttpError, requireEnv } from "../_shared/http.ts"
import {
  exchangeAuthorizationCode,
  expiryDate,
  fetchFromMal,
  MAL_API_URL,
  MAL_AUTHORIZE_URL,
  MAL_REDIRECT_URI,
} from "./mal_api.ts"

// Para onde o callback manda o utilizador no fim. Só estes dois (nada vindo do pedido, para não
// haver redirecionamentos abertos). aoi://myanimelist-auth é do tracker de manga do Mihon: não usar.
const RETURN_URLS: Record<string, string> = {
  app: "aoi://mal-anime-auth",
  site: "https://aoi-mangas.vercel.app/",
}
const DEFAULT_RETURN_TO = "app"

// Um login tem 10 minutos para voltar do MAL
const STATE_MAX_AGE_MS = 10 * 60 * 1000
// O MAL pede um code_verifier entre 43 e 128 caracteres
const CODE_VERIFIER_LENGTH = 128
const STATE_LENGTH = 48
const PKCE_CHARACTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~"

/** Texto aleatório (criptográfico) só com os caracteres permitidos no PKCE. */
function randomPkceString(length: number): string {
  const randomBytes = crypto.getRandomValues(new Uint8Array(length))
  let text = ""
  for (const randomByte of randomBytes) {
    text += PKCE_CHARACTERS[randomByte % PKCE_CHARACTERS.length]
  }
  return text
}

/**
 * Começa um login no MAL para a conta `userId`.
 * Recebe `returnTo` ("app" ou "site"): para onde voltar no fim.
 * Grava o state e o code_verifier e devolve o URL de autorização do MAL para abrir no browser.
 * Efeito secundário: apaga os logins a meio com mais de 10 minutos (de todas as contas).
 */
export async function startLogin(service: SupabaseClient, userId: string, returnTo: unknown): Promise<string> {
  const returnTarget = returnTo === undefined ? DEFAULT_RETURN_TO : returnTo
  const isKnownTarget = typeof returnTarget === "string" && returnTarget in RETURN_URLS
  if (!isKnownTarget) {
    throw new HttpError(400, "invalid_return_to")
  }

  const oldestValidDate = new Date(Date.now() - STATE_MAX_AGE_MS).toISOString()
  await service.from("mal_oauth_states").delete().lt("created_at", oldestValidDate)

  const state = randomPkceString(STATE_LENGTH)
  const codeVerifier = randomPkceString(CODE_VERIFIER_LENGTH)
  const { error } = await service.from("mal_oauth_states").insert({
    state: state,
    user_id: userId,
    code_verifier: codeVerifier,
    return_to: returnTarget,
  })
  if (error) {
    throw error
  }

  const authorizeUrl = new URL(MAL_AUTHORIZE_URL)
  authorizeUrl.searchParams.set("response_type", "code")
  authorizeUrl.searchParams.set("client_id", requireEnv("MAL_CLIENT_ID"))
  authorizeUrl.searchParams.set("redirect_uri", MAL_REDIRECT_URI)
  authorizeUrl.searchParams.set("state", state)
  authorizeUrl.searchParams.set("code_challenge", codeVerifier)
  authorizeUrl.searchParams.set("code_challenge_method", "plain")
  return authorizeUrl.toString()
}

/** Resposta 302 para `returnUrl` com `?mal=<resultado>` (no domínio supabase.co as funções não servem HTML). */
function redirectBack(returnUrl: string, result: string): Response {
  const location = new URL(returnUrl)
  location.searchParams.set("mal", result)
  return new Response(null, { status: 302, headers: { Location: location.toString() } })
}

type OAuthStateRow = {
  user_id: string
  code_verifier: string
  return_to: string
  created_at: string
}

/**
 * Trata do regresso do MAL (GET /functions/v1/mal/callback?code=...&state=...).
 * O state é de uso único: apaga-se logo ao ler. Devolve sempre um 302 para a app ou o site com
 * `mal=connected`, `mal=denied` (o utilizador recusou), `mal=expired` (login velho ou desconhecido)
 * ou `mal=error`.
 */
export async function handleCallback(service: SupabaseClient, req: Request): Promise<Response> {
  const callbackUrl = new URL(req.url)
  const state = callbackUrl.searchParams.get("state")
  const code = callbackUrl.searchParams.get("code")
  const malError = callbackUrl.searchParams.get("error")

  if (!state) {
    return redirectBack(RETURN_URLS.site, "expired")
  }

  const { data: stateRow, error: stateError } = await service
    .from("mal_oauth_states")
    .delete()
    .eq("state", state)
    .select("user_id, code_verifier, return_to, created_at")
    .maybeSingle<OAuthStateRow>()
  if (stateError) {
    throw stateError
  }
  if (stateRow === null) {
    return redirectBack(RETURN_URLS.site, "expired")
  }

  const returnUrl = RETURN_URLS[stateRow.return_to]
  const stateAgeMs = Date.now() - new Date(stateRow.created_at).getTime()
  if (stateAgeMs > STATE_MAX_AGE_MS) {
    return redirectBack(returnUrl, "expired")
  }
  if (malError || !code) {
    return redirectBack(returnUrl, "denied")
  }

  try {
    await saveTokensFromCode(service, stateRow, code)
  } catch (error) {
    console.error("Falhou a ligação ao MAL:", error)
    return redirectBack(returnUrl, "error")
  }
  return redirectBack(returnUrl, "connected")
}

/**
 * Troca o code por tokens, pergunta ao MAL qual é a conta (id e nome) e grava tudo em mal_tokens.
 * Se a conta do Aoi já tinha o MAL ligado, substitui os tokens.
 */
async function saveTokensFromCode(service: SupabaseClient, stateRow: OAuthStateRow, code: string): Promise<void> {
  const tokens = await exchangeAuthorizationCode(code, stateRow.code_verifier)
  if (tokens === null) {
    throw new Error("O MAL recusou o code do login")
  }

  const response = await fetchFromMal(MAL_API_URL + "/users/@me", {
    headers: { Authorization: `Bearer ${tokens.access_token}` },
  })
  if (!response.ok) {
    throw new Error(`O MAL não devolveu a conta: ${response.status}`)
  }
  const malUser = await response.json()

  const { error } = await service.from("mal_tokens").upsert(
    {
      user_id: stateRow.user_id,
      mal_user_id: malUser.id,
      mal_username: malUser.name,
      access_token: tokens.access_token,
      refresh_token: tokens.refresh_token,
      expires_at: expiryDate(tokens),
    },
    { onConflict: "user_id" },
  )
  if (error) {
    throw error
  }
}

/** Estado da ligação: `{ connected, mal_username }` (o token nunca sai do servidor). */
export async function getConnectionStatus(service: SupabaseClient, userId: string) {
  const { data, error } = await service
    .from("mal_tokens")
    .select("mal_username")
    .eq("user_id", userId)
    .maybeSingle()
  if (error) {
    throw error
  }
  if (data === null) {
    return { connected: false, mal_username: null }
  }
  return { connected: true, mal_username: data.mal_username }
}

/**
 * Desliga o MAL da conta: apaga o token e os logins a meio.
 * A cópia da lista, as pastas e a ordem ficam no Supabase.
 */
export async function disconnect(service: SupabaseClient, userId: string) {
  const { error: tokenError } = await service.from("mal_tokens").delete().eq("user_id", userId)
  if (tokenError) {
    throw tokenError
  }
  const { error: stateError } = await service.from("mal_oauth_states").delete().eq("user_id", userId)
  if (stateError) {
    throw stateError
  }
  return { connected: false, mal_username: null }
}
