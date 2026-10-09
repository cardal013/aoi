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
