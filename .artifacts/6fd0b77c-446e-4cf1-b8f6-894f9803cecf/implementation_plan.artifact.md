# Plano de Correção - Splash Screen, Popups e Extensões

Este plano detalha as correções para a Splash Screen (focando na compatibilidade com Samsung/Android 12), o ajuste de texto no popup de Wi-Fi e a adição de logging para depurar a importação de extensões.

## User Review Required

> [!IMPORTANT]
> **Splash Screen (Correção Estrutural):** O diagnóstico indica que o uso de um `layer-list` no tema da Splash Screen impede a visualização do ícone em dispositivos Samsung com Android 12. Vou alterar o tema para apontar **diretamente** para o vetor de teste (`ic_test_vector`), eliminando o wrapper XML intermédio.
>
> **Logging de Extensões:** Vou adicionar logs detalhados. Precisarei que tentes a importação novamente após o build para identificarmos qual URL falha.

## Proposed Changes

### [I18N]

#### [MODIFY] [SettingsMainScreen.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/settings/screen/SettingsMainScreen.kt)
- Alterar o texto do botão de rejeição de `action_cancel` para `action_no` (que já foi traduzido para todos os idiomas).

### [UI / Settings]

#### [MODIFY] [SettingsViewModel.kt](file:///C:/aoi/app/src/main/java/eu/kanade/tachiyomi/ui/setting/SettingsViewModel.kt)
- Adicionar logs (`logcat`) em `importDefaultRepositories()` para capturar o resultado de cada uma das 5 URLs.

### [Resources / Themes]

#### [MODIFY] [themes.xml](file:///C:/aoi/app/src/main/res/values/themes.xml)
- Alterar `windowSplashScreenAnimatedIcon` de `@drawable/ic_mihon_splash` para `@drawable/ic_test_vector`.

#### [DELETE] [ic_mihon_splash.xml](file:///C:/aoi/app/src/main/res/drawable/ic_mihon_splash.xml)
- Remover este ficheiro para evitar confusão, uma vez que o tema passará a apontar diretamente para o vetor.

---

## Verification Plan

### 1. Splash Screen
- Fazer build e verificar se o ícone vermelho (vetor de teste) aparece no arranque. Se aparecer, confirmamos que a correção estrutural funciona.

### 2. Texto do Popup
- Entrar nas Definições e confirmar se o botão diz "Não" (em PT).

### 3. Importação de Extensões
- Aceitar a importação e verificar os logs para identificar a URL em falta.
