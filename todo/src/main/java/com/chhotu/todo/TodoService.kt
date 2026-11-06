package com.chhotu.todo

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.WindowManager
import com.sahayak.app.Frames
import com.sahayak.app.TodoPopup

/** Phone unlock hote hi Chhotu to-do dikhata hai. Internet ka use nahi. */
class TodoService : Service() {
    private var popup: TodoPopup? = null
    private val main = Handler(Looper.getMainLooper())

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            main.postDelayed({ showNow() }, UNLOCK_WAIT_MS)
        }
    }

    fun showNow() {
        if (Settings.canDrawOverlays(this)) popup?.show()
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        instance = this
        Frames.load(this)
        popup = TodoPopup(this, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT))
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Chhotu", NotificationManager.IMPORTANCE_MIN))
        val open = PendingIntent.getActivity(this, 0, Intent(this, TodoActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val note = Notification.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_agenda)
            .setContentTitle("Chhotu is ready")
            .setContentText("Your to-do list will appear when you unlock")
            .setContentIntent(open)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTE_ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTE_ID, note)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        unregisterReceiver(unlocked)
        popup?.shutdown()
        popup = null
        instance = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "chhotu"
        private const val NOTE_ID = 1
        private const val UNLOCK_WAIT_MS = 400L
        var instance: TodoService? = null
            private set

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TodoService::class.java))
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) TodoService.start(context)
    }
}
