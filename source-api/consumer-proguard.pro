-keep class eu.kanade.tachiyomi.source.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.source.** extends eu.kanade.tachiyomi.source.Source { public protected *; }

-keep,allowoptimization class eu.kanade.tachiyomi.util.JsoupExtensionsKt { public protected *; }

# Aoi: fontes de anime (as extensões do Aniyomi usam estas classes)
-keep class eu.kanade.tachiyomi.animesource.model.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.online.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.utils.** { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.** extends eu.kanade.tachiyomi.animesource.AnimeSource { public protected *; }
-keep class eu.kanade.tachiyomi.animesource.** { public protected *; }
-keep,allowoptimization class fi.iki.elonen.** { public protected *; }
