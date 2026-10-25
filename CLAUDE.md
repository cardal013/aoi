# Aoi

Leitor de manga/manhwa para Android, fork do Mihon, com contas e biblioteca na cloud (Supabase). Acrescenta ao Mihon: login por username, estados de leitura (A ler, Concluído, Abandonado, Planeado), sync de progresso e favoritos, animes com o MyAnimeList, tutorial na primeira instalação, Refeições (calendário partilhado pelos admins, com lembrete) e um site (`index.html`, Vercel). `applicationId com.cardal.aoi`, versão 0.27.0.

## Compilar e correr
- Criar `ignorance/local.properties` (gitignored) com `SUPABASE_URL` e `SUPABASE_ANON_KEY`; sem isto não há cloud
- Android Studio ou `.\gradlew.bat assembleDebug` (JDK 21, `JAVA_HOME` do JBR do Android Studio)
- Release: `.\gradlew.bat assembleRelease` (precisa de `keystore.properties` + keystore, fora do git). Só publicar release quando o Afonso pedir
- Testes: `.\gradlew.bat testDebugUnitTest`

## Estrutura
Código do Aoi: `app/.../tachiyomi/data/account/` (cliente Supabase, conta, sync) · `ui/tutorial/` (tutorial) · `ui/more/meals/` + `MealsRepository`, `MealReminder`, `MealReminderJob` (Refeições e lembrete, tabelas `admin_meals` e `admin_meal_reminder`) · `ui/more/account/` (ecrã Account) · `domain/.../ReadingStatus.kt` + migração `15.sqm` · `LibraryViewModel` (categorias virtuais −101…−104) · `index.html` (site) · `supabase/functions/change-username/` (edge function). O resto é Mihon.

## Gotchas
- O import da cloud nunca deve ir buscar capítulos à fonte (apagava capítulos locais)
- PostgREST devolve no máximo 1000 linhas: paginar com `range`
- IDs determinísticos (`manga:`, `source:`, `chapter:`): não mudar a fórmula. Em `user_chapter_progress`, `manga_id` é o ID da fonte
- A regra do username (email interno `usr_<norm>@aoi-app.com`) tem de ser igual na app, no site e na edge function
- Sem `keystore.properties` o release assina-se com a chave de debug
- Os workflows do GitHub são do Mihon e não correm no fork

Contexto completo: vault claude-config → 10-Projetos/Aoi/aoi.md
