package plus.efir.tv

import android.app.Application
import plus.efir.tv.data.ChannelRepository
import plus.efir.tv.data.EpgGuide

class EfirApp : Application() {

    lateinit var repository: ChannelRepository
        private set

    lateinit var epg: EpgGuide
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        repository = ChannelRepository(this)
        epg = EpgGuide(this).apply { loadCache() }
    }
}
