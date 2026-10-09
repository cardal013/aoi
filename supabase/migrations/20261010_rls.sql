-- Aoi: RLS de todas as tabelas usadas pela app e pelo site.
-- Pode correr-se mais de uma vez. Só mexe nas políticas com prefixo aoi_; as outras ficam como estão
-- (ver supabase/rls_check.sql: se houver políticas mais largas nas tabelas da biblioteca, apagar à mão).
-- A edge function change-username usa a service role, que passa por cima da RLS.
-- Os ::text servem para funcionar quer as colunas de utilizador sejam uuid quer text.
--
-- O que a app e o site fazem (com sessão, role authenticated):
--   profiles               insert da própria linha no signup, select do próprio username
--   extensions, manga,     upsert (insert + update + select) e select nos joins da biblioteca.
--   manga_sources,         São um catálogo partilhado entre utilizadores, sem dados pessoais:
--   chapters               qualquer utilizador com sessão lê e escreve, ninguém apaga
--   user_library,          tudo, mas só nas linhas com user_id = auth.uid()
--   user_chapter_progress

alter table public.profiles enable row level security;
alter table public.extensions enable row level security;
alter table public.manga enable row level security;
alter table public.manga_sources enable row level security;
alter table public.chapters enable row level security;
alter table public.user_library enable row level security;
alter table public.user_chapter_progress enable row level security;

-- profiles
drop policy if exists aoi_profiles_select on public.profiles;
create policy aoi_profiles_select on public.profiles
  for select to authenticated using (id::text = (select auth.uid()::text));

drop policy if exists aoi_profiles_insert on public.profiles;
create policy aoi_profiles_insert on public.profiles
  for insert to authenticated with check (id::text = (select auth.uid()::text));

-- Catálogo partilhado
do $$
declare
  t text;
begin
  foreach t in array array['extensions', 'manga', 'manga_sources', 'chapters'] loop
    execute format('drop policy if exists aoi_%1$s_select on public.%1$I', t);
    execute format('create policy aoi_%1$s_select on public.%1$I for select to authenticated using (true)', t);
    execute format('drop policy if exists aoi_%1$s_insert on public.%1$I', t);
    execute format('create policy aoi_%1$s_insert on public.%1$I for insert to authenticated with check (true)', t);
    execute format('drop policy if exists aoi_%1$s_update on public.%1$I', t);
    execute format(
      'create policy aoi_%1$s_update on public.%1$I for update to authenticated using (true) with check (true)', t
    );
  end loop;
end
$$;

-- Biblioteca e progresso de cada utilizador
do $$
declare
  t text;
begin
  foreach t in array array['user_library', 'user_chapter_progress'] loop
    execute format('drop policy if exists aoi_%1$s_own on public.%1$I', t);
    execute format(
      'create policy aoi_%1$s_own on public.%1$I for all to authenticated '
      'using (user_id::text = (select auth.uid()::text)) with check (user_id::text = (select auth.uid()::text))', t
    );
  end loop;
end
$$;

-- anon (visitantes sem sessão) não precisa de nada nas tabelas da biblioteca
revoke all on public.user_library, public.user_chapter_progress from anon;
