package com.sahayak.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import org.json.JSONObject

/** Home screen par Sahayak ki to-do list: scroll hoti hai, ○ dabao to kaam ho gaya. */
class TodoWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { id -> manager.updateAppWidget(id, views(context, id)) }
        @Suppress("DEPRECATION")
        manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_DONE) {
            intent.getStringExtra(EXTRA_ID)?.takeIf { it.isNotEmpty() }?.let { Todos.complete(context, it) }
            return
        }
        super.onReceive(context, intent)
    }

    private fun views(context: Context, id: Int): RemoteViews {
        val v = RemoteViews(context.packageName, R.layout.widget_todo)
        val today = Todos.today(context).size
        val all = Todos.pending(context).size
        v.setTextViewText(R.id.widget_count, if (all == 0) "Sab kaam ho gaye" else "Aaj $today kaam  ·  Kul $all")
        val service = Intent(context, TodoWidgetService::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
        service.data = Uri.parse(service.toUri(Intent.URI_INTENT_SCHEME))
        @Suppress("DEPRECATION")
        v.setRemoteAdapter(R.id.widget_list, service)
        v.setEmptyView(R.id.widget_list, R.id.widget_empty)
        // Kaam par ○ dabao: ho gaya. Mutable zaroori, kyunki har line apna id bharti hai
        val done = PendingIntent.getBroadcast(
            context, 0, Intent(context, TodoWidget::class.java).setAction(ACTION_DONE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        )
        v.setPendingIntentTemplate(R.id.widget_list, done)
        // Upar ka naam aur + : Sahayak khule, wahan naya kaam jodo
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        v.setOnClickPendingIntent(R.id.widget_add, open)
        v.setOnClickPendingIntent(R.id.widget_title, open)
        return v
    }

    companion object {
        const val ACTION_DONE = "com.sahayak.app.TODO_WIDGET_DONE"
        const val EXTRA_ID = "todo_id"

        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TodoWidget::class.java))
            if (ids.isNotEmpty()) TodoWidget().onUpdate(context, manager, ids)
        }
    }
}

/** Widget ki list ke liye kaam: din ke hisaab se headings (Overdue, Today, Tomorrow …). */
class TodoWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(private val context: Context) : RemoteViewsFactory {
        /** Har line: heading (item = null) ya kaam. */
        private var rows: List<Pair<String, JSONObject?>> = emptyList()

        override fun onCreate() = Unit

        override fun onDataSetChanged() {
            val out = mutableListOf<Pair<String, JSONObject?>>()
            var group = ""
            Todos.pending(context).forEach { item ->
                val head = Todos.groupOf(item)
                if (head != group) {
                    group = head
                    out.add(head to null)
                }
                out.add(head to item)
            }
            rows = out
        }

        override fun onDestroy() = Unit

        override fun getCount() = rows.size

        override fun getViewAt(position: Int): RemoteViews {
            val (head, item) = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_todo_head)
            if (item == null) {
                return RemoteViews(context.packageName, R.layout.widget_todo_head).apply {
                    setTextViewText(R.id.head_text, if (head == "Overdue") "⏰ Chhoote hue" else head)
                    setTextColor(R.id.head_text, if (head == "Overdue") 0xFFF87171.toInt() else 0xFFFBBF24.toInt())
                }
            }
            return RemoteViews(context.packageName, R.layout.widget_todo_row).apply {
                setTextViewText(R.id.row_text, item.optString("text"))
                val whenText = when {
                    Todos.timed(item) -> "⏰ " + Todos.clock(item.optLong("at"))
                    head == "Overdue" -> Todos.whenText(item)
                    else -> ""
                }
                setTextViewText(R.id.row_when, whenText)
                setViewVisibility(R.id.row_when, if (whenText.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE)
                setOnClickFillInIntent(R.id.row_done, Intent().putExtra(TodoWidget.EXTRA_ID, item.optString("id")))
            }
        }

        override fun getLoadingView(): RemoteViews? = null

        override fun getViewTypeCount() = 2

        override fun getItemId(position: Int) = position.toLong()

        override fun hasStableIds() = false
    }
}
