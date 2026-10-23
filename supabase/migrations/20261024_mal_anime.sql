-- Aoi 0.23.0: animes com o MyAnimeList.
-- Cria a cópia da lista de animes do MAL, as pastas, o modo de ordem de cada aba e o token do MAL.
-- O MAL manda na lista; esta cópia só serve para o caso de o MAL ir abaixo (e guarda as pastas e a ordem,
-- que o MAL não tem). Os mangas do MAL nunca entram aqui.
-- Não mexe na user_anime (AniList): sai no passo da app "tirar o AniList".
-- Correr no SQL Editor antes do deploy da edge function `mal`. Corre numa transação.

begin;

-- 1. Token do MAL de cada conta do Aoi.
--    Só a edge function `mal` lhe chega (service role): RLS ligada, sem políticas e sem grants
--    para anon/authenticated. Assim liga-se uma vez e funciona noutro telemóvel e no site.
create table if not exists public.mal_tokens (
  user_id       uuid primary key references auth.users(id) on delete cascade,
  mal_user_id   integer not null,
  mal_username  text not null,
  access_token  text not null,
  refresh_token text not null,
  expires_at    timestamptz not null,
  created_at    timestamptz not null default now(),
  updated_at    timestamptz not null default now()
);

create or replace trigger mal_tokens_set_updated_at
  before update on public.mal_tokens
  for each row
  execute function public.aoi_set_updated_at();

alter table public.mal_tokens enable row level security;
revoke all on public.mal_tokens from anon, authenticated;
grant all on public.mal_tokens to service_role;

-- 2. Logins a meio (OAuth 2 com PKCE). Entre o "Connect MyAnimeList" e o regresso do MAL
--    guarda-se aqui o state (aleatório, uso único) ligado à conta e o code_verifier.
--    É assim que o callback, que chega sem JWT, sabe de que conta é o login.
create table if not exists public.mal_oauth_states (
  state         text primary key,
  user_id       uuid not null references auth.users(id) on delete cascade,
  code_verifier text not null,
  return_to     text not null check (return_to in ('app', 'site')),
  created_at    timestamptz not null default now()
);

alter table public.mal_oauth_states enable row level security;
revoke all on public.mal_oauth_states from anon, authenticated;
grant all on public.mal_oauth_states to service_role;

-- 3. Pastas de animes (só Aoi + Supabase; o MAL não sabe delas). Não há pastas dentro de pastas.
--    cover_mal_id null = capa da primeira temporada.
--    manual_position só vale na aba manual_tab (a aba onde foi arrumada à mão); se a pasta mudar de aba,
--    a posição deixa de contar e ela vai para o topo da nova aba.
create table if not exists public.user_anime_folders (
  id              uuid primary key default gen_random_uuid(),
  user_id         uuid not null default auth.uid() references auth.users(id) on delete cascade,
  name            text not null check (length(btrim(name)) > 0),
  cover_mal_id    integer,
  sort_order      text not null default 'DATE_ASC'
                  check (sort_order in ('DATE_ASC', 'DATE_DESC', 'MANUAL')),
  manual_position double precision,
  manual_tab      text check (manual_tab in ('WATCHING', 'PLAN_TO_WATCH', 'COMPLETED')),
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now(),
  -- para a chave estrangeira de user_mal_anime garantir que a pasta é da mesma conta
  unique (user_id, id)
);

create or replace trigger user_anime_folders_set_updated_at
  before insert or update on public.user_anime_folders
  for each row
  execute function public.aoi_set_updated_at();

create index if not exists user_anime_folders_user_updated_idx
  on public.user_anime_folders (user_id, updated_at);

alter table public.user_anime_folders enable row level security;

drop policy if exists user_anime_folders_own on public.user_anime_folders;
create policy user_anime_folders_own on public.user_anime_folders
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

revoke all on public.user_anime_folders from anon;
grant select, insert, update, delete on public.user_anime_folders to authenticated, service_role;

-- 4. Cópia da lista de animes do MAL (uma linha por anime, mal_id = id do anime no MAL).
--    status: só os 3 estados do Aoi (On Hold e Dropped passam a PLAN_TO_WATCH no MAL).
--    deleted: remover só marca, para a remoção sincronizar (como na user_anime).
--    mal_pending: alteração gravada aqui mas ainda não enviada ao MAL (MAL em baixo); a edge function
--      reenvia no próximo sync e só depois aceita o que vem do MAL para esta linha.
--    touched_at: quando o anime foi mexido pela última vez (modo Recentes).
--    folder_id / folder_position: pasta (no máximo uma) e posição à mão dentro dela.
--    manual_position / manual_tab: posição à mão na aba, só válida na aba manual_tab (como nas pastas).
--    Quem escreve o que vem do MAL é só a edge function `mal`. A app e o site só mudam a pasta e a ordem.
create table if not exists public.user_mal_anime (
  user_id          uuid not null default auth.uid() references auth.users(id) on delete cascade,
  mal_id           integer not null,
  title            text not null,
  title_english    text,
  cover_url        text,
  media_type       text,
  num_episodes     integer,
  start_date       text,
  status           text not null
                   check (status in ('WATCHING', 'PLAN_TO_WATCH', 'COMPLETED')),
  episodes_watched integer not null default 0 check (episodes_watched >= 0),
  deleted          boolean not null default false,
  mal_pending      boolean not null default false,
  touched_at       timestamptz not null default now(),
  folder_id        uuid,
  folder_position  double precision,
  manual_position  double precision,
  manual_tab       text check (manual_tab in ('WATCHING', 'PLAN_TO_WATCH', 'COMPLETED')),
  added_at         timestamptz not null default now(),
  updated_at       timestamptz not null default now(),
  primary key (user_id, mal_id),
  -- apagar a pasta só deixa os animes soltos (põe folder_id a null e mantém o user_id)
  foreign key (user_id, folder_id)
    references public.user_anime_folders (user_id, id)
    on delete set null (folder_id)
);

create or replace trigger user_mal_anime_set_updated_at
  before insert or update on public.user_mal_anime
  for each row
  execute function public.aoi_set_updated_at();

-- Remover um anime tira-o da pasta e da ordem à mão
create or replace function public.aoi_mal_anime_leave_folder_on_delete()
returns trigger
language plpgsql
as $$
begin
  if new.deleted and not old.deleted then
    new.folder_id := null;
    new.folder_position := null;
    new.manual_position := null;
    new.manual_tab := null;
  end if;
  return new;
end;
$$;

create or replace trigger user_mal_anime_leave_folder_on_delete
  before update on public.user_mal_anime
  for each row
  execute function public.aoi_mal_anime_leave_folder_on_delete();

create index if not exists user_mal_anime_user_updated_idx
  on public.user_mal_anime (user_id, updated_at);

create index if not exists user_mal_anime_user_folder_idx
  on public.user_mal_anime (user_id, folder_id)
  where folder_id is not null;

create index if not exists user_mal_anime_user_pending_idx
  on public.user_mal_anime (user_id)
  where mal_pending;

alter table public.user_mal_anime enable row level security;

drop policy if exists user_mal_anime_select_own on public.user_mal_anime;
create policy user_mal_anime_select_own on public.user_mal_anime
  for select to authenticated
  using (auth.uid() = user_id);

drop policy if exists user_mal_anime_update_own on public.user_mal_anime;
create policy user_mal_anime_update_own on public.user_mal_anime
  for update to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

-- A app e o site leem tudo mas só mudam a pasta, a ordem e o touched_at (sem insert nem delete:
-- acrescentar, mudar o estado e remover vão pela edge function, que fala com o MAL).
revoke all on public.user_mal_anime from anon, authenticated;
grant select on public.user_mal_anime to authenticated;
grant update (folder_id, folder_position, manual_position, manual_tab, touched_at)
  on public.user_mal_anime to authenticated;
grant all on public.user_mal_anime to service_role;

-- 5. Modo de ordem de cada aba de anime (Recentes ou Manual). Sem linha = RECENT.
create table if not exists public.user_anime_tabs (
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  tab        text not null check (tab in ('WATCHING', 'PLAN_TO_WATCH', 'COMPLETED')),
  mode       text not null default 'RECENT' check (mode in ('RECENT', 'MANUAL')),
  updated_at timestamptz not null default now(),
  primary key (user_id, tab)
);

create or replace trigger user_anime_tabs_set_updated_at
  before insert or update on public.user_anime_tabs
  for each row
  execute function public.aoi_set_updated_at();

alter table public.user_anime_tabs enable row level security;

drop policy if exists user_anime_tabs_own on public.user_anime_tabs;
create policy user_anime_tabs_own on public.user_anime_tabs
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

revoke all on public.user_anime_tabs from anon;
grant select, insert, update, delete on public.user_anime_tabs to authenticated, service_role;

commit;
