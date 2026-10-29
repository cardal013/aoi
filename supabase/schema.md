# Esquema do Supabase (Aoi)

Tirado do projeto `lmwsecuiylqkpeabckdv` a 9/10/2026 com [`rls_check.sql`](rls_check.sql), antes da migration [`20261010_rls.sql`](migrations/20261010_rls.sql). Para atualizar, voltar a correr o script e substituir isto.

## Tabelas

### profiles
| Coluna | Tipo |
|-|-|
| id | uuid not null (PK, FK para auth.users) |
| username | text not null (unique, duas constraints: `profiles_username_key` e `profiles_username_unique`) |
| created_at | timestamptz not null default now() |

### extensions
| Coluna | Tipo |
|-|-|
| id | text not null (PK, = `manga.source` da app) |
| name | text not null |
| is_active | boolean default true |
| created_at | timestamptz default now() |

### manga
| Coluna | Tipo |
|-|-|
| id | uuid not null default gen_random_uuid() (PK; a app manda o UUID determinístico) |
| title | text not null |
| author, artist, description, thumbnail_url, status | text |
| genres | jsonb default '[]' (a app não usa) |
| created_at, updated_at | timestamptz default now() |

### manga_sources
| Coluna | Tipo |
|-|-|
| id | uuid not null (PK) |
| manga_id | uuid not null (FK manga) |
| extension_id | text not null (FK extensions) |
| source_manga_id | text not null (URL do manga na fonte) |
| last_sync_at | timestamptz default now() |

Unique `(extension_id, source_manga_id)`.

### chapters
| Coluna | Tipo |
|-|-|
| id | uuid not null (PK) |
| manga_source_id | uuid not null (FK manga_sources) |
| source_chapter_id | text not null (URL do capítulo) |
| name | text not null |
| chapter_number | numeric not null |
| chapter_label, scanlator | text |
| uploaded_at | timestamptz |
| created_at | timestamptz default now() |

Unique `(manga_source_id, source_chapter_id)`.

### user_library
| Coluna | Tipo |
|-|-|
| user_id | uuid not null |
| manga_id | uuid not null (FK manga) |
| status | text not null default 'plan_to_read' (a app escreve sempre em maiúsculas: `READING`, `PLAN_TO_READ`, …) |
| is_favorite | boolean default false |
| added_at | timestamptz default now() |
| last_read_at | timestamptz |
| source_id | uuid not null (FK manga_sources) |
| last_chapter_id | uuid (FK chapters) |
| last_page | integer |
| updated_at | timestamptz not null default now() (trigger `user_library_set_updated_at`) |

### user_chapter_progress
| Coluna | Tipo |
|-|-|
| id | uuid not null default gen_random_uuid() (PK) |
| user_id | uuid not null (FK auth.users) |
| manga_id | uuid not null (FK; é o `manga_sources.id`, não o `manga.id`) |
| chapter_id | uuid not null |
| read | boolean not null default false |
| last_page_read | integer |
| updated_at | timestamptz not null default now() |

Unique `(user_id, chapter_id)`.

### user_anime (desde 22/10/2026, [`20261022_anime.sql`](migrations/20261022_anime.sql))
| Coluna | Tipo |
|-|-|
| user_id | uuid not null default auth.uid() (FK auth.users) |
| anilist_id | integer not null (ID do anime no AniList) |
| title, title_english, cover_url, format | text (só `title` é not null) |
| episodes, season_year | integer |
| status | text not null default 'PLAN_TO_WATCH', check em `WATCHING`, `PLAN_TO_WATCH`, `COMPLETED` |
| episodes_watched | integer not null default 0 |
| deleted | boolean not null default false (remover só marca, para sincronizar) |
| added_at | timestamptz not null default now() |
| updated_at | timestamptz not null default now() (trigger `user_anime_set_updated_at`) |

PK `(user_id, anilist_id)`. RLS com uma política `ALL` própria (`user_anime_own`), sem acesso para `anon`.

A mesma migration pôs o trigger `user_chapter_progress_set_updated_at` (insert e update): o `updated_at` do progresso passou a ser a hora do servidor.

### admins e admin_data (desde 22/10/2026, [`20261022_admin.sql`](migrations/20261022_admin.sql))
| Tabela | Colunas | Acesso |
|-|-|-|
| admins | `user_id` uuid PK (FK auth.users), `created_at` | select só da própria linha; ninguém escreve pela API (só no SQL Editor) |
| admin_data | `user_id`, `key` text (PK juntos), `value` jsonb, `updated_at` (trigger) | `ALL` só se `auth.uid() = user_id` **e** `is_admin()` |

`public.is_admin()` (security definer) diz se a conta com sessão está em `admins`. O admin é pelo id da conta, por isso mudar o username não o tira.

### admin_meals (desde 25/10/2026, [`20261025_admin_meals.sql`](migrations/20261025_admin_meals.sql))
| Colunas | Acesso |
|-|-|
| `day` date PK, `at_home` boolean (true = almoço em casa), `updated_by` uuid (default `auth.uid()`), `updated_at` (trigger) | `ALL` para qualquer admin (`is_admin()`): o calendário é partilhado. Dia sem linha = por marcar |

### admin_meal_reminder (desde 25/10/2026, [`20261025_admin_meal_reminder.sql`](migrations/20261025_admin_meal_reminder.sql))
| Colunas | Acesso |
|-|-|
| `id` smallint PK (sempre 1), `days_before` smallint (1 a 7), `remind_at` time (default 19:00), `updated_by`, `updated_at` (trigger) | select, insert, update para qualquer admin: as definições do lembrete são comuns |

### library_requests (desde 29/10/2026, [`20261029_library_requests.sql`](migrations/20261029_library_requests.sql); colunas do MAL em [`20261029_mal_manga.sql`](migrations/20261029_mal_manga.sql))
Mangas acrescentados pelo site ainda por ligar à extensão (aparecem na biblioteca do site "a ligar"). O site cria com o manga do MAL e a extensão escolhida; liga logo pela ação `manga_bind` da função `mal` se der (MangaDex, mangas já na cloud, ou opção escolhida em `CHOOSE`); senão a app (`LibraryRequests`, no sync automático) procura na extensão e acrescenta. Os `ADDED` o site apaga.

| Coluna | Tipo |
|-|-|
| id | uuid PK default `gen_random_uuid()` |
| user_id | uuid not null default `auth.uid()` (FK auth.users) |
| query | text not null (título inglês do MAL, ou o principal) |
| title, mal_id, cover_url, media_type | o manga do MAL (title = título principal, às vezes em romaji) |
| source_id | text (`manga.source` da app como texto, os IDs não cabem num número de JS; null = todas as fontes do telemóvel) |
| reading_status | text not null default `PLAN_TO_READ` (`READING`, `COMPLETED`, `DROPPED`, `PLAN_TO_READ`) |
| state | text not null default `PENDING` |
| candidates | jsonb not null default `[]`: opções para o site escolher, `{source_id, source_name, url, title, thumbnail_url}` |
| choice | jsonb: a opção escolhida no site (mesma forma) |
| manga_id | uuid (FK manga, on delete set null): o manga acrescentado |
| message | text: nome da fonte em falta, erro, "Already in your library" |
| created_at, updated_at | timestamptz (`updated_at` pelo trigger) |

Estados: `PENDING` (o site pediu) → `SEARCHING` (um telemóvel ficou com ele; mais de 10 min = a app morreu e volta a ser tratado) → `ADDED`, `NOT_FOUND`, `FAILED`, `CHOOSE` (várias opções; o site escolhe → `CHOSEN` → `ADDED`) ou `MISSING_EXTENSION` (a fonte pedida ou escolhida não está instalada; volta a ser tratado quando for). `CANCELLED` é do site. RLS com uma política `ALL` própria, sem acesso para `anon`.

### Mangas com o MAL (desde 29/10/2026, [`20261029_mal_manga.sql`](migrations/20261029_mal_manga.sql))
Só o estado e os capítulos lidos andam entre o Aoi e o MAL; o MAL nunca cria nem apaga mangas do Aoi. A lógica está em `supabase/functions/mal/manga_list.ts`.

| Tabela | Colunas | Acesso |
|-|-|-|
| user_devices | `user_id`, `device_id` (PK juntos), `device_name`, `sources` jsonb (`[{id, name, lang, base_url}]`), `updated_at` | `ALL` próprio. A app substitui a linha quando a lista de fontes muda |
| user_mal_manga | `user_id`, `mal_id` (PK), ficha (`title`, `title_english`, `cover_url`, `media_type`, `num_chapters`), `mal_status` (o do MAL: reading, completed, on_hold, dropped, plan_to_read), `chapters_read`, `mal_updated_at` | select próprio; só a função escreve. Cópia da lista de mangas do MAL |
| user_manga_mal | `user_id`, `manga_id` (PK, FK manga), `mal_id`, `link_state` (`PENDING`, `LINKED`, `CONFIRM`, `NOT_FOUND`, `IGNORED`), `candidates` jsonb, `synced_status`, `synced_chapters`, `synced_at` | `ALL` próprio. Ligação manga do Aoi ↔ MAL e o último estado sincronizado (diz de que lado foi a mudança). `PENDING` com `mal_id` = pista do tracker do Mihon |
| user_mal_manga_sync | `user_id` PK, `state` (`OFF`, `PREVIEW`, `ON`), `preview` jsonb (relatório da comparação), `confirmed_at`, `last_sync_at` | `ALL` próprio |

Funções: `aoi_library_mal_state(p_user)` (estado e capítulo lido mais alto de cada manga da biblioteca, `security invoker`) e `aoi_mark_read_up_to(p_user, p_source_id, p_chapter)` (marca como lidos até esse número, nunca desmarca; só `service_role`).

### Extensão do Chrome (desde 29/10/2026, [`20261029_chrome_extension.sql`](migrations/20261029_chrome_extension.sql))
| Tabela | Colunas | Acesso |
|-|-|-|
| extension_tokens | `token_hash` text PK (SHA-256 do token), `user_id` (FK auth.users), `created_at`, `last_used_at` | nenhum pela API (RLS sem políticas); só as funções abaixo. Até 5 por conta (um por browser) |

Funções (`security definer`; a extensão chama como `anon`, só com o token): `aoi_ext_create_token()` (só `authenticated`: o site cria o token e passa-o à extensão), `aoi_ext_revoke(token)`, `aoi_ext_hosts(token)` (sites das fontes da biblioteca), `aoi_ext_chapter_state(token, url, chapter_id)` (capítulo da biblioteca pelo link normalizado com `aoi_norm_url`, e sem o `?...` se só der um; lido, por ler, seguinte) e `aoi_ext_set_read(token, chapter_id, read)` (igual ao site: `user_chapter_progress` + último capítulo em `user_library`). `aoi_ext_user(token)` é interna.

## RLS e permissões

RLS ligada nas 7 tabelas.

| Tabela | Políticas (antes da arrumação) | Depois de `20261010_rls.sql` |
|-|-|-|
| extensions, manga, manga_sources | select, insert, update para `authenticated` (sem delete) | igual |
| chapters | idem, com insert e update repetidos | sem os repetidos |
| user_library | `ALL` próprio + 4 políticas próprias repetidas (role public) | só `ALL` próprio |
| user_chapter_progress | select/insert/update/delete próprios (role public; update sem with check) | uma `ALL` própria com with check |
| profiles | insert e update próprios; **select de todos** os perfis por qualquer utilizador com sessão | select só do próprio |

Grants antes da arrumação: `anon` tinha `REFERENCES, TRIGGER, TRUNCATE` em todas as tabelas (e `SELECT` em `profiles`); `authenticated` tinha tudo. A migration tira tudo ao `anon` e `TRUNCATE, TRIGGER, REFERENCES` ao `authenticated`. Nenhum destes passava pela API REST, mas o `TRUNCATE` ignora a RLS.
