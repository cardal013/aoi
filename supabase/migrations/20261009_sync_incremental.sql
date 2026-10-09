-- Aoi: suporte ao sync incremental (import só do que mudou na cloud).
-- Só acrescenta uma coluna, um trigger e índices. Não apaga nem altera dados existentes.
-- Correr no painel do Supabase: SQL Editor -> colar -> Run.

-- 1. user_library.updated_at: quando a linha foi criada ou alterada pela última vez.
--    (A app, se esta coluna não existir, volta a ler a biblioteca inteira no import.)
alter table public.user_library
  add column if not exists updated_at timestamptz not null default now();

-- user_chapter_progress.updated_at já existe e é preenchido pela app; isto só garante a coluna.
alter table public.user_chapter_progress
  add column if not exists updated_at timestamptz not null default now();

-- 2. Trigger que atualiza user_library.updated_at em cada UPDATE (inclui o ON CONFLICT DO UPDATE dos upserts).
create or replace function public.aoi_set_updated_at()
returns trigger
language plpgsql
as $$
begin
  new.updated_at = now();
  return new;
end;
$$;

create or replace trigger user_library_set_updated_at
  before update on public.user_library
  for each row
  execute function public.aoi_set_updated_at();

-- 3. Índices para o filtro "user_id = ? and updated_at >= ?" + ordenação, com paginação por range.
create index if not exists user_library_user_updated_idx
  on public.user_library (user_id, updated_at);

create index if not exists user_chapter_progress_user_updated_idx
  on public.user_chapter_progress (user_id, updated_at);

-- 4. Permissões explícitas. O acesso a cada linha continua a depender da RLS.
--    "anon" fica de fora de propósito: só utilizadores com sessão (authenticated) mexem na biblioteca.
grant select, insert, update, delete on public.user_library to authenticated, service_role;
grant select, insert, update, delete on public.user_chapter_progress to authenticated, service_role;
grant execute on function public.aoi_set_updated_at() to authenticated, service_role;
