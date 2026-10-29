-- Aoi: mangas com o MyAnimeList (estados e capítulos lidos em sync) e acrescentar mangas pelo site.
-- O MAL nunca cria nem apaga mangas da biblioteca do Aoi: só se sincronizam o estado e os capítulos lidos.
-- Na primeira sincronização ganha o Aoi no estado e fica o maior número de capítulos (nunca recua).
-- On Hold do MAL conta como Plan to Read no Aoi.
-- Depende de aoi_set_updated_at(). Corre numa transação.
begin;

-- 1. Fontes instaladas em cada telemóvel (uma linha por telemóvel; a app substitui-a quando a lista muda).
--    sources: [{ "id": "<id da fonte>", "name": "MangaDex", "lang": "en", "base_url": "https://…" }]
create table if not exists public.user_devices (
  user_id     uuid not null default auth.uid() references auth.users(id) on delete cascade,
  device_id   text not null check (length(device_id) between 1 and 64),
  device_name text,
  sources     jsonb not null default '[]'::jsonb,
  updated_at  timestamptz not null default now(),
  primary key (user_id, device_id)
);

create or replace trigger user_devices_set_updated_at
  before insert or update on public.user_devices
  for each row
  execute function public.aoi_set_updated_at();

alter table public.user_devices enable row level security;

drop policy if exists user_devices_own on public.user_devices;
create policy user_devices_own on public.user_devices
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

revoke all on public.user_devices from anon;
grant select, insert, update, delete on public.user_devices to authenticated, service_role;

-- 2. Mangas acrescentados pelo site: entram com o manga do MAL e a extensão escolhida e ficam "por ligar"
--    até se saber o URL do manga nessa extensão (o telemóvel procura; a MangaDex e os mangas que já estão
--    na cloud ligam-se logo). Reaproveita a library_requests; os pedidos antigos (sem MAL) saem.
alter table public.library_requests
  add column if not exists mal_id     integer,
  add column if not exists title      text,
  add column if not exists cover_url  text,
  add column if not exists media_type text;

delete from public.library_requests where mal_id is null;

-- 3. Cópia da lista de mangas do MAL (só a edge function `mal` escreve). Serve para comparar com o Aoi
--    e para mostrar o que está no MAL e não no Aoi. mal_status é o do MAL, sem conversão.
create table if not exists public.user_mal_manga (
  user_id        uuid not null default auth.uid() references auth.users(id) on delete cascade,
  mal_id         integer not null,
  title          text not null,
  title_english  text,
  cover_url      text,
  media_type     text,
  num_chapters   integer,
  mal_status     text not null
                 check (mal_status in ('reading', 'completed', 'on_hold', 'dropped', 'plan_to_read')),
  chapters_read  integer not null default 0 check (chapters_read >= 0),
  mal_updated_at timestamptz,
  updated_at     timestamptz not null default now(),
  primary key (user_id, mal_id)
);

create or replace trigger user_mal_manga_set_updated_at
  before insert or update on public.user_mal_manga
  for each row
  execute function public.aoi_set_updated_at();

alter table public.user_mal_manga enable row level security;

drop policy if exists user_mal_manga_select_own on public.user_mal_manga;
create policy user_mal_manga_select_own on public.user_mal_manga
  for select to authenticated
  using (auth.uid() = user_id);

revoke all on public.user_mal_manga from anon, authenticated;
grant select on public.user_mal_manga to authenticated;
grant all on public.user_mal_manga to service_role;

-- 4. Ligação de cada manga do Aoi (manga_id = mangaRemoteId) ao manga do MAL, e o último estado
--    sincronizado (synced_*): é com ele que se sabe de que lado foi a mudança.
--    link_state: PENDING (por procurar), LINKED, CONFIRM (vários candidatos, escolhe-se no site ou na app),
--    NOT_FOUND, IGNORED (não ligar este).
create table if not exists public.user_manga_mal (
  user_id         uuid not null default auth.uid() references auth.users(id) on delete cascade,
  manga_id        uuid not null references public.manga(id) on delete cascade,
  mal_id          integer,
  link_state      text not null default 'PENDING'
                  check (link_state in ('PENDING', 'LINKED', 'CONFIRM', 'NOT_FOUND', 'IGNORED')),
  candidates      jsonb not null default '[]'::jsonb,
  synced_status   text check (synced_status in ('READING', 'COMPLETED', 'DROPPED', 'PLAN_TO_READ')),
  synced_chapters integer check (synced_chapters >= 0),
  synced_at       timestamptz,
  updated_at      timestamptz not null default now(),
  primary key (user_id, manga_id),
  check (link_state <> 'LINKED' or mal_id is not null)
);

create or replace trigger user_manga_mal_set_updated_at
  before insert or update on public.user_manga_mal
  for each row
  execute function public.aoi_set_updated_at();

create index if not exists user_manga_mal_user_state_idx
  on public.user_manga_mal (user_id, link_state);

alter table public.user_manga_mal enable row level security;

drop policy if exists user_manga_mal_own on public.user_manga_mal;
create policy user_manga_mal_own on public.user_manga_mal
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

revoke all on public.user_manga_mal from anon;
grant select, insert, update, delete on public.user_manga_mal to authenticated, service_role;

-- 5. Estado da sincronização de mangas de cada conta.
--    OFF: desligada. PREVIEW: só compara (preview tem o relatório) e não escreve em lado nenhum.
--    ON: em sync. Passa de PREVIEW a ON no site (painel MyAnimeList); a app faz um backup antes do primeiro
--    import depois disso.
create table if not exists public.user_mal_manga_sync (
  user_id      uuid primary key default auth.uid() references auth.users(id) on delete cascade,
  state        text not null default 'OFF' check (state in ('OFF', 'PREVIEW', 'ON')),
  preview      jsonb,
  confirmed_at timestamptz,
  last_sync_at timestamptz,
  updated_at   timestamptz not null default now()
);

create or replace trigger user_mal_manga_sync_set_updated_at
  before insert or update on public.user_mal_manga_sync
  for each row
  execute function public.aoi_set_updated_at();

alter table public.user_mal_manga_sync enable row level security;

drop policy if exists user_mal_manga_sync_own on public.user_mal_manga_sync;
create policy user_mal_manga_sync_own on public.user_mal_manga_sync
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

revoke all on public.user_mal_manga_sync from anon;
grant select, insert, update, delete on public.user_mal_manga_sync to authenticated, service_role;

-- 6. Estado e capítulos lidos de cada manga da biblioteca de uma conta, como o MAL os conta:
--    o número do capítulo lido mais alto (arredondado para baixo; capítulos sem número não contam).
create or replace function public.aoi_library_mal_state(p_user uuid)
returns table (manga_id uuid, source_id uuid, title text, status text, chapters_read integer)
language sql
stable
security invoker
set search_path = public
as $$
  select
    ul.manga_id,
    ul.source_id,
    m.title,
    upper(ul.status) as status,
    coalesce(floor(max(c.chapter_number) filter (where p.read and c.chapter_number > 0)), 0)::integer
  from public.user_library ul
  join public.manga m on m.id = ul.manga_id
  left join public.user_chapter_progress p on p.user_id = ul.user_id and p.manga_id = ul.source_id
  left join public.chapters c on c.id = p.chapter_id
  where ul.user_id = p_user
  group by ul.manga_id, ul.source_id, m.title, ul.status
$$;

-- 7. Marca como lidos os capítulos de um manga até ao número dado (o MAL avançou). Nunca desmarca.
--    Devolve quantos capítulos passaram a lidos. A app recebe-os no import seguinte.
create or replace function public.aoi_mark_read_up_to(p_user uuid, p_source_id uuid, p_chapter numeric)
returns integer
language plpgsql
security invoker
set search_path = public
as $$
declare
  marked integer;
begin
  insert into public.user_chapter_progress (user_id, chapter_id, manga_id, read, last_page_read)
  select p_user, c.id, p_source_id, true, 0
  from public.chapters c
  where c.manga_source_id = p_source_id
    and c.chapter_number > 0
    and c.chapter_number <= p_chapter
  on conflict (user_id, chapter_id) do update
    set read = true
    where not public.user_chapter_progress.read;
  get diagnostics marked = row_count;
  return marked;
end;
$$;

revoke all on function public.aoi_library_mal_state(uuid) from public, anon;
revoke all on function public.aoi_mark_read_up_to(uuid, uuid, numeric) from public, anon, authenticated;
grant execute on function public.aoi_library_mal_state(uuid) to authenticated, service_role;
grant execute on function public.aoi_mark_read_up_to(uuid, uuid, numeric) to service_role;

commit;
