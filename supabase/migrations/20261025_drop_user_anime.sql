-- Aoi 0.23.0: apaga a lista de animes antiga do AniList (tabela user_anime da 0.21.0).
-- Decisão do Afonso (24/10/2026): os animes com ids do AniList deitam-se fora, sem migração para o MAL.
-- Corrida pelo Afonso no SQL Editor a 24/10/2026, antes da release 0.23.0.
-- A app 0.23.0 e o site novo já não usam a user_anime. Não mexe no trigger
-- user_chapter_progress_set_updated_at, que veio na mesma migration da user_anime.

begin;
drop table if exists public.user_anime;
commit;
