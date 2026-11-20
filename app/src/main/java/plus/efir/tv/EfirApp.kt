package plus.efir.tv

import android.app.Application
import android.os.Handler
import android.os.Looper
import plus.efir.tv.data.ChannelRepository
import plus.efir.tv.data.EpgGuide
import plus.efir.tv.data.SelfUpdate
import plus.efir.tv.data.SourceHealth
import plus.efir.tv.player.PlaybackLog

class EfirApp : Application() {

    lateinit var repository: ChannelRepository
        private set

    lateinit var epg: EpgGuide
        private set

    lateinit var health: SourceHealth
        private set

    lateinit var update: SelfUpdate
        private set

    /** Playlists, guide lines and old verdicts are disk reads; the screen must not wait on them. */
    @Volatile
    var ready = false
        private set

    /** Fires on the UI thread once the reads above are done — at once if they already are. */
    var onReady: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        repository = ChannelRepository(this)
        epg = EpgGuide(this)
        health = SourceHealth(this)
        update = SelfUpdate(this)
        Thread {
            runCatching { PlaybackLog.attach(this) }
            PlaybackLog.recordSession("приложение запущено")
            runCatching { epg.loadCache() }
            runCatching { health.loadCache() }
            runCatching { repository.preload() }
            ready = true
            main.post { onReady?.invoke() }
        }.apply { isDaemon = true; name = "efir-init" }.start()
    }
}
