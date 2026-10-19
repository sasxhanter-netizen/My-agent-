package com.myagent.app

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.*
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var keyBox: EditText
    private lateinit var modelBox: EditText
    private lateinit var status: TextView
    private val prefs by lazy { getSharedPreferences("agent", Context.MODE_PRIVATE) }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.setPadding(40, 80, 40, 40)
        keyBox = EditText(this)
        keyBox.hint = "Gemini API key"
        keyBox.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyBox.setText(prefs.getString("key", ""))
        modelBox = EditText(this)
        modelBox.hint = "Model"
        modelBox.setText(prefs.getString("model", "gemini-2.5-flash"))
        val startBtn = Button(this)
        startBtn.text = "▶️ تشغيل الأفاتار"
        startBtn.textSize = 20f
        startBtn.setOnClickListener { startAgent() }
        val stopBtn = Button(this)
        stopBtn.text = "⏹ إيقاف الأفاتار"
        stopBtn.setOnClickListener {
            stopService(Intent(this, AgentService::class.java))
            Bus.post("تم إيقاف الأفاتار")
        }
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val memBtn = Button(this)
        memBtn.text = "🧠 الذاكرة"
        memBtn.textSize = 12f
        memBtn.setOnClickListener { showMem() }
        val newBtn = Button(this)
        newBtn.text = "🔄 جديدة"
        newBtn.textSize = 12f
        newBtn.setOnClickListener {
            Bus.resetHistory = true
            Bus.post("بدأنا محادثة جديدة")
        }
        val modelsBtn = Button(this)
        modelsBtn.text = "🔃 موديلات"
        modelsBtn.textSize = 12f
        modelsBtn.setOnClickListener {
            savePrefs()
            prefs.edit().putLong("mts", 0L).apply()
            thread {
                val k = prefs.getString("key", "") ?: ""
                if (k.isBlank()) { Bus.post("اكتب المفتاح أولاً"); return@thread }
                val l = discoverModels(prefs, k)
                Bus.post("الموديلات المتاحة لمفتاحك:\n" + (if (l.isEmpty()) "ما قدرت أجيبها" else l.joinToString("\n")))
            }
        }
        row.addView(memBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(newBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(modelsBtn, LinearLayout.LayoutParams(0, -2, 1f))

        val row2 = LinearLayout(this)
        row2.orientation = LinearLayout.HORIZONTAL
        val filesBtn = Button(this)
        filesBtn.text = "📁 الملفات"
        filesBtn.textSize = 12f
        filesBtn.setOnClickListener { openFilesAccess() }
        val notifBtn = Button(this)
        notifBtn.text = "🔔 الإشعارات"
        notifBtn.textSize = 12f
        notifBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                Bus.post("فعّل MyAgent في قائمة الوصول للإشعارات")
            } catch (e: Exception) {
                Bus.post("تعذر فتح الإعدادات: " + e.message)
            }
        }
        val wanderBtn = Button(this)
        wanderBtn.textSize = 12f
        fun refreshWander() {
            wanderBtn.text = if (prefs.getBoolean("wander", true)) "🚶 تجول: شغال" else "🚶 تجول: متوقف"
        }
        wanderBtn.setOnClickListener {
            prefs.edit().putBoolean("wander", !prefs.getBoolean("wander", true)).apply()
            refreshWander()
        }
        refreshWander()
        row2.addView(filesBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(notifBtn, LinearLayout.LayoutParams(0, -2, 1f))
        row2.addView(wanderBtn, LinearLayout.LayoutParams(0, -2, 1f))

        val accBtn = Button(this)
        accBtn.text = "♿ تحكم الشاشة (إمكانية الوصول)"
        accBtn.textSize = 12f
        accBtn.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                Bus.post("فعّل MyAgent من الخدمات المثبتة ثم ارجع")
            } catch (e: Exception) {
                Bus.post("تعذر فتح الإعدادات: " + e.message)
            }
        }

        status = TextView(this)
        status.textSize = 16f
        val scroll = ScrollView(this)
        scroll.addView(status)
        root.addView(keyBox)
        root.addView(modelBox)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(row)
        root.addView(row2)
        root.addView(accBtn)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        val need = allPerms().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
    }

    private fun allPerms(): List<String> {
        val l = ArrayList<String>()
        l.add(Manifest.permission.RECORD_AUDIO)
        l.add(Manifest.permission.READ_CONTACTS)
        l.add(Manifest.permission.CALL_PHONE)
        l.add(Manifest.permission.SEND_SMS)
        l.add(Manifest.permission.READ_SMS)
        l.add(Manifest.permission.READ_CALENDAR)
        l.add(Manifest.permission.WRITE_CALENDAR)
        if (Build.VERSION.SDK_INT >= 33) l.add(Manifest.permission.POST_NOTIFICATIONS)
        return l
    }

    private fun openFilesAccess() {
        try {
            if (Build.VERSION.SDK_INT >= 30) {
                if (android.os.Environment.isExternalStorageManager()) {
                    Bus.post("صلاحية الملفات مفعّلة بالفعل ✅")
                    return
                }
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + packageName)))
                Bus.post("فعّل السماح بالوصول لكل الملفات ثم ارجع")
            } else {
                requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 3)
            }
        } catch (e: Exception) {
            Bus.post("تعذر فتح الإعدادات: " + e.message)
        }
    }

    private fun savePrefs() {
        prefs.edit()
            .putString("key", keyBox.text.toString().trim())
            .putString("model", modelBox.text.toString().trim())
            .apply()
    }

    override fun onResume() {
        super.onResume()
        status.text = synchronized(Bus.buf) { Bus.buf.toString() }
        Bus.listener = { s: String -> runOnUiThread { status.append(s + "\n\n") } }
    }

    override fun onPause() {
        super.onPause()
        Bus.listener = null
        savePrefs()
    }

    private fun startAgent() {
        savePrefs()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Bus.post("اسمح بصلاحية الميكروفون ثم اضغط تشغيل مرة أخرى")
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 2)
            return
        }
        if (!Settings.canDrawOverlays(this)) {
            Bus.post("فعّل خيار العرض فوق التطبيقات الأخرى للتطبيق ثم ارجع واضغط تشغيل")
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + packageName)))
            return
        }
        if ((prefs.getString("key", "") ?: "").isBlank()) {
            Bus.post("اكتب مفتاح Gemini أولاً")
            return
        }
        try {
            startForegroundService(Intent(this, AgentService::class.java))
            Bus.post("تم تشغيل الأفاتار. اضغط زر Home وستجد الروبوت 🤖 فوق الشاشة.")
            Bus.post("إمكانية الوصول: " + (if (ScreenService.inst != null) "مفعّلة ✅" else "غير مفعّلة"))
        } catch (e: Exception) {
            Bus.post("تعذر تشغيل الخدمة: " + e.message)
        }
    }

    private fun showMem() {
        val l = (prefs.getString("mem", "") ?: "").split("\n").filter { it.isNotBlank() }
        val text = if (l.isEmpty()) "الذاكرة فاضية" else l.mapIndexed { i, s -> "" + (i + 1) + ". " + s }.joinToString("\n")
        AlertDialog.Builder(this)
            .setTitle("الذاكرة")
            .setMessage(text)
            .setPositiveButton("إغلاق", null)
            .setNegativeButton("مسح الكل") { _, _ ->
                prefs.edit().putString("mem", "").apply()
                Bus.resetHistory = true
                Bus.post("تم مسح الذاكرة")
            }
            .show()
    }
}
