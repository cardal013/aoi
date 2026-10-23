// Edge function `admin-set-password`: um admin muda a palavra-passe de outra conta (ecrã Users da app).
// Precisa da API de admin do Auth, por isso corre com a service role.
// Regras (plano da v0.23.0): só admins chamam; mínimo de 6 caracteres; não dá para mudar a de
// outro admin; a conta sai da sessão em todos os aparelhos.
//
// POST com JWT do Aoi e { user_id, new_password }. Responde { success, signed_out }.
// Erros: 401 (sem sessão), 403 not_admin / target_is_admin,
// 400 invalid_user_id / password_too_short / password_rejected,
// 404 user_not_found.

import { corsHeaders, errorResponse, HttpError, jsonResponse, readJsonBody } from "../_shared/http.ts"
import { createAnonClient, createServiceClient, getCallerUser, isAdminAccount } from "../_shared/supabase.ts"
import type { SupabaseClient } from "https://esm.sh/@supabase/supabase-js@2"

const MIN_PASSWORD_LENGTH = 6
const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

/**
 * Termina todas as sessões da conta (refresh tokens revogados em todos os aparelhos).
 * A API de admin só faz logout com um JWT da própria conta, por isso entra-se com a palavra-passe
 * nova acabada de definir e faz-se logout global com essa sessão.
 * Devolve true se conseguiu. Os access tokens já emitidos ainda valem até expirarem (1 h por defeito).
 */
async function signOutEverywhere(service: SupabaseClient, email: string, newPassword: string): Promise<boolean> {
  const { data, error } = await createAnonClient().auth.signInWithPassword({ email: email, password: newPassword })
  if (error || !data.session) {
    console.error("Não deu para entrar com a palavra-passe nova:", error?.message)
    return false
  }

  const { error: signOutError } = await service.auth.admin.signOut(data.session.access_token, "global")
  if (signOutError) {
    console.error("Logout global falhou:", signOutError.message)
    return false
  }
  return true
}

/** Valida o pedido, muda a palavra-passe e termina as sessões da conta. */
async function changePassword(req: Request) {
  const caller = await getCallerUser(req)
  const body = await readJsonBody(req)
  const service = createServiceClient()

  const callerIsAdmin = await isAdminAccount(service, caller.id)
  if (!callerIsAdmin) {
    throw new HttpError(403, "not_admin")
  }

  const targetUserId = body.user_id
  const isValidUserId = typeof targetUserId === "string" && UUID_PATTERN.test(targetUserId)
  if (!isValidUserId) {
    throw new HttpError(400, "invalid_user_id")
  }

  const newPassword = body.new_password
  const isLongEnough = typeof newPassword === "string" && newPassword.length >= MIN_PASSWORD_LENGTH
  if (!isLongEnough) {
    throw new HttpError(400, "password_too_short")
  }

  const isOtherAccount = targetUserId !== caller.id
  if (isOtherAccount && await isAdminAccount(service, targetUserId)) {
    throw new HttpError(403, "target_is_admin")
  }

  const { data: target, error: targetError } = await service.auth.admin.getUserById(targetUserId)
  if (targetError || !target.user || !target.user.email) {
    throw new HttpError(404, "user_not_found")
  }

  const { error: updateError } = await service.auth.admin.updateUserById(targetUserId, { password: newPassword })
  if (updateError) {
    // por exemplo, a palavra-passe não cumpre as regras do Auth do projeto
    console.error("O Auth recusou a palavra-passe nova:", updateError.message)
    throw new HttpError(400, "password_rejected")
  }

  const signedOut = await signOutEverywhere(service, target.user.email, newPassword)
  return { success: true, signed_out: signedOut }
}

Deno.serve(async (req) => {
  if (req.method === "OPTIONS") {
    return new Response("ok", { headers: corsHeaders })
  }

  try {
    if (req.method !== "POST") {
      throw new HttpError(405, "method_not_allowed")
    }
    return jsonResponse(await changePassword(req))
  } catch (error) {
    return errorResponse(error)
  }
})
