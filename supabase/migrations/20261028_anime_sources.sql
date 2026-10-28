-- Aoi: que fonte de anime corresponde a cada anime do MAL (para ver e transferir episódios).
-- Corrida no SQL Editor a 28/10/2026. Depende de aoi_set_updated_at().
begin;

create table if not exists public.anime_sources (
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  mal_id     bigint not null,
  source_id  text not null,
  anime_url  text not null,
  title      text,
  updated_at timestamptz not null default now(),
  primary key (user_id, mal_id)
);

create or replace trigger anime_sources_set_updated_at
  before insert or update on public.anime_sources
  for each row
  execute function public.aoi_set_updated_at();

alter table public.anime_sources enable row level security;

drop policy if exists anime_sources_own on public.anime_sources;
create policy anime_sources_own on public.anime_sources
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

revoke all on public.anime_sources from anon;
grant select, insert, update, delete on public.anime_sources to authenticated, service_role;

commit;
