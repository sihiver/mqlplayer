package com.sihiver.mqltv

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.sihiver.mqltv.tv.TvHomeRecommendations

/**
 * Sinkronkan saluran beranda TV saat seluruh app masuk background (mis. tekan Home).
 * Google TV Launcher sering baru refresh baris preview setelah lifecycle ini.
 */
class MqltvApplication : Application() {

    private var startedActivityCount = 0

    override fun onCreate() {
        super.onCreate()
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                startedActivityCount++
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivityCount--
                if (startedActivityCount <= 0) {
                    startedActivityCount = 0
                    TvHomeRecommendations.syncForLauncherRefresh(applicationContext)
                }
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityResumed(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
