-- Aoi: estado do esquema, da RLS e das políticas. Só lê, não altera nada.
-- Correr no painel do Supabase: SQL Editor -> colar -> Run. Depois "Export -> CSV" e guardar como supabase/schema_atual.csv.
--
-- O que procurar:
--   rls      -> todas as tabelas do Aoi devem estar "ligada"
--   politica -> user_library e user_chapter_progress só com user_id = auth.uid(); profiles só com id = auth.uid()
--   grant    -> anon não precisa de nada nas tabelas da biblioteca

select 1 as ord, 'rls' as tipo, c.relname::text as tabela,
       case when c.relrowsecurity then 'ligada' else 'DESLIGADA' end as detalhe
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public' and c.relkind = 'r'

union all

select 2, 'politica', p.tablename::text,
       p.policyname || ' | ' || p.cmd || ' | ' || array_to_string(p.roles, ',') ||
       ' | using: ' || coalesce(p.qual, '-') || ' | check: ' || coalesce(p.with_check, '-')
from pg_policies p
where p.schemaname = 'public'

union all

select 3, 'grant', g.table_name::text,
       g.grantee || ': ' || string_agg(g.privilege_type, ',' order by g.privilege_type)
from information_schema.role_table_grants g
where g.table_schema = 'public' and g.grantee in ('anon', 'authenticated')
group by g.table_name, g.grantee

union all

select 4, 'coluna', c.table_name::text,
       lpad(c.ordinal_position::text, 2, '0') || ' ' || c.column_name || ' ' || c.data_type ||
       case when c.is_nullable = 'NO' then ' not null' else '' end ||
       coalesce(' default ' || c.column_default, '')
from information_schema.columns c
where c.table_schema = 'public'

union all

select 5, 'constraint', tc.table_name::text, tc.constraint_type || ' ' || tc.constraint_name
from information_schema.table_constraints tc
where tc.table_schema = 'public' and tc.constraint_type in ('PRIMARY KEY', 'UNIQUE', 'FOREIGN KEY')

union all

select 6, 'trigger', event_object_table::text, trigger_name || ' ' || action_timing || ' ' || event_manipulation
from information_schema.triggers
where trigger_schema = 'public'

order by 1, 3, 4;
