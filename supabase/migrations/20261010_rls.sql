-- Aoi: arrumação da RLS (9/10/2026), feita a partir do resultado do supabase/rls_check.sql (ver supabase/schema.md).
-- A RLS já estava ligada em todas as tabelas e a biblioteca já estava limitada a user_id = auth.uid().
-- Isto só tira políticas repetidas, fecha o profiles e retira privilégios que a app não usa.
-- Correr no SQL Editor. Corre numa transação: ou passa tudo ou nada.

begin;

-- chapters: as políticas "Users can ..." repetiam as "Allow authenticated ..."
drop policy if exists "Users can insert chapters" on public.chapters;
drop policy if exists "Users can update chapters" on public.chapters;

-- user_library: "Users can manage their own library" (ALL) já cobre as outras quatro
drop policy if exists "Users can delete own library" on public.user_library;
drop policy if exists "Users can insert own library" on public.user_library;
drop policy if exists "Users can update own library" on public.user_library;
drop policy if exists "Users can view own library" on public.user_library;

-- user_chapter_progress: as quatro políticas passam a uma só, igual à da biblioteca (o update não tinha with check)
drop policy if exists "delete own" on public.user_chapter_progress;
drop policy if exists "insert own" on public.user_chapter_progress;
drop policy if exists "select own" on public.user_chapter_progress;
drop policy if exists "update own" on public.user_chapter_progress;
drop policy if exists "Users can manage their own progress" on public.user_chapter_progress;
create policy "Users can manage their own progress" on public.user_chapter_progress
  for all to authenticated
  using ((select auth.uid()) = user_id)
  with check ((select auth.uid()) = user_id);

-- profiles: qualquer utilizador com sessão via os usernames de todos. A app só lê o próprio
-- e a edge function change-username usa a service role (passa por cima da RLS)
drop policy if exists profiles_select_public on public.profiles;
drop policy if exists profiles_select_own on public.profiles;
create policy profiles_select_own on public.profiles
  for select to authenticated
  using ((select auth.uid()) = id);

-- anon (sem sessão) não precisa de nada nestas tabelas; truncate, trigger e references não passam
-- pela API, mas o truncate ignora a RLS e não há razão para os ter
revoke all on public.profiles, public.extensions, public.manga, public.manga_sources,
  public.chapters, public.user_library, public.user_chapter_progress from anon;
revoke truncate, trigger, references on public.profiles, public.extensions, public.manga, public.manga_sources,
  public.chapters, public.user_library, public.user_chapter_progress from authenticated;

commit;
