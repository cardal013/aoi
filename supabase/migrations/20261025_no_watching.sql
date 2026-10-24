-- Aoi 0.24.0: sai o estado Watching dos animes (fica só Plan to Watch e Completed).
-- Decisão do Afonso (24/10/2026): Watching passa a Plan to Watch, também no próprio MAL
-- (isso faz a edge function `mal` no sync seguinte, mantendo os episódios vistos).
-- Posições à mão e o modo da antiga aba Watching deixam de contar.
-- Correr no SQL Editor depois do deploy da edge function `mal` nova. Corre numa transação.

begin;

update public.user_mal_anime set status = 'PLAN_TO_WATCH' where status = 'WATCHING';
update public.user_mal_anime set manual_position = null, manual_tab = null where manual_tab = 'WATCHING';
update public.user_anime_folders set manual_position = null, manual_tab = null where manual_tab = 'WATCHING';
delete from public.user_anime_tabs where tab = 'WATCHING';

alter table public.user_mal_anime drop constraint if exists user_mal_anime_status_check;
alter table public.user_mal_anime add constraint user_mal_anime_status_check
  check (status in ('PLAN_TO_WATCH', 'COMPLETED'));

alter table public.user_mal_anime drop constraint if exists user_mal_anime_manual_tab_check;
alter table public.user_mal_anime add constraint user_mal_anime_manual_tab_check
  check (manual_tab in ('PLAN_TO_WATCH', 'COMPLETED'));

alter table public.user_anime_folders drop constraint if exists user_anime_folders_manual_tab_check;
alter table public.user_anime_folders add constraint user_anime_folders_manual_tab_check
  check (manual_tab in ('PLAN_TO_WATCH', 'COMPLETED'));

alter table public.user_anime_tabs drop constraint if exists user_anime_tabs_tab_check;
alter table public.user_anime_tabs add constraint user_anime_tabs_tab_check
  check (tab in ('PLAN_TO_WATCH', 'COMPLETED'));

commit;
