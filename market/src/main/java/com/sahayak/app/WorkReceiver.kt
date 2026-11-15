package com.sahayak.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Market Watch app ke alarm: khabar, report, bhaav ki jaanch, pattern scan, News Watch, aur phone chalu hone par sab dobara. */
class WorkReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        when (intent.action) {
            Watcher.ACTION_RUN -> Thread { Watcher.run(app, true) }.start()
            Patterns.ACTION -> Thread { Patterns.run(app, true) }.start()
            Market.ACTION_VERIFY -> Thread { Market.verifyAll(app, true) }.start()
            Market.ACTION_REPORT -> Thread { Market.report(app, true) }.start()
            Market.ACTION_NEWS -> Thread { Market.runNews(app, true) }.start()
            else -> {
                val result = goAsync()
                Thread {
                    try {
                        Watcher.rearm(app)
                        Market.rearm(app)
                    } finally {
                        result.finish()
                    }
                }.start()
            }
        }
    }
}
