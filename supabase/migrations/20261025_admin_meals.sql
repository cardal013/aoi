-- Aoi 0.26.0: Refeições (só admins). Um calendário partilhado por todos os admins:
-- at_home = true (verde, almoço em casa) ou false (vermelho, não almoço). Dia sem linha = por marcar.
-- Depende de 20261022_admin.sql (is_admin() e aoi_set_updated_at()). Correr no SQL Editor. Corre numa transação.

begin;

create table if not exists public.admin_meals (
  day        date primary key,
  at_home    boolean not null,
  updated_by uuid default auth.uid() references auth.users(id) on delete set null,
  updated_at timestamptz not null default now()
);

create or replace trigger admin_meals_set_updated_at
  before insert or update on public.admin_meals
  for each row
  execute function public.aoi_set_updated_at();

alter table public.admin_meals enable row level security;

-- Qualquer admin lê e muda qualquer dia (é partilhado)
drop policy if exists admin_meals_admins on public.admin_meals;
create policy admin_meals_admins on public.admin_meals
  for all to authenticated
  using (public.is_admin())
  with check (public.is_admin());

revoke all on public.admin_meals from anon;
grant select, insert, update, delete on public.admin_meals to authenticated, service_role;

commit;
