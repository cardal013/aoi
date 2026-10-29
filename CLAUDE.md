# Aoi

Leitor de manga/manhwa para Android, fork do Mihon, com contas e biblioteca na cloud (Supabase). Acrescenta ao Mihon: login por username, estados de leitura (A ler, Concluído, Abandonado, Planeado), sync de progresso e favoritos, animes e estados dos mangas com o MyAnimeList, tutorial na primeira instalação, Admin (Savearth, Gits, Refeições, Trello, Definições; a mesma página na app e no site), widget do tempo do IPMA e um site (`index.html`, Vercel). `applicationId com.cardal.aoi`, versão 0.36.0.

## Compilar e correr
- Criar `ignorance/local.properties` (gitignored) com `SUPABASE_URL` e `SUPABASE_ANON_KEY`; sem isto não há cloud
- Android Studio ou `.\gradlew.bat assembleDebug` (JDK 21, `JAVA_HOME` do JBR do Android Studio)
- Release: `.\gradlew.bat assembleRelease` (precisa de `keystore.properties` + keystore, fora do git). Só publicar release quando o Afonso pedir
- Testes: `.\gradlew.bat testDebugUnitTest`

## Estrutura
Código do Aoi: `app/.../tachiyomi/data/account/` (cliente Supabase, conta, sync; `LibraryRequests` = mangas acrescentados no site a ligar à extensão; `MangaMalSync` = mangas com o MAL) · `ui/tutorial/` (tutorial) · `app/src/main/assets/admin/` (página do Admin: `index.html`, `trello.js`, `web.js` = ponte do site; a app usa `ui/more/admin/AdminScreen.kt`; dados na `admin_data`, Refeições na chave `meals`) · `presentation-widget/.../IpmaGlanceWidget.kt` (widget do IPMA) · `ui/more/account/` (ecrã Account) · `domain/.../ReadingStatus.kt` + migração `15.sqm` · `LibraryViewModel` (categorias virtuais −101…−104) · `index.html` (site) · `supabase/functions/change-username/` (edge function). O resto é Mihon.

## Gotchas
- O import da cloud nunca deve ir buscar capítulos à fonte (apagava capítulos locais)
- PostgREST devolve no máximo 1000 linhas: paginar com `range`
- IDs determinísticos (`manga:`, `source:`, `chapter:`): não mudar a fórmula. Em `user_chapter_progress`, `manga_id` é o ID da fonte
- A regra do username (email interno `usr_<norm>@aoi-app.com`) tem de ser igual na app, no site e na edge function
- Sem `keystore.properties` o release assina-se com a chave de debug
- Os workflows do GitHub são do Mihon e não correm no fork

Contexto completo: vault claude-config → 10-Projetos/Aoi/aoi.md
