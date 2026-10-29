-- Aoi: pedidos para acrescentar mangas à biblioteca a partir do site.
-- O site cria o pedido; a app pesquisa nas extensões instaladas e acrescenta.
-- Corrida no SQL Editor a 29/10/2026. Depende de aoi_set_updated_at().
begin;

create table if not exists public.library_requests (
  id             uuid primary key default gen_random_uuid(),
  user_id        uuid not null default auth.uid() references auth.users(id) on delete cascade,
  query          text not null check (length(btrim(query)) between 1 and 500),
  source_id      text,
  reading_status text not null default 'PLAN_TO_READ'
                 check (reading_status in ('READING', 'COMPLETED', 'DROPPED', 'PLAN_TO_READ')),
  state          text not null default 'PENDING'
                 check (state in ('PENDING', 'SEARCHING', 'CHOOSE', 'CHOSEN', 'ADDED',
                                  'NOT_FOUND', 'MISSING_EXTENSION', 'FAILED', 'CANCELLED')),
  candidates     jsonb not null default '[]'::jsonb,
  choice         jsonb,
  manga_id       uuid references public.manga(id) on delete set null,
  message        text,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create index if not exists library_requests_user_state
  on public.library_requests (user_id, state);

create or replace trigger library_requests_set_updated_at
  before insert or update on public.library_requests
  for each row
  execute function public.aoi_set_updated_at();

alter table public.library_requests enable row level security;

drop policy if exists library_requests_own on public.library_requests;
create policy library_requests_own on public.library_requests
  for all to authenticated
  using (auth.uid() = user_id)
  with check (auth.uid() = user_id);

revoke all on public.library_requests from anon;
grant select, insert, update, delete on public.library_requests to authenticated, service_role;

commit;
