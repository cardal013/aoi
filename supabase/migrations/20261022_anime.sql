-- Aoi 0.21.0: lista de animes e updated_at do progresso pela hora do servidor.
-- Corrida no SQL Editor a 22/10/2026 ("Success. No rows returned").

begin;

-- 1. Progresso: o updated_at passa a ser a hora do servidor (um telemóvel com o relógio atrasado
--    deixava de aparecer no import incremental dos outros)
create or replace trigger user_chapter_progress_set_updated_at
  before insert or update on public.user_chapter_progress
  for each row
  execute function public.aoi_set_updated_at();

-- 2. Animes de cada utilizador (dados do AniList). Remover só marca deleted, para sincronizar
create table if not exists public.user_anime (
  user_id          uuid not null default auth.uid() references auth.users(id) on delete cascade,
  anilist_id       integer not null,
  title            text not null,
  title_english    text,
  cover_url        text,
  format           text,
  episodes         integer,
  season_year      integer,
  status           text not null default 'PLAN_TO_WATCH'
                   check (status in ('WATCHING', 'PLAN_TO_WATCH', 'COMPLETED')),
  episodes_watched integer not null default 0 check (episodes_watched >= 0),
  deleted          boolean not null default false,
  added_at         timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  primary key (user_id, anilist_id)
);

create or replace trigger user_anime_set_updated_at
  before insert or update on public.user_anime
  for each row
  execute function public.aoi_set_updated_at();

create index if not exists user_anime_user_updated_idx
  on public.user_anime (user_id, updated_at);

alter table public.user_anime enable row level security;

drop policy if exists user_anime_own on public.user_anime;
create policy user_anime_own on public.user_anime
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

revoke all on public.user_anime from anon;
grant select, insert, update, delete on public.user_anime to authenticated, service_role;

commit;
