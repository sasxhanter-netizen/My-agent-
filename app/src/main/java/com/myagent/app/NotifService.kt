package com.myagent.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NItem(
    val key: String,
    val pkg: String,
    val app: String,
    val title: String,
    val text: String,
    val time: Long,
    val reply: Notification.Action?
)

object NotifStore {
    val items = ArrayList<NItem>()
    @Volatile var connected = false

    fun add(i: NItem) {
        synchronized(items) {
            items.removeAll { it.key == i.key }
            items.add(0, i)
            while (items.size > 40) items.removeAt(items.size - 1)
        }
    }

    fun snapshot(): List<NItem> = synchronized(items) { ArrayList(items) }
}

class NotifService : NotificationListenerService() {
    override fun onListenerConnected() {
        NotifStore.connected = true
        try {
            val act = activeNotifications
            if (act != null) {
                for (s in act) handle(s)
            }
        } catch (e: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        NotifStore.connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn != null) handle(sbn)
    }

    private fun handle(sbn: StatusBarNotification) {
        try {
            val n = sbn.notification
            if (sbn.packageName == packageName) return
            if ((n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
            if (sbn.isOngoing) return
            val ex = n.extras
            val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            val big = ex.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            val text = big ?: ex.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: ""
            if (title.isBlank() && text.isBlank()) return
            var reply: Notification.Action? = null
            val acts = n.actions
            if (acts != null) {
                for (a in acts) {
                    val ri = a.remoteInputs
                    if (ri != null && ri.isNotEmpty()) {
                        reply = a
                        break
                    }
                }
            }
            val app = try {
                packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
            } catch (e: Exception) {
                sbn.packageName
            }
            NotifStore.add(NItem(sbn.key, sbn.packageName, app, title, text, sbn.postTime, reply))
        } catch (e: Exception) {
        }
    }
}
