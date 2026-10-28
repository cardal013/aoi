package eu.kanade.tachiyomi.ui.anime.watch

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import android.app.AlertDialog
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import dev.zacsweers.metro.Inject
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.data.anime.AnimeDownloads
import eu.kanade.tachiyomi.data.anime.AnimeExtensions
import eu.kanade.tachiyomi.data.anime.AnimeLinks
import eu.kanade.tachiyomi.data.anime.AnimeProgress
import eu.kanade.tachiyomi.data.anime.AnimeVideos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import logcat.LogPriority
import mihon.app.di.appGraph
import tachiyomi.core.common.util.lang.withIOContext
import tachiyomi.core.common.util.system.logcat

/**
 * Player dos episódios (Media3), em ecrã inteiro e na horizontal. Um episódio transferido vê-se da cache,
 * sem rede. Retoma onde se parou, tocar duas vezes salta 10 s e aos 85% conta o episódio no MAL.
 */
@UnstableApi
class AnimePlayerActivity : ComponentActivity() {

    @Inject private lateinit var extensions: AnimeExtensions

    @Inject private lateinit var downloads: AnimeDownloads

    @Inject private lateinit var links: AnimeLinks

    @Inject private lateinit var progress: AnimeProgress

    private lateinit var playerView: PlayerView
    private lateinit var message: TextView
    private lateinit var qualityButton: Button
    private var player: ExoPlayer? = null
    private var videos: List<Video> = emptyList()
    private var current: Video? = null
    private var marked = false

    private val queueUrls by lazy { intent.getStringArrayExtra(EXTRA_QUEUE_URLS).orEmpty().toList() }
    private val queueNames by lazy { intent.getStringArrayExtra(EXTRA_QUEUE_NAMES).orEmpty().toList() }
    private val queueNumbers by lazy { intent.getFloatArrayExtra(EXTRA_QUEUE_NUMBERS)?.toList().orEmpty() }

    // O episódio que está a dar; quando acaba passa ao seguinte da fila
    private var queueIndex = -1
    private val episodeUrl get() = if (queueIndex < 0) intent.getStringExtra(EXTRA_EPISODE_URL)!! else queueUrls[queueIndex]
    private val episodeName get() = if (queueIndex < 0) intent.getStringExtra(EXTRA_EPISODE_NAME).orEmpty() else queueNames[queueIndex]
    private val episodeNumber get() = if (queueIndex < 0) intent.getFloatExtra(EXTRA_EPISODE_NUMBER, -1f) else queueNumbers[queueIndex]

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appGraph.inject(this)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContentView(buildLayout())
        lifecycleScope.launch { load() }
        lifecycleScope.launch {
            while (isActive) {
                delay(2_000)
                checkProgress()
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun buildLayout(): FrameLayout {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        playerView = PlayerView(this).apply {
            setShowNextButton(false)
            setShowPreviousButton(false)
            setShowSubtitleButton(true)
            keepScreenOn = true
        }
        val detector = GestureDetector(
            this,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    val p = player ?: return false
                    val forward = e.x > playerView.width / 2
                    p.seekTo((p.currentPosition + if (forward) SEEK_MS else -SEEK_MS).coerceAtLeast(0))
                    Toast.makeText(this@AnimePlayerActivity, if (forward) "+10 s" else "−10 s", Toast.LENGTH_SHORT).show()
                    return true
                }
            },
        )
        playerView.setOnTouchListener { _, event ->
            detector.onTouchEvent(event)
            false
        }
        message = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            text = getString(eu.kanade.tachiyomi.R.string.anime_player_loading)
        }
        qualityButton = Button(this).apply {
            text = getString(eu.kanade.tachiyomi.R.string.anime_player_quality)
            alpha = 0.85f
            visibility = Button.GONE
            setOnClickListener { chooseVideo() }
        }
        root.addView(playerView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        root.addView(
            message,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        root.addView(
            qualityButton,
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END)
                .apply { setMargins(24, 24, 24, 24) },
        )
        return root
    }

    private suspend fun load() {
        val downloaded = downloads.downloaded(episodeUrl)
        if (downloaded != null) {
            play(downloaded.request.toMediaItem())
            return
        }
        val source = extensions.source(intent.getLongExtra(EXTRA_SOURCE_ID, 0))
        if (source == null) {
            message.setText(eu.kanade.tachiyomi.R.string.anime_player_no_source)
            return
        }
        try {
            val episode = SEpisode.create().apply {
                url = episodeUrl
                name = episodeName
            }
            videos = withIOContext { extensions.guard(source) { AnimeVideos.load(source, episode) } }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Anime: não deu para obter os vídeos" }
        }
        if (videos.isEmpty()) {
            message.setText(eu.kanade.tachiyomi.R.string.anime_player_no_videos)
            return
        }
        qualityButton.visibility = Button.VISIBLE
        playVideo(videos.first())
    }

    private fun playVideo(video: Video) {
        val source = extensions.source(intent.getLongExtra(EXTRA_SOURCE_ID, 0)) ?: return
        current = video
        // Os cabeçalhos da fonte são código da extensão: se rebentar, tenta sem eles
        val headers = try {
            AnimeVideos.headers(source, video)
        } catch (e: Throwable) {
            logcat(LogPriority.WARN, e) { "Anime: cabeçalhos de ${source.name} falharam" }
            emptyMap()
        }
        downloads.saveHeaders(video.videoUrl, headers)
        video.subtitleTracks.forEach { downloads.saveHeaders(it.url, headers) }
        play(AnimeVideos.mediaItem(episodeUrl, video))
    }

    private fun play(item: MediaItem) {
        message.text = ""
        val start = player?.currentPosition?.takeIf { it > 0 } ?: links.position(episodeUrl)
        player?.release()
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(downloads.playerDataSource))
            .build()
            .also { p ->
                playerView.player = p
                p.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            message.text = getString(eu.kanade.tachiyomi.R.string.anime_player_error, error.errorCodeName)
                        }

                        override fun onPlaybackStateChanged(state: Int) {
                            if (state == Player.STATE_ENDED) {
                                checkProgress(ended = true)
                                playNext()
                            }
                        }
                    },
                )
                p.setMediaItem(item, start)
                p.prepare()
                p.play()
            }
    }

    /** Acabou o episódio: passa ao seguinte da fila, do princípio (ou fecha se não houver). */
    private fun playNext() {
        if (queueIndex + 1 >= queueUrls.size) {
            finish()
            return
        }
        queueIndex++
        marked = false
        videos = emptyList()
        current = null
        player?.release()
        player = null
        qualityButton.visibility = Button.GONE
        message.setText(eu.kanade.tachiyomi.R.string.anime_player_loading)
        lifecycleScope.launch { load() }
    }

    private fun chooseVideo() {
        if (videos.isEmpty()) return
        val names = videos.map { it.videoTitle.ifBlank { it.videoUrl.substringAfter("://").substringBefore('/') } }
        AlertDialog.Builder(this)
            .setTitle(eu.kanade.tachiyomi.R.string.anime_player_quality)
            .setSingleChoiceItems(names.toTypedArray(), videos.indexOf(current)) { dialog, which ->
                dialog.dismiss()
                playVideo(videos[which])
            }
            .show()
    }

    private fun checkProgress(ended: Boolean = false) {
        val p = player ?: return
        val duration = p.duration.takeIf { it > 0 } ?: return
        links.savePosition(episodeUrl, if (ended) 0 else p.currentPosition)
        if (!marked && (ended || p.currentPosition >= duration * WATCHED_AT)) {
            marked = true
            val malId = intent.getLongExtra(EXTRA_MAL_ID, 0)
            val number = episodeNumber
            if (malId > 0 && number > 0) lifecycleScope.launch { withIOContext { progress.markWatched(malId, number) } }
        }
    }

    override fun onStop() {
        checkProgress()
        player?.pause()
        super.onStop()
    }

    override fun onDestroy() {
        player?.release()
        player = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_SOURCE_ID = "source_id"
        private const val EXTRA_EPISODE_URL = "episode_url"
        private const val EXTRA_EPISODE_NAME = "episode_name"
        private const val EXTRA_EPISODE_NUMBER = "episode_number"
        private const val EXTRA_MAL_ID = "mal_id"
        private const val EXTRA_QUEUE_URLS = "queue_urls"
        private const val EXTRA_QUEUE_NAMES = "queue_names"
        private const val EXTRA_QUEUE_NUMBERS = "queue_numbers"
        private const val SEEK_MS = 10_000L
        private const val WATCHED_AT = 0.85

        /** [next] são os episódios que vêm a seguir, pela ordem: tocam sozinhos quando o anterior acaba. */
        fun intent(context: Context, sourceId: Long, malId: Long, episode: SEpisode, next: List<SEpisode> = emptyList()): Intent =
            Intent(context, AnimePlayerActivity::class.java)
                .putExtra(EXTRA_SOURCE_ID, sourceId)
                .putExtra(EXTRA_EPISODE_URL, episode.url)
                .putExtra(EXTRA_EPISODE_NAME, episode.name)
                .putExtra(EXTRA_EPISODE_NUMBER, episode.episode_number)
                .putExtra(EXTRA_MAL_ID, malId)
                .putExtra(EXTRA_QUEUE_URLS, next.map { it.url }.toTypedArray())
                .putExtra(EXTRA_QUEUE_NAMES, next.map { it.name }.toTypedArray())
                .putExtra(EXTRA_QUEUE_NUMBERS, next.map { it.episode_number }.toFloatArray())
    }
}
