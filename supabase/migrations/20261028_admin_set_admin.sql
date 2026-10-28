-- Aoi 0.30.0: tirar o admin a outra conta dava "Erro (P0001)" no ecrã Users do site.
-- P0001 é um "raise exception" do Postgres. Isto volta a pôr a função admin_set_admin igual à do repo
-- (a única regra é ninguém tirar o admin a si próprio) e, no fim, mostra os triggers da tabela admins
-- e a função que ficou, para ver se há mais alguma coisa a recusar. Correr no SQL Editor.

begin;

create or replace function public.admin_set_admin(target_user_id uuid, make_admin boolean)
returns void
language plpgsql
volatile
security definer
set search_path = public
as $$
begin
  if not public.is_admin() then
    raise exception 'not_admin' using errcode = '42501';
  end if;

  if target_user_id = auth.uid() and not make_admin then
    raise exception 'cannot_remove_own_admin' using errcode = 'P0001';
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

-- Confirmar (o SQL Editor só mostra o último resultado, por isso vai tudo numa tabela):
-- deve vir só uma linha "funcao"; uma linha "trigger" é algo na tabela admins que pode estar a recusar
select 'trigger' as tipo, tgname::text as nome, pg_get_triggerdef(oid) as definicao
from pg_trigger
where tgrelid = 'public.admins'::regclass and not tgisinternal
union all
select 'funcao', p.oid::regprocedure::text, pg_get_functiondef(p.oid)
from pg_proc p
where p.proname = 'admin_set_admin';
