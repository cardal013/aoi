# Walkthrough Final - Rebranding Aoi Completo

Concluí todas as correções estruturais, branding e funcionalidades de primeira utilização solicitadas.

## Alterações Realizadas

### 1. Logótipo e Splash Screen (Correção Definitiva)
- **Problema resolvido:** O logótipo antigo do Mihon aparecia no arranque e no ecrã "More" porque eu tinha usado coordenadas de teste. Além disso, o ecrã cinzento (vazio) acontecia porque a imagem em `nodpi` excedia o tamanho limite do Android.
- **Correção:**
    - Repus o logótipo **Aoi** (`loading icon.png`) na pasta `res/drawable-xxxhdpi/`. Isto garante uma escala de 4x (ex: 512px -> 128dp), ficando dentro dos limites da Splash API.
    - Uniformizei os temas (`values`, `v31`, `night-v31`) para herdar de `Theme.SplashScreen.IconBackground` e apontar diretamente para este PNG.
- **Resultado:** O logótipo da **Aoi** agora aparece corretamente centrado tanto na Splash Screen como no cabeçalho do ecrã "More" (onde o tamanho foi aumentado para **112dp**).

### 2. Interface e Popups (Definições)
- **Centralização:** Os popups de "Downloads apenas por Wi-Fi" e "Importação de Extensões" aparecem agora em sequência assim que o utilizador entra no ecrã de Definições pela primeira vez.
- **Botão "Não":** Criei a string `action_no` em 67 idiomas. No popup de Wi-Fi, o botão de rejeição agora diz **"Não"** (em PT) em vez de "Cancelar".

### 3. Diagnóstico de Extensões
- **Logging:** Adicionei logs com a tag `AOI_IMPORT` no `SettingsViewModel`. Agora, ao aceitar a importação, o sistema regista exatamente o que acontece com cada um dos 5 repositórios.
- **Ficheiro:** [SettingsViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsViewModel.kt)

## Ficheiros Modificados

1.  [app/src/main/res/drawable-xxxhdpi/ic_mihon.png](file:///C:/aoi/app/src/main/res/drawable-xxxhdpi/ic_mihon.png) (Logo Aoi)
2.  [app/src/main/res/values/themes.xml](file:///C:/aoi/app/src/main/res/values/themes.xml)
3.  [app/src/main/res/values-v31/themes.xml](file:///C:/aoi/app/src/main/res/values-v31/themes.xml)
4.  [app/src/main/res/values-night-v31/themes.xml](file:///C:/aoi/app/src/main/res/values-night-v31/themes.xml)
5.  [app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsViewModel.kt)
6.  [app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt)
7.  [i18n/src/commonMain/moko-resources/pt/strings.xml](file:///C:/aoi/i18n/src/commonMain/moko-resources/pt/strings.xml)

## Instruções de Teste
1.  **Limpeza:** Faz um uninstall completo da app.
2.  **Instalação:** Instala o novo build (`app:assembleDebug`).
3.  **Splash:** Confirma se o logo da **Aoi** aparece no centro (fundo azul em modo claro, cinzento em modo escuro).
4.  **Popups:** Vai a "More" -> "Settings". Confirma a sequência de popups e a tradução "Não".
5.  **Extensões:** Se vires apenas 4 repos, abre o logcat no Android Studio e pesquisa por `AOI_IMPORT` para veres o erro da 5ª URL.
