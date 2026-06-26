package com.hawwwran.shushly

import android.app.Application
import com.hawwwran.shushly.service.alerting.NotificationChannels
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class ShushlyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NotificationChannels.ensureAll(this)
    }
}
