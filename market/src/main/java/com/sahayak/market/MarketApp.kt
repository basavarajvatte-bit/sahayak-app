package com.sahayak.market

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.sahayak.app.Lang

/** Marathi wale app (.mr) mein har screen Marathi mein dikhe. Hinglish wale app mein kuch nahi badalta. */
class MarketApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Lang.on = packageName.endsWith(".mr")
        if (!Lang.on) return
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) = Lang.watch(activity.window?.decorView)
            override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        })
    }
}
