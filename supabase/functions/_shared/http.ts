// Respostas HTTP comuns às edge functions do Aoi: cabeçalhos CORS (o site chama-as do browser),
// respostas em JSON e um erro com código HTTP que acaba o pedido.
// Usado por `mal` e `admin-set-password`.

export const corsHeaders = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Headers": "authorization, x-client-info, apikey, content-type",
  "Access-Control-Allow-Methods": "GET, POST, OPTIONS",
}

/**
 * Erro que acaba o pedido com o código HTTP `status` e `{ error: code }` no corpo.
 * O `code` é o que a app e o site leem para mostrar a mensagem certa.
 */
export class HttpError extends Error {
  status: number
  code: string

  constructor(status: number, code: string) {
    super(code)
    this.status = status
    this.code = code
  }
}

/** Resposta JSON com os cabeçalhos CORS. */
export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status: status,
    headers: { ...corsHeaders, "Content-Type": "application/json" },
  })
}

/**
 * Transforma qualquer erro numa resposta JSON.
 * Um HttpError dá o seu código; qualquer outro é registado no log e dá 500 sem detalhes.
 */
export function errorResponse(error: unknown): Response {
  if (error instanceof HttpError) {
    return jsonResponse({ error: error.code }, error.status)
  }
  console.error("Erro na edge function:", error)
  return jsonResponse({ error: "internal_error" }, 500)
}

/** Lê o corpo JSON do pedido; um corpo vazio ou inválido dá 400 `invalid_body`. */
export async function readJsonBody(req: Request): Promise<Record<string, unknown>> {
  try {
    const body = await req.json()
    const isObject = typeof body === "object" && body !== null && !Array.isArray(body)
    if (!isObject) {
      throw new HttpError(400, "invalid_body")
    }
    return body
  } catch {
    throw new HttpError(400, "invalid_body")
  }
}

/** Lê uma variável de ambiente (secrets do Supabase); se faltar, falha com 500 e diz qual no log. */
export function requireEnv(name: string): string {
  const value = Deno.env.get(name)
  if (!value) {
    console.error(`Falta a variável de ambiente ${name}`)
    throw new HttpError(500, "server_misconfigured")
  }
  return value
}
