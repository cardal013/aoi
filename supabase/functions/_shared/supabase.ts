// Clientes do Supabase para as edge functions e validação de quem chama.
// O cliente service role ignora a RLS: usar só com o user_id já validado por getCallerUser.
// SUPABASE_URL, SUPABASE_ANON_KEY e SUPABASE_SERVICE_ROLE_KEY existem por defeito nas edge functions.

import { createClient, type SupabaseClient, type User } from "https://esm.sh/@supabase/supabase-js@2"
import { HttpError, requireEnv } from "./http.ts"

const clientOptions = {
  auth: { persistSession: false, autoRefreshToken: false },
}

/** Cliente com a service role (passa por cima da RLS). */
export function createServiceClient(): SupabaseClient {
  return createClient(requireEnv("SUPABASE_URL"), requireEnv("SUPABASE_SERVICE_ROLE_KEY"), clientOptions)
}

/** Cliente com a anon key, sem sessão guardada. */
export function createAnonClient(): SupabaseClient {
  return createClient(requireEnv("SUPABASE_URL"), requireEnv("SUPABASE_ANON_KEY"), clientOptions)
}

/**
 * Valida o JWT do cabeçalho Authorization junto do Auth do Supabase e devolve a conta de quem chama.
 * Necessário porque a função `mal` corre com verify_jwt = false (o callback do MAL chega sem JWT).
 * Falha com 401 se não houver cabeçalho ou se o token não for válido.
 */
export async function getCallerUser(req: Request): Promise<User> {
  const authHeader = req.headers.get("Authorization")
  if (!authHeader) {
    throw new HttpError(401, "missing_authorization")
  }

  const accessToken = authHeader.replace(/^Bearer\s+/i, "")
  const { data, error } = await createAnonClient().auth.getUser(accessToken)
  if (error || !data.user) {
    throw new HttpError(401, "unauthorized")
  }
  return data.user
}

/** Diz se a conta está na tabela admins (lida com a service role). */
export async function isAdminAccount(service: SupabaseClient, userId: string): Promise<boolean> {
  const { data, error } = await service
    .from("admins")
    .select("user_id")
    .eq("user_id", userId)
    .maybeSingle()
  if (error) {
    throw error
  }
  return data !== null
}
