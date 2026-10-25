-- Aoi 0.27.0: lembrete das Refeições. Uma só linha (id = 1) com as definições comuns a todos os admins:
-- quantos dias antes (1 a 7) e a que horas cada telemóvel admin mostra o lembrete.
-- Depende de 20261022_admin.sql (is_admin() e aoi_set_updated_at()). Correr no SQL Editor. Corre numa transação.

begin;

create table if not exists public.admin_meal_reminder (
  id          smallint primary key default 1 check (id = 1),
  days_before smallint not null default 1 check (days_before between 1 and 7),
  remind_at   time not null default '19:00',
  updated_by  uuid default auth.uid() references auth.users(id) on delete set null,
  updated_at  timestamptz not null default now()
);

insert into public.admin_meal_reminder (id) values (1) on conflict do nothing;

create or replace trigger admin_meal_reminder_set_updated_at
  before insert or update on public.admin_meal_reminder
  for each row
  execute function public.aoi_set_updated_at();

alter table public.admin_meal_reminder enable row level security;

drop policy if exists admin_meal_reminder_admins on public.admin_meal_reminder;
create policy admin_meal_reminder_admins on public.admin_meal_reminder
  for all to authenticated
  using (public.is_admin())
  with check (public.is_admin());

revoke all on public.admin_meal_reminder from anon;
grant select, insert, update on public.admin_meal_reminder to authenticated, service_role;

commit;
