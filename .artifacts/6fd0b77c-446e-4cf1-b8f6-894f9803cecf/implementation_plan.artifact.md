# Plano de Implementação - Atualização do Logótipo (Splash e More)

Este plano detalha a substituição do logótipo interno da aplicação (usado na Splash Screen e no ecrã "More") pela nova imagem fornecida.

## User Review Required

> [!IMPORTANT]
> O recurso `ic_mihon` é utilizado como **ícone de notificação** em vários serviços (Backup, Library Update, Extension Install). Ao substituir um vetor por um bitmap, o aspeto nas notificações pode mudar. Vou garantir que o ficheiro XML mantém uma estrutura que permita a renderização correta.

## Proposed Changes

### [Resources]

#### [NEW] [ic_mihon_new.png](file:///C:/aoi/app/src/main/res/drawable-nodpi/ic_mihon_new.png)
- Cópia da imagem original `C:\aoi\loading icon.png` para a pasta de recursos.

#### [MODIFY] [ic_mihon.xml](file:///C:/aoi/app/src/main/res/drawable/ic_mihon.xml)
- Substituir o conteúdo `<vector>` por um `<layer-list>` contendo um `<bitmap>` que aponta para `ic_mihon_new`. Isto permite controlar o tamanho (ex: 72dp) e manter a compatibilidade com os locais onde é invocado.

### [Verification of Usage]

#### [VERIFY] [ic_mihon_splash.xml](file:///C:/aoi/app/src/main/res/drawable/ic_mihon_splash.xml)
- Confirmar que continua a apontar para `@drawable/ic_mihon`. Como este ficheiro já centra o ícone com 72dp, a alteração no `ic_mihon.xml` será transparente.

#### [VERIFY] [LogoHeader.kt](file:///C:/aoi/app/src/main/java/eu/kanade/presentation/more/LogoHeader.kt)
- Confirmar que utiliza `R.drawable.ic_mihon`.

---

## Outras Referências Encontradas (Grep)
As seguintes classes também utilizam `ic_mihon` (principalmente para notificações):
- `BackupNotifier.kt`
- `LibraryUpdateNotifier.kt`
- `ExtensionInstallService.kt`

Estas referências serão atualizadas indiretamente ao alterar o ficheiro `ic_mihon.xml`.

---

## Verification Plan

### Manual Verification
- Verificar se a Splash Screen mostra o novo logótipo.
- Verificar se o ecrã "More" (More -> Sobre ou topo da lista) mostra o novo logótipo no cabeçalho.
- Validar se as notificações continuam a exibir o ícone (pode aparecer como um quadrado se o sistema não suportar transparências em bitmaps de notificação, mas manteremos o padrão solicitado).
