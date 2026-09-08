import { serve } from "https://deno.land/std@0.168.0/http/server.ts"
import { createClient } from "https://esm.sh/@supabase/supabase-js@2"

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
}

serve(async (req) => {
  // Handle CORS
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    // 1. Validate the user JWT from the header
    const authHeader = req.headers.get('Authorization')
    if (!authHeader) throw new Error('Missing Authorization header')

    const supabaseClient = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_ANON_KEY') ?? '',
      { global: { headers: { Authorization: authHeader } } }
    )

    const { data: { user }, error: userError } = await supabaseClient.auth.getUser()
    if (userError || !user) throw new Error('Unauthorized')

    // 2. Parse request body
    const { newUsername } = await req.json()
    if (!newUsername) throw new Error('New username is required')
    const trimmedUsername = newUsername.trim()

    // 3. Normalization logic (Byte-to-byte parity with App/Site)
    // Replicates Kotlin: username.trim().lowercase().filter { it.isLetterOrDigit() }
    const normalized = trimmedUsername.toLowerCase()
      .split('')
      .filter((char: string) => /\p{L}|\p{N}/u.test(char))
      .join('')
    const newInternalEmail = `usr_${normalized}@aoi-app.com`

    // 4. Create Admin client to bypass RLS and update Auth emails without confirmation
    const supabaseAdmin = createClient(
      Deno.env.get('SUPABASE_URL') ?? '',
      Deno.env.get('SUPABASE_SERVICE_ROLE_KEY') ?? ''
    )

    // 5. Check uniqueness in profiles table (Admin client ignores RLS)
    const { data: existing, error: checkError } = await supabaseAdmin
      .from('profiles')
      .select('id')
      .eq('username', trimmedUsername)
      .maybeSingle()

    if (checkError) throw checkError
    if (existing && existing.id !== user.id) {
      return new Response(JSON.stringify({ error: 'username_taken' }), {
        status: 409,
        headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      })
    }

    // 6. Update Auth User (Email + Metadata)
    // email_confirm: true tells Supabase the new email is already confirmed (bypassing confirmation flow)
    const { error: authUpdateError } = await supabaseAdmin.auth.admin.updateUserById(
      user.id,
      {
        email: newInternalEmail,
        email_confirm: true,
        user_metadata: { username: trimmedUsername }
      }
    )
    if (authUpdateError) throw authUpdateError

    // 7. Update Profiles Table
    const { error: profileUpdateError } = await supabaseAdmin
      .from('profiles')
      .update({ username: trimmedUsername })
      .eq('id', user.id)
    if (profileUpdateError) throw profileUpdateError

    return new Response(JSON.stringify({ success: true, email: newInternalEmail }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: 200,
    })

  } catch (error) {
    console.error('Edge Function Error:', error.message)
    return new Response(JSON.stringify({ error: error.message }), {
      headers: { ...corsHeaders, 'Content-Type': 'application/json' },
      status: 400,
    })
  }
})
