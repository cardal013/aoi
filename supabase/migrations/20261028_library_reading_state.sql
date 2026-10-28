-- Aoi 0.34.1 (site): web_url para abrir o manga e os capítulos na fonte (corrido a 28/10/2026) e, para cada
-- manga da biblioteca de quem chama, quantos capítulos faltam ler e o próximo a ler (o primeiro por ler
-- depois do último lido; sem nenhum lido, o primeiro). Corre com as permissões de quem chama (RLS).
alter table public.manga_sources add column if not exists web_url text;
alter table public.chapters add column if not exists web_url text;

create or replace function public.library_reading_state()
returns table (
  manga_source_id uuid,
  unread int,
  next_chapter_id uuid,
  next_chapter_name text,
  next_web_url text
)
language sql
stable
security invoker
set search_path = public
as $$
  with lib as (
    select source_id from user_library where user_id = auth.uid()
  ),
  ch as (
    select c.manga_source_id, c.id, c.name, c.chapter_number, c.uploaded_at, c.web_url,
           coalesce(p.read, false) as read
    from chapters c
    join lib on lib.source_id = c.manga_source_id
    left join user_chapter_progress p on p.chapter_id = c.id and p.user_id = auth.uid()
  ),
  last_read as (
    select manga_source_id, max(chapter_number) as n from ch where read group by manga_source_id
  ),
  nxt as (
    select distinct on (ch.manga_source_id) ch.manga_source_id, ch.id, ch.name, ch.web_url
    from ch
    left join last_read lr on lr.manga_source_id = ch.manga_source_id
    where not ch.read and ch.chapter_number > coalesce(lr.n, -1e9)
    order by ch.manga_source_id, ch.chapter_number, ch.uploaded_at, ch.id
  ),
  counts as (
    select manga_source_id, (count(*) filter (where not read))::int as unread
    from ch group by manga_source_id
  )
  select c.manga_source_id, c.unread, n.id, n.name, n.web_url
  from counts c
  left join nxt n on n.manga_source_id = c.manga_source_id;
$$;

revoke execute on function public.library_reading_state() from public, anon;
grant execute on function public.library_reading_state() to authenticated;
