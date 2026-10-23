package eu.kanade.tachiyomi.ui.anime

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.data.anime.MalAnimeSync
import eu.kanade.tachiyomi.ui.main.MainActivity
import mihon.app.di.appGraph

/**
 * Recebe o regresso do login no MyAnimeList dos animes (aoi://mal-anime-auth?mal=connected|denied|expired|error),
 * para onde a edge function `mal` manda o browser no fim, e volta à app.
 * Não é o aoi://myanimelist-auth do tracker de manga do Mihon (TrackLoginActivity), que não se mexe.
 */
class MalAuthActivity : Activity() {

    @Inject private lateinit var malAnimeSync: MalAnimeSync

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph.inject(this)

        val loginResult = intent.data?.getQueryParameter("mal")
        malAnimeSync.onLoginResult(loginResult)

        val backToApp = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(backToApp)
        finish()
    }
}
