-- Aoi 0.22.0: contas admin e dados do Admin (Savearth, Definições).
-- Corrida no SQL Editor a 22/10/2026. Para pôr uma conta como admin, só o passo 3 (com o username certo).

begin;

-- 1. Quem é admin, pelo id da conta (não muda quando se muda o username)
create table if not exists public.admins (
  user_id    uuid primary key references auth.users(id) on delete cascade,
  created_at timestamptz not null default now()
);

alter table public.admins enable row level security;

-- Cada um só consegue ver se ele próprio é admin; ninguém se põe admin pela API
drop policy if exists admins_select_own on public.admins;
create policy admins_select_own on public.admins
  for select to authenticated
  using (auth.uid() = user_id);

revoke all on public.admins from anon, authenticated;
grant select on public.admins to authenticated;
grant all on public.admins to service_role;

create or replace function public.is_admin()
returns boolean
language sql
stable
security definer
set search_path = public
as $$
  select exists (select 1 from public.admins where user_id = auth.uid());
$$;

revoke execute on function public.is_admin() from public, anon;
grant execute on function public.is_admin() to authenticated;

-- 2. Dados do Savearth e das Definições (chave/valor, como o localStorage da Aoc), só para admins
create table if not exists public.admin_data (
  user_id    uuid not null default auth.uid() references auth.users(id) on delete cascade,
  key        text not null,
  value      jsonb,
  updated_at timestamptz not null default now(),
  primary key (user_id, key)
);

create or replace trigger admin_data_set_updated_at
  before insert or update on public.admin_data
  for each row
  execute function public.aoi_set_updated_at();

alter table public.admin_data enable row level security;

drop policy if exists admin_data_own on public.admin_data;
create policy admin_data_own on public.admin_data
  for all to authenticated
  using (auth.uid() = user_id and public.is_admin())
  with check (auth.uid() = user_id and public.is_admin());

revoke all on public.admin_data from anon;
grant select, insert, update, delete on public.admin_data to authenticated, service_role;

-- 3. Pôr uma conta como admin
insert into public.admins (user_id)
select id from public.profiles
where lower(username) = lower('O_TEU_USERNAME')
on conflict do nothing;

commit;

-- Confirmar
select a.user_id, p.username
from public.admins a
join public.profiles p on p.id = a.user_id;
