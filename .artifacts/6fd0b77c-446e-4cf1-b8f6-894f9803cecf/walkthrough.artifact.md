# Walkthrough - Ajustes Finais de Branding (Logo e Splash)

Concluí os ajustes no tamanho do logótipo interno e a correção do problema de visibilidade da Splash Screen.

## Alterações Realizadas

### 1. Logo no Ecrã "More"
- **Aumento de Tamanho:** No ficheiro `LogoHeader.kt`, o tamanho do ícone foi aumentado de **64.dp** para **112.dp** para garantir uma presença visual mais forte no topo do ecrã "More".
- **Ficheiro:** [LogoHeader.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/LogoHeader.kt)

### 2. Correção da Splash Screen
- **Visibilidade:** O problema do logótipo não aparecer (ecrã apenas cinzento/azul) foi resolvido movendo o recurso `ic_mihon.png` para a pasta **`drawable-nodpi`**. Isto evita que o sistema aplique escalas de densidade que podem ultrapassar os limites da Splash Screen API.
- **Ajuste de Escala:** O ficheiro `ic_mihon_splash.xml` foi configurado com um tamanho fixo de **160dp** (o padrão recomendado pela Google), garantindo que o ícone aparece centrado e sem zoom excessivo.
- **Fundo:** Confirmado que o fundo segue a cor `@color/splash`, que alterna entre azul (dia) e cinzento escuro (noite).

### 3. Verificação de Temas
- Verificados os ficheiros `themes.xml` (incluindo variações v31/v33). As referências para `windowSplashScreenAnimatedIcon` e `windowSplashScreenBackground` estão corretas e consistentes em todo o projeto.

## Ficheiros Modificados

1.  [app/src/main/java/eu/kanade/presentation/more/LogoHeader.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/LogoHeader.kt)
2.  [app/src/main/res/drawable-nodpi/ic_mihon.png](file:///C:/aoi/app/src/main/res/drawable-nodpi/ic_mihon.png) (Novo local/PNG direto)
3.  [app/src/main/res/drawable/ic_mihon_splash.xml](file:///C:/aoi/app/src/main/res/drawable/ic_mihon_splash.xml)
4.  [app/src/main/res/values/themes.xml](file:///C:/aoi/app/src/main/res/values/themes.xml) (Verificação)

## Verificação Técnica
- **Build:** Executado `gradle assembleDebug` com sucesso.
- **Recursos:** O recurso `R.drawable.ic_mihon` agora é um PNG direto, o que garante compatibilidade total com o Jetpack Compose.
- **Splash API:** O tamanho de 160dp no `layer-list` está dentro das especificações do Android 12+.
