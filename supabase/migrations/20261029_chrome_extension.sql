-- Extensão do Aoi para o Chrome (29/10/2026).
-- A extensão mostra um overlay nas páginas dos capítulos das fontes (MangaFire, Webtoons, ...) e marca os
-- capítulos lidos na cloud, como o site e a app. Não usa a sessão do site (o refresh token do Supabase roda e
-- partilhá-lo deslogava o site): o site pede um token próprio para a extensão (aoi_ext_create_token) e passa-lho.
-- Com esse token a extensão só consegue: ver em que capítulo está, marcar/desmarcar capítulos e saber os sites
-- das fontes da biblioteca. Guarda-se só o hash do token. Correr uma vez no SQL Editor.

-- Tokens da extensão: um por browser ligado (até 5 por conta)
create table if not exists public.extension_tokens (
  token_hash text primary key,
  user_id uuid not null references auth.users(id) on delete cascade,
  created_at timestamptz not null default now(),
  last_used_at timestamptz
);
create index if not exists extension_tokens_user_idx on public.extension_tokens (user_id);

-- Sem políticas: ninguém lê nem escreve a tabela pela API, só as funções abaixo
alter table public.extension_tokens enable row level security;
revoke all on public.extension_tokens from anon, authenticated;

-- Link "normalizado" para comparar o URL da página com o web_url guardado:
-- sem http(s)://, sem www., sem #..., sem barra no fim e em minúsculas. Mantém o ?... (o Webtoons usa-o)
create or replace function public.aoi_norm_url(p_url text)
returns text
language sql
immutable
parallel safe
as $$
  select nullif(
    rtrim(
      regexp_replace(
        regexp_replace(lower(split_part(coalesce(p_url, ''), '#', 1)), '^https?://', ''),
        '^www\.', ''),
      '/'),
    '')
$$;

-- O mesmo, sem o ?... (segunda tentativa, para quando a fonte junta parâmetros ao link)
create or replace function public.aoi_norm_url_path(p_url text)
returns text
language sql
immutable
parallel safe
as $$
  select public.aoi_norm_url(split_part(coalesce(p_url, ''), '?', 1))
$$;

-- Dono do token (erro se o token não existir). Atualiza last_used_at no máximo uma vez por hora.
create or replace function public.aoi_ext_user(p_token text)
returns uuid
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_user uuid;
  v_hash text := encode(extensions.digest(coalesce(p_token, ''), 'sha256'), 'hex');
begin
  select user_id into v_user from public.extension_tokens where token_hash = v_hash;
  if v_user is null then
    raise exception 'invalid extension token' using errcode = '28000';
  end if;

  update public.extension_tokens
     set last_used_at = now()
   where token_hash = v_hash
     and (last_used_at is null or last_used_at < now() - interval '1 hour');

  return v_user;
end
$$;

-- Chamado pelo site (com sessão): cria um token novo para a extensão e devolve-o. Guarda só o hash.
-- Cada conta fica com os 5 tokens mais recentes (um por browser); os mais antigos deixam de funcionar.
create or replace function public.aoi_ext_create_token()
returns text
language plpgsql
security definer
set search_path = public, extensions
as $$
declare
  v_user uuid := auth.uid();
  v_token text;
begin
  if v_user is null then
    raise exception 'not signed in' using errcode = '28000';
  end if;

  v_token := encode(extensions.gen_random_bytes(32), 'hex');
  insert into public.extension_tokens (token_hash, user_id)
  values (encode(extensions.digest(v_token, 'sha256'), 'hex'), v_user);

  delete from public.extension_tokens
   where user_id = v_user
     and token_hash not in (
       select token_hash from public.extension_tokens
        where user_id = v_user
        order by created_at desc
        limit 5);

  return v_token;
end
$$;

-- Chamado pela extensão ao desligar (sair da conta no site): apaga o próprio token
create or replace function public.aoi_ext_revoke(p_token text)
returns void
language sql
security definer
set search_path = public, extensions
as $$
  delete from public.extension_tokens
   where token_hash = encode(extensions.digest(coalesce(p_token, ''), 'sha256'), 'hex');
$$;

-- Sites (host) das fontes da biblioteca: a extensão só pergunta pelos capítulos nestes sites
create or replace function public.aoi_ext_hosts(p_token text)
returns text[]
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user uuid := public.aoi_ext_user(p_token);
  v_hosts text[];
begin
  select coalesce(array_agg(distinct split_part(public.aoi_norm_url(ms.web_url), '/', 1)), '{}')
    into v_hosts
    from public.user_library ul
    join public.manga_sources ms on ms.id = ul.source_id
   where ul.user_id = v_user
     and ms.web_url is not null;
  return v_hosts;
end
$$;

-- Estado de um capítulo da biblioteca, pelo URL da página (ou pelo id). null = não é um capítulo da biblioteca.
-- Devolve: manga, capítulo, se está lido, quantos faltam ler no manga e o capítulo seguinte (número maior).
create or replace function public.aoi_ext_chapter_state(p_token text, p_url text default null, p_chapter_id uuid default null)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user uuid := public.aoi_ext_user(p_token);
  v_chapter record;
  v_next record;
  v_read boolean;
  v_unread int;
  v_total int;
  v_has_next boolean;
begin
  -- 1.ª tentativa: pelo id, ou pelo link completo
  select c.id, c.name, c.chapter_number, c.manga_source_id, ul.manga_id, m.title
    into v_chapter
    from public.user_library ul
    join public.chapters c on c.manga_source_id = ul.source_id
    join public.manga m on m.id = ul.manga_id
   where ul.user_id = v_user
     and (c.id = p_chapter_id
          or (p_chapter_id is null and public.aoi_norm_url(c.web_url) = public.aoi_norm_url(p_url)))
   limit 1;

  -- 2.ª tentativa: o link sem o ?..., só se der um único capítulo
  -- (found = a última consulta encontrou uma linha; ler v_chapter.id antes disso daria erro)
  if not found and p_chapter_id is null then
    select found_chapter.id, found_chapter.name, found_chapter.chapter_number,
           found_chapter.manga_source_id, found_chapter.manga_id, found_chapter.title
      into v_chapter
      from (
        select c.id, c.name, c.chapter_number, c.manga_source_id, ul.manga_id, m.title,
               count(*) over () as matches
          from public.user_library ul
          join public.chapters c on c.manga_source_id = ul.source_id
          join public.manga m on m.id = ul.manga_id
         where ul.user_id = v_user
           and public.aoi_norm_url_path(c.web_url) = public.aoi_norm_url_path(p_url)
      ) as found_chapter
     where found_chapter.matches = 1;
  end if;

  if not found then
    return null;
  end if;

  select coalesce(p.read, false) into v_read
    from public.user_chapter_progress p
   where p.user_id = v_user and p.chapter_id = v_chapter.id;

  select count(*)::int,
         (count(*) filter (where coalesce(p.read, false) = false))::int
    into v_total, v_unread
    from public.chapters c
    left join public.user_chapter_progress p on p.chapter_id = c.id and p.user_id = v_user
   where c.manga_source_id = v_chapter.manga_source_id;

  select c.id, c.name, c.chapter_number, c.web_url
    into v_next
    from public.chapters c
   where c.manga_source_id = v_chapter.manga_source_id
     and c.chapter_number > v_chapter.chapter_number
     and c.web_url is not null
   order by c.chapter_number, c.uploaded_at, c.id
   limit 1;
  v_has_next := found;

  return jsonb_build_object(
    'manga_id', v_chapter.manga_id,
    'source_id', v_chapter.manga_source_id,
    'title', v_chapter.title,
    'chapter', jsonb_build_object('id', v_chapter.id, 'name', v_chapter.name, 'number', v_chapter.chapter_number),
    'read', coalesce(v_read, false),
    'unread', v_unread,
    'total', v_total,
    'next', case when v_has_next
                 then jsonb_build_object('id', v_next.id, 'name', v_next.name, 'number', v_next.chapter_number, 'web_url', v_next.web_url)
                 else null
            end
  );
end
$$;

-- Marca ou desmarca um capítulo da biblioteca (o mesmo que o site grava). Lido passa a ser o último capítulo
-- do manga em user_library. Devolve o estado novo (como aoi_ext_chapter_state).
create or replace function public.aoi_ext_set_read(p_token text, p_chapter_id uuid, p_read boolean)
returns jsonb
language plpgsql
security definer
set search_path = public
as $$
declare
  v_user uuid := public.aoi_ext_user(p_token);
  v_source uuid;
  v_manga uuid;
begin
  select c.manga_source_id, ul.manga_id
    into v_source, v_manga
    from public.chapters c
    join public.user_library ul on ul.source_id = c.manga_source_id and ul.user_id = v_user
   where c.id = p_chapter_id;

  if v_source is null then
    raise exception 'chapter not in library' using errcode = '42501';
  end if;

  insert into public.user_chapter_progress (user_id, chapter_id, manga_id, read, last_page_read, updated_at)
  values (v_user, p_chapter_id, v_source, p_read, 0, now())
  on conflict (user_id, chapter_id)
  do update set read = excluded.read, last_page_read = 0, updated_at = now();

  if p_read then
    update public.user_library
       set last_chapter_id = p_chapter_id, last_page = 0, last_read_at = now()
     where user_id = v_user and manga_id = v_manga;
  end if;

  return public.aoi_ext_chapter_state(p_token, null, p_chapter_id);
end
$$;

-- Permissões: a extensão chama como anon (só com o token); o site cria o token com sessão
revoke execute on function public.aoi_ext_user(text) from public, anon, authenticated;
revoke execute on function public.aoi_ext_create_token() from public, anon;
grant execute on function public.aoi_ext_create_token() to authenticated;
revoke execute on function public.aoi_ext_revoke(text) from public;
grant execute on function public.aoi_ext_revoke(text) to anon, authenticated;
revoke execute on function public.aoi_ext_hosts(text) from public;
grant execute on function public.aoi_ext_hosts(text) to anon, authenticated;
revoke execute on function public.aoi_ext_chapter_state(text, text, uuid) from public;
grant execute on function public.aoi_ext_chapter_state(text, text, uuid) to anon, authenticated;
revoke execute on function public.aoi_ext_set_read(text, uuid, boolean) from public;
grant execute on function public.aoi_ext_set_read(text, uuid, boolean) to anon, authenticated;
