package plus.efir.tv

import android.app.Application
import plus.efir.tv.data.ChannelRepository

class EfirApp : Application() {

    lateinit var repository: ChannelRepository
        private set

    override fun onCreate() {
        super.onCreate()
        CrashReport.install(this)
        repository = ChannelRepository(this)
    }
}
