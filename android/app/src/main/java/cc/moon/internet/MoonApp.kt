package cc.moon.internet

import android.app.Application
import android.content.Context
import cc.moon.internet.data.Lang
import cc.moon.internet.data.LogStore

class MoonApp : Application() {
    // The service builds its notification off the application context, so the language has to be
    // applied here too — not only on the activity.
    override fun attachBaseContext(base: Context) = super.attachBaseContext(Lang.wrap(base))

    override fun onCreate() {
        super.onCreate()
        // Before anything opens the file: a debug level left on overnight would otherwise grow
        // without limit, and the retention the Логи page offers has to be honoured by someone.
        cc.moon.internet.data.SubscriptionService.loadUnits(this)
        // whatever the updater downloaded has done its job by the time we run again
        cc.moon.internet.data.ApkInstaller.cleanUp(this)
        LogStore.prune(this, keepDays = 7)
    }
}
