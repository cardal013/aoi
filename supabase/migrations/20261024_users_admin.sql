-- Aoi 0.23.0: ecrã Users (só admins, só na app).
-- Funções que só correm se quem chama for admin: listar as contas e dar/tirar admin.
-- Mudar a palavra-passe precisa da API de admin do Auth e vai pela edge function `admin-set-password`.
-- Depende de 20261022_admin.sql (tabela admins e is_admin()). Correr no SQL Editor. Corre numa transação.

begin;

-- 1. Lista das contas com o username, a data de criação e se é admin.
--    search: parte do username (sem distinguir maiúsculas); null ou vazio = todas.
--    Lê auth.users para a data de criação e para não esquecer contas sem linha em profiles.
--    A app pagina com range() (o PostgREST devolve no máximo 1000 linhas).
create or replace function public.admin_list_users(search text default null)
returns table (user_id uuid, username text, created_at timestamptz, is_admin boolean)
language plpgsql
stable
security definer
set search_path = public
as $$
#variable_conflict use_column
declare
  search_normalized text := lower(btrim(coalesce(search, '')));
begin
  if not public.is_admin() then
    raise exception 'not_admin' using errcode = '42501';
  end if;

  return query
    select
      u.id,
      p.username,
      u.created_at,
      (a.user_id is not null)
    from auth.users u
    left join public.profiles p on p.id = u.id
    left join public.admins a on a.user_id = u.id
    where search_normalized = ''
       or position(search_normalized in lower(coalesce(p.username, ''))) > 0
    order by lower(p.username) nulls last, u.created_at;
end;
$$;

revoke execute on function public.admin_list_users(text) from public, anon;
grant execute on function public.admin_list_users(text) to authenticated;

-- 2. Dar ou tirar admin a uma conta. Ninguém se pode tirar admin a si próprio.
create or replace function public.admin_set_admin(target_user_id uuid, make_admin boolean)
returns void
language plpgsql
volatile
security definer
set search_path = public
as $$
declare
  removing_own_admin boolean := (target_user_id = auth.uid() and not make_admin);
begin
  if not public.is_admin() then
    raise exception 'not_admin' using errcode = '42501';
  end if;

  if removing_own_admin then
    raise exception 'cannot_remove_own_admin';
  end if;

  if make_admin then
    insert into public.admins (user_id) values (target_user_id)
    on conflict do nothing;
  else
    delete from public.admins where user_id = target_user_id;
  end if;
end;
$$;

revoke execute on function public.admin_set_admin(uuid, boolean) from public, anon;
grant execute on function public.admin_set_admin(uuid, boolean) to authenticated;

commit;
