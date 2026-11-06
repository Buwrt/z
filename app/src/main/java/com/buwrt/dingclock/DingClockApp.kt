package com.buwrt.dingclock

import android.app.Application
import com.buwrt.dingclock.data.SettingsStore
import com.buwrt.dingclock.schedule.AlarmScheduler
import com.buwrt.dingclock.schedule.CatchUpScheduler
import com.buwrt.dingclock.util.Notifications

class DingClockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)

        val store = SettingsStore(this)
        if (store.load().enabled) {
            AlarmScheduler.reschedule(this, store)
            CatchUpScheduler.ensurePeriodic(this)
        }
    }
}
