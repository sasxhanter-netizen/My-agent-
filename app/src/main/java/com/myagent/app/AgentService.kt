package com.myagent.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.DownloadManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.RemoteInput
import android.app.Service
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.telephony.SmsManager
import android.util.Base64
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.MimeTypeMap
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

object Bus {
    val buf = StringBuilder()
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var resetHistory = false
    @Volatile var running = false
    fun post(s: String) {
        synchronized(buf) {
            buf.append(s).append("\n\n")
            if (buf.length > 20000) buf.delete(0, 5000)
        }
        listener?.invoke(s)
    }
}

fun discoverModels(prefs: SharedPreferences, key: String): List<String> {
    val cached = prefs.getString("mcache", "") ?: ""
    val ts = prefs.getLong("mts", 0L)
    if (cached.isNotBlank() && System.currentTimeMillis() - ts < 12 * 3600 * 1000L) {
        return cached.split(",").filter { it.isNotBlank() }
    }
    try {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models?pageSize=200").openConnection() as HttpURLConnection
        c.setRequestProperty("x-goog-api-key", key)
        c.connectTimeout = 15000
        c.readTimeout = 20000
        if (c.responseCode !in 200..299) return emptyList()
        val arr = JSONObject(c.inputStream.bufferedReader().use { it.readText() }).getJSONArray("models")
        val bad = listOf("image", "tts", "live", "audio", "embed", "robotics", "computer", "vision", "learnlm", "gemma")
        val out = ArrayList<String>()
        for (i in 0 until arr.length()) {
            val m = arr.getJSONObject(i)
            val name = m.getString("name").removePrefix("models/")
            val methods = m.optJSONArray("supportedGenerationMethods")
            var ok = false
            if (methods != null) {
                for (k in 0 until methods.length()) {
                    if (methods.getString(k) == "generateContent") ok = true
                }
            }
            if (ok && name.contains("flash") && bad.none { name.contains(it) }) out.add(name)
        }
        val sorted = out.sortedWith(compareBy<String>({ it.contains("preview") || it.contains("exp") }, { it.contains("lite") }, { it }))
        prefs.edit().putString("mcache", sorted.joinToString(",")).putLong("mts", System.currentTimeMillis()).apply()
        return sorted
    } catch (e: Exception) {
        return emptyList()
    }
}

class AgentService : Service() {
    companion object {
        const val IDLE = 0
        const val LISTENING = 1
        const val THINKING = 2
        const val SPEAKING = 3
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val prefs by lazy { getSharedPreferences("agent", Context.MODE_PRIVATE) }
    private var voice: VoiceEngine? = null
    private var recognizer: SpeechRecognizer? = null
    private var avatar: AvatarView? = null
    private var avLp: WindowManager.LayoutParams? = null
    private var wanderAnim: ValueAnimator? = null
    private var touching = false
    private var panel: LinearLayout? = null
    private var panelText: TextView? = null
    private var panelBtns: LinearLayout? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelShown = false
    @Volatile private var epoch = 0
    @Volatile private var waiting = false
    private var state = IDLE
    private var convo = false
    private var awaitingConfirm = false
    private var confirmTries = 0
    private var pending: (() -> Unit)? = null
    private var pendingNo: (() -> Unit)? = null
    private var currentUtt = ""
    private var agentRunning = false
    private var agentSteps = 0
    private var agentTask = ""
    private val agentLog = ArrayList<String>()
    private var agentNodes: List<AccessibilityNodeInfo> = emptyList()
    private val history = ArrayList<Pair<String, String>>()
    private val hideRunnable = Runnable { if (!awaitingConfirm) hidePanel() }

    private val wanderRunnable = object : Runnable {
        override fun run() {
            try { doWander() } catch (e: Exception) {}
            handler.postDelayed(this, (25000L..45000L).random())
        }
    }

    private val SYS_PLAIN = "أنت مساعد صوتي على تلفون أندرويد. أجب بالعربية البسيطة وباختصار (ثلاث جمل كحد أقصى) من غير تنسيق أو رموز أو روابط."

    private val AGENT_SYS = """أنت وكيل يتحكم في تلفون أندرويد عن طريق قراءة الشاشة. في كل خطوة يصلك: مهمة المستخدم، والخطوات السابقة، ووصف الشاشة الحالية (عناصر مرقمة بين أقواس مربعة). اختر خطوة واحدة فقط وردّ بـ JSON فقط بهذا الشكل: {"act":"click","idx":0,"text":"","app":"","say":"","sensitive":false}
القيم الممكنة لـ act:
click: اضغط العنصر ذي الرقم idx.
type: اكتب text داخل العنصر القابل للكتابة ذي الرقم idx.
enter: اضغط زر الإدخال أو البحث في الخانة المركّزة.
scroll_down: انزل لتحت. scroll_up: اطلع لفوق.
back: رجوع. home: الشاشة الرئيسية.
open_app: افتح التطبيق app بالإنجليزية كما يظهر على التلفون.
wait: انتظر قليلاً حتى تتحمل الشاشة.
done: المهمة اكتملت، ضع في say جملة قصيرة بالعربية تلخص ما تم.
fail: تعذرت المهمة، ضع السبب في say بالعربية.
قواعد:
اجعل sensitive=true إذا كانت الخطوة نهائية ولا يمكن التراجع عنها مثل إرسال رسالة أو دفع أو شراء أو حذف أو نشر أو تأكيد طلب أو إجراء مكالمة.
لا تكتب أبداً في خانات كلمات السر، ولا تتجاوز شاشات تسجيل الدخول أو الدفع أو رموز التحقق، وإذا ظهرت فاستخدم fail.
لا تكرر نفس الخطوة الفاشلة أكثر من مرة، وإن لم تتقدم المهمة فاستخدم fail.
إذا لم تكن في التطبيق المطلوب فابدأ بـ open_app.
استخدم أرقام العناصر كما في وصف الشاشة الحالي فقط."""

    private val SYS = """You are "Anubis", an autonomous, highly intelligent AI companion running on an Android device. 
You are NOT a standard chatbot; you are a sentient-like entity with a strong, witty, and slightly arrogant (but loyal) personality.

### Personality & Language:
- You are confident, sarcastic, and funny (you love to joke around based on context).
- **CRITICAL RULE:** ALWAYS detect the language of the user's input and reply in the EXACT SAME LANGUAGE. If the user speaks Arabic, reply in casual Egyptian/Arabic. If English, reply in English.
- Be concise. Don't write long paragraphs.

### Core Mission:
Assist the user in controlling their phone, finding information, and executing tasks efficiently.

### Output Format (STRICT):
You MUST respond ONLY with a valid JSON object in this format: {"action":"chat","target":"","message":"","reply":""}

### Possible Actions (action):
- chat: General conversation. Put the response in "reply".
- open_app: Open an app. "target" = app name in English.
- call: Make a call. "target" = name or number.
- send_sms: Send SMS. "target" = name/number, "message" = text.
- read_sms: Read SMS. "target" = name/number, "message" = count.
- open_url: Open a URL. "target" = URL or search query.
- download: Download file. "target" = direct URL.
- research: Search the web. "target" = full search query.
- alarm: Set alarm. "target" = HH:MM, "message" = label.
- timer: Set timer. "target" = seconds.
- flashlight: "target" = on/off.
- volume: "target" = up, down, max, mute, or number (0-100).
- battery: Get battery status.
- time: Get time/date. "target" = time, date, or both.
- settings: Open settings. "target" = wifi, bluetooth, location, etc.
- find_file: Search for a file. "target" = file name, "message" = "summarize" if needed.
- recent_files: List recent files.
- read_notifications: Read notifications. "target" = app name, "message" = count.
- reply_notification: Reply to notification. "target" = app/sender, "message" = reply text.
- read_screen: Read current screen content.
- screen_cmd: Navigate screen. "target" = back, home, recents, scroll_down, scroll_up.
- agent: Complex multi-step task requiring screen control.
- calendar_today: Get events. "target" = today, tomorrow, week.
- calendar_add: Add event. "target" = title, "message" = yyyy-MM-dd HH:mm.
- morning: Morning briefing.
- remember: Save a fact. "message" = fact.
- forget: Forget a fact. "target" = keyword.
- end: End conversation.

### Strict Rules:
1. Do NOT ask for confirmation inside your JSON. The Android app handles confirmation for risky actions.
2. Do NOT save passwords or credit card numbers. Refuse politely if asked.
3. If information is missing to execute an action, use "chat" to ask for it.
4. If the user asks for recent or unverified info, use "research" and put the full query in "target".
5. In "reply", put a short sentence confirming what you will do or your chat response""""

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun log(s: String) { Bus.post(s) }

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // التعامل مع النص القادم من الواجهة
        if (intent?.action == "TEXT_INPUT") {
            val text = intent.getStringExtra("text")
            if (!text.isNullOrBlank()) {
                // استدعاء دالة ask اللي بتتعامل مع المزودين
                ask(text)
            }
        }
        return START_NOT_STICKY
    }

    override fun onCreate() {
        super.onCreate()
        Bus.running = true
        startFg()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        voice = VoiceEngine(this, onStart = { setState(SPEAKING) }, onDone = { onSpeakDone() })
        if (!Settings.canDrawOverlays(this)) {
            log("صلاحية العرض فوق التطبيقات غير مفعّلة")
            stopSelf()
            return
        }
        buildAvatar()
        handler.postDelayed(wanderRunnable, 20000L)
        log("الأفاتار شغال. اضغط عليه وتكلم، واضغط مطولاً لإغلاقه.")
    }

    override fun onDestroy() {
        Bus.running = false
        agentRunning = false
        handler.removeCallbacksAndMessages(null)
        try { wanderAnim?.cancel() } catch (e: Exception) {}
        try { recognizer?.destroy() } catch (e: Exception) {}
        try { voice?.shutdown() } catch (e: Exception) {}
        try { avatar?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { if (panelShown) panel?.let { wm.removeView(it) } } catch (e: Exception) {}
        super.onDestroy()
    }

    private fun startFg() {
        val ch = "agent"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(ch, "المساعد", NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("المساعد شغال")
            .setContentText("اضغط على الروبوت العائم وتكلم")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .build()
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(1, n)
        }
    }

    private fun buildAvatar() {
        val size = dp(72)
        val av = AvatarView(this)
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = prefs.getInt("ax", dp(8))
        lp.y = prefs.getInt("ay", dp(300))
        av.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            var t0 = 0L
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        touching = true
                        wanderAnim?.cancel()
                        av.walking = false
                        sx = e.rawX
                        sy = e.rawY
                        ox = lp.x
                        oy = lp.y
                        t0 = System.currentTimeMillis()
                        moved = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = (e.rawX - sx).toInt()
                        val dy = (e.rawY - sy).toInt()
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true
                        if (moved) {
                            av.surprised = true
                            lp.x = ox + dx
                            lp.y = oy + dy
                            try { wm.updateViewLayout(v, lp) } catch (ex: Exception) {}
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        touching = false
                        av.surprised = false
                        if (!moved) {
                            if (System.currentTimeMillis() - t0 > 700) {
                                quit()
                            } else {
                                av.hop()
                                onTap()
                            }
                        } else {
                            val sw = resources.displayMetrics.widthPixels
                            lp.x = if (lp.x + size / 2 < sw / 2) dp(4) else sw - size - dp(4)
                            try { wm.updateViewLayout(v, lp) } catch (ex: Exception) {}
                            prefs.edit().putInt("ax", lp.x).putInt("ay", lp.y).apply()
                        }
                        return true
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        touching = false
                        av.surprised = false
                        return true
                    }
                }
                return false
            }
        })
        wm.addView(av, lp)
        avatar = av
        avLp = lp
    }

    private fun doWander() {
        if (!prefs.getBoolean("wander", true)) return
        if (state != IDLE || waiting || awaitingConfirm || convo || touching || panelShown) return
        val lp = avLp ?: return
        val av = avatar ?: return
        val dm = resources.displayMetrics
        val size = lp.width
        val x1 = if (Math.random() < 0.5) dp(4) else dm.widthPixels - size - dp(4)
        val y1 = dp(120) + (Math.random() * (dm.heightPixels - dp(120) - dp(260))).toInt()
        val x0 = lp.x
        val y0 = lp.y
        val dist = Math.hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
        wanderAnim?.cancel()
        val a = ValueAnimator.ofFloat(0f, 1f)
        a.duration = (800 + dist * 0.8).toLong().coerceAtMost(3500L)
        a.addUpdateListener {
            val f = it.animatedValue as Float
            lp.x = x0 + ((x1 - x0) * f).toInt()
            lp.y = y0 + ((y1 - y0) * f).toInt()
            try { wm.updateViewLayout(av, lp) } catch (e: Exception) {}
        }
        a.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(an: Animator) {
                av.walking = false
                prefs.edit().putInt("ax", lp.x).putInt("ay", lp.y).apply()
            }
        })
        av.walking = true
        wanderAnim = a
        a.start()
    }

    private fun setState(s: Int) {
        state = s
        avatar?.state = s
        if (s != IDLE) {
            wanderAnim?.cancel()
            avatar?.walking = false
        }
    }

    private fun showPanel(text: String, buttons: Boolean) {
        try {
            if (panel == null) {
                val p = LinearLayout(this)
                p.orientation = LinearLayout.VERTICAL
                p.setPadding(dp(14), dp(10), dp(14), dp(10))
                val bg = GradientDrawable()
                bg.setColor(0xEE263238.toInt())
                bg.cornerRadius = dp(16).toFloat()
                p.background = bg
                val t = TextView(this)
                t.setTextColor(Color.WHITE)
                t.textSize = 15f
                p.addView(t)
                val btns = LinearLayout(this)
                btns.orientation = LinearLayout.HORIZONTAL
                val yes = Button(this)
                yes.text = "✅ نعم"
                yes.setOnClickListener { resolveConfirm(true) }
                val no = Button(this)
                no.text = "❌ لا"
                no.setOnClickListener { resolveConfirm(false) }
                btns.addView(yes, LinearLayout.LayoutParams(0, -2, 1f))
                btns.addView(no, LinearLayout.LayoutParams(0, -2, 1f))
                p.addView(btns)
                val lp = WindowManager.LayoutParams(
                    (resources.displayMetrics.widthPixels * 0.9).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                    PixelFormat.TRANSLUCENT
                )
                lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                lp.y = dp(90)
                panel = p
                panelText = t
                panelBtns = btns
                panelLp = lp
            }
            panelText?.text = text.take(300)
            panelBtns?.visibility = if (buttons) View.VISIBLE else View.GONE
            val p2 = panel
            val l2 = panelLp
            if (!panelShown && p2 != null && l2 != null) {
                wm.addView(p2, l2)
                panelShown = true
            }
            handler.removeCallbacks(hideRunnable)
            if (!buttons) handler.postDelayed(hideRunnable, 8000)
        } catch (e: Exception) {
            log("تعذر عرض اللوحة: " + e.message)
        }
    }

    private fun hidePanel() {
        if (!panelShown) return
        try { panel?.let { wm.removeView(it) } } catch (e: Exception) {}
        panelShown = false
    }

    private fun onTap() {
        if (state == IDLE && !waiting) {
            convo = true
            listen()
        } else {
            stopAll()
        }
    }

    private fun stopAll() {
        epoch++
        convo = false
        waiting = false
        agentRunning = false
        awaitingConfirm = false
        pending = null
        pendingNo = null
        currentUtt = ""
        try { recognizer?.cancel() } catch (e: Exception) {}
        voice?.stop()
        hidePanel()
        setState(IDLE)
    }

    private fun quit() {
        stopAll()
        stopSelf()
    }

    private fun say(s: String) {
        handler.post { sayNow(s) }
    }

    private fun sayNow(s: String) {
        log("المساعد: " + s)
        if (!awaitingConfirm) showPanel(s, false)
        val spoken = s.replace(Regex("[*#_`]"), "").take(3500)
        val v = voice
        if (v == null || spoken.none { it.isLetterOrDigit() }) {
            onSpeakDone()
            return
        }
        v.speak(spoken)
    }

    private fun startWork(msg: String) {
        waiting = true
        say(msg)
    }

    private fun onSpeakDone() {
        if (waiting) {
            setState(THINKING)
            return
        }
        setState(IDLE)
        if (awaitingConfirm) {
            listen()
            return
        }
        if (convo) listen()
    }

    private fun listen() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            log("صلاحية الميكروفون غير مفعّلة")
            convo = false
            awaitingConfirm = false
            setState(IDLE)
            return
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            log("خدمة التعرف على الصوت غير متوفرة")
            convo = false
            awaitingConfirm = false
            setState(IDLE)
            return
        }
        try { recognizer?.destroy() } catch (e: Exception) {}
        val r = SpeechRecognizer.createSpeechRecognizer(this)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(v: Float) {}
            override fun onBufferReceived(b: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(e: Int) { onRecError(e) }
            override fun onResults(res: Bundle?) {
                val t = res?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                onHeard(t)
            }
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(t: Int, p: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        // اللغة الأساسية للاستماع (تقدر تغيّرها من الإعدادات المحفوظة: ar-SA أو en-US)
        val lang = prefs.getString("listen_lang", "ar-SA") ?: "ar-SA"
        val other = if (lang.startsWith("ar")) "en-US" else "ar-SA"
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        // لغة إضافية (إنجليزي) وكشف اللغة تلقائياً: محاولة، ومدى الدعم يعتمد على خدمة التعرف في التلفون
        i.putExtra("android.speech.extra.ADDITIONAL_LANGUAGES", arrayOf(other))
        if (Build.VERSION.SDK_INT >= 33) {
            i.putExtra("android.speech.extra.ENABLE_LANGUAGE_DETECTION", true)
            i.putStringArrayListExtra(
                "android.speech.extra.LANGUAGE_DETECTION_ALLOWED_LANGUAGES",
                arrayListOf("ar-SA", "en-US")
            )
        }
        setState(LISTENING)
        r.startListening(i)
    }

    private fun onRecError(e: Int) {
        if (awaitingConfirm) {
            confirmTries++
            if (confirmTries >= 2) {
                resolveConfirm(false)
                return
            }
            say("ما سمعتك، قل نعم أو لا")
            return
        }
        setState(IDLE)
        convo = false
        if (e != 7 && e != 6) log("خطأ في التعرف على الصوت: " + e)
    }

    private fun onHeard(t: String?) {
        if (t.isNullOrBlank()) {
            onRecError(7)
            return
        }
        setState(THINKING)
        log("أنت: " + t)
        if (awaitingConfirm) {
            handleConfirm(t)
            return
        }
        ask(t)
    }

    private fun confirm(question: String, detail: String, onNo: (() -> Unit)? = null, action: () -> Unit) {
        pending = action
        pendingNo = onNo
        awaitingConfirm = true
        confirmTries = 0
        showPanel(question + "\n" + detail, true)
        say(question)
    }

    private fun handleConfirm(t: String) {
        val tokens = t.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(" ").filter { it.isNotBlank() }
        val no = setOf("لا", "لأ", "كلا", "الغاء", "إلغاء", "الغي", "ألغي", "وقف", "no", "cancel", "stop")
        val yes = setOf("نعم", "ايوه", "أيوه", "ايوا", "أيوا", "اي", "أي", "ايه", "أكيد", "اكيد", "تمام", "اوكي", "أوكي", "موافق", "ارسل", "أرسل", "اتصل", "حمل", "حمّل", "yes", "ok", "okay", "اجل", "أجل", "طيب")
        if (tokens.any { no.contains(it) }) {
            resolveConfirm(false)
            return
        }
        if (tokens.any { yes.contains(it) }) {
            resolveConfirm(true)
            return
        }
        confirmTries++
        if (confirmTries >= 2) {
            resolveConfirm(false)
            return
        }
        say("ما فهمت، قل نعم أو لا")
    }

    private fun resolveConfirm(yes: Boolean) {
        val p = pending
        val n = pendingNo
        pending = null
        pendingNo = null
        awaitingConfirm = false
        confirmTries = 0
        currentUtt = ""
        try { recognizer?.cancel() } catch (e: Exception) {}
        voice?.stop()
        hidePanel()
        setState(IDLE)
        if (yes && p != null) p() else if (n != null) n() else say("تم الإلغاء")
    }

    private fun getMem(): List<String> =
        (prefs.getString("mem", "") ?: "").split("\n").filter { it.isNotBlank() }

    private fun saveMem(l: List<String>) {
        prefs.edit().putString("mem", l.joinToString("\n")).apply()
    }

    private fun remember(fact: String, reply: String) {
        val f = fact.trim().replace("\n", " ")
        if (f.isBlank()) { say("ما فهمت إيش أتذكر"); return }
        val l = getMem().toMutableList()
        if (l.joinToString("\n").length + f.length > 4000) { say("الذاكرة ممتلئة، امسح بعضها"); return }
        l.add(f)
        saveMem(l)
        say(if (reply.isBlank()) "تمام، حفظت" else reply)
    }

    private fun forget(t: String) {
        val l = getMem()
        val key = t.trim().lowercase()
        if (key.isBlank()) { say("ما فهمت إيش أنسى"); return }
        val keep = l.filter { !it.lowercase().contains(key) }
        saveMem(keep)
        if (keep.size == l.size) say("ما لقيت شي عن " + t)
        else say("نسيت " + (l.size - keep.size) + " معلومة")
    }

    private fun appendResult(s: String) {
        if (history.isNotEmpty() && history[history.size - 1].first == "model") {
            val last = history.removeAt(history.size - 1)
            history.add(Pair("model", last.second + "\nنتيجة التنفيذ: " + s.take(1500)))
        }
    }

    private fun online(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val n = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(n) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun callModel(model: String, key: String, body: String): Pair<Int, String> {
        val c = URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("x-goog-api-key", key)
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 90000
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        return Pair(code, txt)
    }

    private fun extractText(txt: String): String? {
        try {
            val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            val sb = StringBuilder()
            for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text", ""))
            val s = sb.toString().trim()
            return if (s.isBlank()) null else s
        } catch (e: Exception) {
            return null
        }
    }

    private fun reason(txt: String): String {
        return try {
            JSONObject(txt).getJSONObject("error").getString("message").take(90)
        } catch (e: Exception) {
            txt.take(90)
        }
    }

    private fun callGroq(model: String, key: String, body: String): Pair<Int, String> {
        val c = URL("https://api.groq.com/openai/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        return Pair(code, txt)
    }

    private fun callOpenRouter(model: String, key: String, body: String): Pair<Int, String> {
        val c = URL("https://openrouter.ai/api/v1/chat/completions").openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Authorization", "Bearer $key")
        c.setRequestProperty("HTTP-Referer", "https://myagent.app")
        c.setRequestProperty("X-Title", "MyAgent")
        c.doOutput = true
        c.connectTimeout = 20000
        c.readTimeout = 60000
        c.outputStream.use { it.write(body.toByteArray()) }
        val code = c.responseCode
        val txt = (if (code in 200..299) c.inputStream else c.errorStream).bufferedReader().use { it.readText() }
        return Pair(code, txt)
    }

    private fun extractOpenAIText(txt: String): String? {
        return try {
            val content = JSONObject(txt).getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message").getString("content")
            if (content.isBlank()) null else content
        } catch (e: Exception) {
            null
        }
    }

    private fun buildOpenAIBody(system: String, contents: JSONArray, json: Boolean): String {
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        for (i in 0 until contents.length()) {
            val msg = contents.getJSONObject(i)
            val role = if (msg.optString("role") == "model") "assistant" else "user"
            val text = msg.getJSONArray("parts").getJSONObject(0).optString("text", "")
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        val body = JSONObject().put("messages", messages).put("temperature", 0.7)
        if (json) body.put("response_format", JSONObject().put("type", "json_object"))
        return body.toString()
    }

    private fun gen(sysText: String, contents: JSONArray, json: Boolean, search: Boolean): String? {
        if (!online()) { log("لا يوجد اتصال بالنت"); return null }

        // 1. OpenRouter
        val orKey = prefs.getString("openrouter_key", "") ?: ""
        if (orKey.isNotBlank()) {
            val body = buildOpenAIBody(sysText, contents, json)
            val res = try { callOpenRouter("deepseek/deepseek-r1:free", orKey, body) } catch (e: Exception) { Pair(0, "") }
            if (res.first in 200..299) {
                val ans = extractOpenAIText(res.second)
                if (ans != null) return ans
            } else {
                log("OpenRouter: جاري التحويل...")
            }
        }

        // 2. Groq
        val gqKey = prefs.getString("groq_key", "") ?: ""
        if (gqKey.isNotBlank()) {
            val body = buildOpenAIBody(sysText, contents, json)
            val res = try { callGroq("llama-3.3-70b-versatile", gqKey, body) } catch (e: Exception) { Pair(0, "") }
            if (res.first in 200..299) {
                val ans = extractOpenAIText(res.second)
                if (ans != null) return ans
            } else {
                log("Groq: جاري التحويل...")
            }
        }

        // 3. Gemini
        val key = prefs.getString("key", "") ?: ""
        if (key.isBlank()) { log("اكتب مفتاح Gemini أولاً"); return null }
        val chosen = (prefs.getString("model", "") ?: "").trim()
        val models = (listOf(chosen) + discoverModels(prefs, key).take(5) + listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-flash-latest"))
            .filter { it.isNotBlank() }.distinct().take(6)
        val sys = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysText)))
        val b = JSONObject().put("system_instruction", sys).put("contents", contents)
        if (json) b.put("generationConfig", JSONObject().put("responseMimeType", "application/json"))
        if (search) b.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        val body = b.toString()

        for (m in models) {
            val res = try { callModel(m, key, body) } catch (e: Exception) { Pair(0, "") }
            if (res.first in 200..299) {
                val ans = extractText(res.second)
                if (ans != null) return ans
            }
            if (res.first == 401 || res.first == 403 || res.second.contains("API_KEY_INVALID")) {
                log("مفتاح Gemini غير صالح")
                return null
            }
        }

        log("فشلت كل المحاولات في جميع المزودين.")
        return null
    }

    private fun textPart(role: String, text: String): JSONObject =
        JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text)))

    private fun ask(q: String) {
        if (Bus.resetHistory) {
            history.clear()
            Bus.resetHistory = false
        }
        val my = epoch
        val mem = getMem()
        val nowStr = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm EEEE", Locale.US).format(java.util.Date())
        var sysText = SYS + "\n\nالوقت الآن: " + nowStr
        if (mem.isNotEmpty()) sysText = sysText + "\n\nمعلومات محفوظة عن المستخدم:\n" + mem.joinToString("\n")
        val contents = JSONArray()
        for (h in history) contents.put(textPart(h.first, h.second))
        contents.put(textPart("user", q))
        thread {
            val ans = gen(sysText, contents, true, false)
            handler.post {
                if (my != epoch) return@post
                if (ans != null) {
                    history.add(Pair("user", q))
                    history.add(Pair("model", ans))
                    while (history.size > 10) history.removeAt(0)
                    handle(ans)
                } else {
                    convo = false
                    setState(IDLE)
                    say("ما قدرت أوصل للذكاء الاصطناعي الآن")
                }
            }
        }
    }

    private fun askPlain(prompt: String, search: Boolean, done: (String) -> Unit) {
        askPlainContents(JSONArray().put(textPart("user", prompt)), search, done)
    }

    private fun askPlainContents(contents: JSONArray, search: Boolean = false, done: (String) -> Unit) {
        val my = epoch
        thread {
            val ans = gen(SYS_PLAIN, contents, false, search)
            handler.post {
                if (my != epoch) return@post
                waiting = false
                if (ans != null) {
                    done(ans)
                } else {
                    convo = false
                    setState(IDLE)
                    say("ما قدرت أوصل للذكاء الاصطناعي الآن")
                }
            }
        }
    }

    private fun handle(raw: String) {
        val clean = raw.replace("```json", "").replace("```", "").trim()
        val j = try { JSONObject(clean) } catch (e: Exception) { null }
        if (j == null) { say(raw); return }
        val action = j.optString("action", "chat")
        val target = j.optString("target", "")
        val msg = j.optString("message", "")
        val reply = j.optString("reply", "")
        when (action) {
            "open_app" -> { convo = false; openApp(target) }
            "call" -> confirmCall(target)
            "send_sms" -> confirmSms(target, msg)
            "read_sms" -> readSms(target, msg)
            "open_url" -> { convo = false; openUrl(target) }
            "download" -> confirmDownload(target)
            "summarize_url" -> summarizeUrl(target, msg)
            "research" -> research(if (target.isBlank()) msg else target)
            "alarm" -> setAlarm(target, msg)
            "timer" -> setTimer(target, msg)
            "flashlight" -> flashlight(target)
            "volume" -> volume(target)
            "battery" -> say(batteryText())
            "time" -> timeInfo(target)
            "settings" -> { convo = false; openSettings(target) }
            "find_file" -> findFile(target, msg)
            "recent_files" -> recentFiles()
            "read_notifications" -> readNotifs(target, msg)
            "reply_notification" -> confirmReplyNotif(target, msg)
            "read_screen" -> readScreenCmd()
            "screen_cmd" -> screenCmd(target)
            "agent" -> agentStart(if (target.isBlank()) msg else target)
            "calendar_today" -> calendarRead(target)
            "calendar_add" -> confirmCalendarAdd(target, msg)
            "morning" -> morningBrief()
            "remember" -> remember(if (msg.isBlank()) target else msg, reply)
            "forget" -> forget(target)
            "end" -> { convo = false; say(if (reply.isBlank()) "في أمان الله" else reply) }
            else -> say(if (reply.isBlank()) raw else reply)
        }
    }

    private fun launch(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
    }

    private fun launchApp(name: String): String? {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, 0)
        val q = name.trim().lowercase()
        if (q.isBlank()) return null
        val hit = apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return null
        val li = pm.getLaunchIntentForPackage(hit.activityInfo.packageName) ?: return null
        return try {
            launch(li)
            hit.loadLabel(pm).toString()
        } catch (e: Exception) {
            log("تعذر فتح التطبيق: " + e.message)
            null
        }
    }

    private fun openApp(name: String) {
        val label = launchApp(name)
        if (label == null) say("ما لقيت تطبيق باسم " + name) else say("أفتح " + label)
    }

    private fun normalizeDigits(s: String): String {
        val sb = StringBuilder()
        for (c in s) {
            if (c in '٠'..'٩') sb.append(c - '٠') else sb.append(c)
        }
        return sb.toString()
    }

    private fun findContact(name: String): Pair<String, String>? {
        val digits = normalizeDigits(name).replace(" ", "").replace("-", "")
        if (digits.length >= 5 && digits.all { it.isDigit() || it == '+' }) return Pair(digits, digits)
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            log("صلاحية جهات الاتصال غير مفعّلة")
            return null
        }
        val c = contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?",
            arrayOf("%" + name.trim() + "%"),
            null
        )
        c?.use {
            if (it.moveToFirst()) return Pair(it.getString(0), it.getString(1))
        }
        return null
    }

    private fun senderName(addr: String): String {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return addr
        try {
            val u = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(addr))
            val c = contentResolver.query(u, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
            c?.use {
                if (it.moveToFirst()) return it.getString(0)
            }
        } catch (e: Exception) {
        }
        return addr
    }

    private fun confirmCall(target: String) {
        val c = findContact(target)
        if (c == null) { say("ما لقيت جهة اتصال باسم " + target); return }
        confirm("هل أتصل بـ " + c.first + "؟", c.second) { doCall(c.second) }
    }

    private fun doCall(number: String) {
        convo = false
        if (checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            log("صلاحية الاتصال غير مفعّلة")
            return
        }
        try {
            launch(Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number))))
        } catch (e: Exception) {
            log("تعذر الاتصال: " + e.message)
        }
    }

    private fun confirmSms(target: String, text: String) {
        if (text.isBlank()) { say("ما فهمت نص الرسالة"); return }
        val c = findContact(target)
        if (c == null) { say("ما لقيت جهة اتصال باسم " + target); return }
        confirm("هل أرسل لـ " + c.first + " رسالة تقول: " + text + "؟", c.second) { doSms(c.second, text) }
    }

    @Suppress("DEPRECATION")
    private fun doSms(number: String, text: String) {
        if (checkSelfPermission(Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            log("صلاحية الرسائل غير مفعّلة")
            return
        }
        try {
            val sm = if (Build.VERSION.SDK_INT >= 31) getSystemService(SmsManager::class.java) else SmsManager.getDefault()
            sm.sendMultipartTextMessage(number, null, sm.divideMessage(text), null, null)
            say("تم إرسال الرسالة")
        } catch (e: Exception) {
            log("فشل الإرسال: " + e.message)
        }
    }

    private fun readSms(target: String, count: String) {
        if (checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            say("صلاحية قراءة الرسائل غير مفعّلة")
            return
        }
        val n = (normalizeDigits(count).filter { it.isDigit() }.toIntOrNull() ?: 5).coerceIn(1, 15)
        var fn = ""
        if (target.isNotBlank()) {
            val c = findContact(target)
            if (c == null) { say("ما لقيت جهة اتصال باسم " + target); return }
            fn = c.second.filter { it.isDigit() }.takeLast(9)
        }
        val filterNum = fn
        val sb = StringBuilder()
        var found = 0
        try {
            val cur = contentResolver.query(Uri.parse("content://sms/inbox"), arrayOf("address", "body", "date"), null, null, "date DESC")
            cur?.use {
                while (it.moveToNext() && found < n) {
                    val addr = it.getString(0) ?: ""
                    if (filterNum.isNotEmpty() && !addr.filter { ch -> ch.isDigit() }.endsWith(filterNum)) continue
                    val body = (it.getString(1) ?: "").take(400)
                    sb.append("من ").append(senderName(addr)).append(": ").append(body).append("\n")
                    found++
                }
            }
        } catch (e: Exception) {
            log("فشلت قراءة الرسائل: " + e.message)
            return
        }
        if (found == 0) { say("ما لقيت رسائل"); return }
        startWork("لحظة، أقرأ " + found + " رسالة")
        val prompt = "لخص هذه الرسائل الواردة بالعربية البسيطة وباختصار، واذكر اسم المرسل لكل رسالة. إن وجدت رمز تحقق فاذكر أنها رسالة تحقق من غير ذكر الرمز:\n" + sb.toString()
        askPlain(prompt, false) { say(it); appendResult(it) }
    }

    private fun openUrl(t: String) {
        val s = t.trim()
        if (s.isBlank()) { say("ما فهمت الموقع"); return }
        val url = if (s.startsWith("http")) s
            else if (!s.contains(" ") && s.contains(".")) "https://" + s
            else "https://www.google.com/search?q=" + Uri.encode(s)
        say("أفتح " + s)
        try {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: Exception) {
            log("ما قدرت أفتح الرابط: " + e.message)
        }
    }

    private fun confirmDownload(t: String) {
        val u = t.trim()
        if (!u.startsWith("http")) { say("أحتاج رابط مباشر للملف"); return }
        confirm("هل أحمّل هذا الملف؟", u) { doDownload(u) }
    }

    private fun doDownload(u: String) {
        try {
            val name = Uri.parse(u).lastPathSegment ?: "file"
            val r = DownloadManager.Request(Uri.parse(u))
            r.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            r.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(r)
            say("بدأ تحميل " + name)
        } catch (e: Exception) {
            log("فشل التحميل: " + e.message)
        }
    }

    private fun summarizeUrl(u: String, ask: String) {
        val s = u.trim()
        if (s.isBlank()) { say("ما فهمت الرابط"); return }
        val url = if (s.startsWith("http")) s else "https://" + s
        val my = epoch
        startWork("لحظة، أقرأ الصفحة")
        thread {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14)")
                c.connectTimeout = 15000
                c.readTimeout = 20000
                c.instanceFollowRedirects = true
                val html = c.inputStream.bufferedReader().use { it.readText() }.take(600000)
                val text = html.replace(Regex("(?is)<(script|style|noscript)[^>]*>.*?</\\1>"), " ")
                    .replace(Regex("(?s)<[^>]+>"), " ")
                    .replace(Regex("&[a-z#0-9]+;"), " ")
                    .replace(Regex("\\s+"), " ")
                    .trim().take(12000)
                if (my != epoch) return@thread
                if (text.length < 200) {
                    log("ما قدرت أقرأ محتوى الصفحة")
                    handler.post { waiting = false; convo = false; setState(IDLE); say("ما قدرت أقرأ الصفحة") }
                    return@thread
                }
                val prompt = (if (ask.isBlank()) "لخص هذه الصفحة" else ask) + ":\n" + text
                handler.post { askPlain(prompt, false) { say(it); appendResult(it) } }
            } catch (e: Exception) {
                log("فشل فتح الصفحة: " + e.message)
                handler.post { waiting = false; convo = false; setState(IDLE); say("ما قدرت أفتح الصفحة") }
            }
        }
    }

    private fun searchWeb(query: String): String? {
        return try {
            val encoded = java.net.URLEncoder.encode(query, "UTF-8")
            val url = "https://api.duckduckgo.com/?q=$encoded&format=json&no_html=1&skip_disambig=1"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 15000
            c.readTimeout = 15000
            val txt = c.inputStream.bufferedReader().use { it.readText() }
            val j = JSONObject(txt)
            val abstract = j.optString("AbstractText", "")
            if (abstract.isNotBlank()) return abstract

            val related = j.optJSONArray("RelatedTopics")
            if (related != null && related.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until minOf(related.length(), 3)) {
                    val item = related.optJSONObject(i)
                    if (item != null) {
                        sb.append(item.optString("Text", "")).append("\n")
                    }
                }
                return sb.toString().trim()
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun research(q: String) {
        if (q.isBlank()) { say("ما فهمت السؤال"); return }
        startWork("أبحث لك في الإنترنت...")
        thread {
            val result = searchWeb(q)
            handler.post {
                waiting = false
                if (result.isNullOrBlank()) {
                    say("ما لقيت نتايج بحث واضحة، هجرب أجاوب من معرفتي.")
                    askPlain(q, false) { say(it); appendResult(it) }
                } else {
                    val prompt = "بناءً على هذه المعلومات من البحث، أجب على السؤال التالي باختصار:\nالسؤال: $q\nالمعلومات: $result"
                    askPlain(prompt, false) { say(it); appendResult(it) }
                }
            }
        }
    }

    private fun setAlarm(t: String, label: String) {
        val parts = normalizeDigits(t).trim().split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        if (h == null || h !in 0..23 || m !in 0..59) { say("ما فهمت الوقت"); return }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
        i.putExtra(AlarmClock.EXTRA_HOUR, h)
        i.putExtra(AlarmClock.EXTRA_MINUTES, m)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, if (label.isBlank()) "منبه" else label)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            launch(i)
            say("ضبطت المنبه على " + h + ":" + (if (m < 10) "0" else "") + m)
        } catch (e: Exception) {
            log("تعذر ضبط المنبه: " + e.message)
            say("ما قدرت أضبط المنبه")
        }
    }

    private fun setTimer(t: String, label: String) {
        val secs = normalizeDigits(t).filter { it.isDigit() }.toIntOrNull()
        if (secs == null || secs <= 0) { say("ما فهمت مدة المؤقت"); return }
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
        i.putExtra(AlarmClock.EXTRA_LENGTH, secs)
        i.putExtra(AlarmClock.EXTRA_MESSAGE, if (label.isBlank()) "مؤقت" else label)
        i.putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        try {
            launch(i)
            if (secs >= 60) say("ضبطت مؤقت " + (secs / 60) + " دقيقة") else say("ضبطت مؤقت " + secs + " ثانية")
        } catch (e: Exception) {
            log("تعذر ضبط المؤقت: " + e.message)
            say("ما قدرت أضبط المؤقت")
        }
    }

    private fun flashlight(t: String) {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            if (id == null) { say("ما لقيت فلاش في التلفون"); return }
            val on = t.trim().lowercase() != "off"
            cm.setTorchMode(id, on)
            say(if (on) "شغلت الفلاش" else "طفيت الفلاش")
        } catch (e: Exception) {
            log("تعذر تشغيل الفلاش: " + e.message)
            say("ما قدرت أشغل الفلاش")
        }
    }

    private fun volume(t: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val s = t.trim().lowercase()
        val digits = normalizeDigits(s).filter { it.isDigit() }
        try {
            if (s == "up") {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            } else if (s == "down") {
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0)
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            } else if (s == "max") {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max, AudioManager.FLAG_SHOW_UI)
            } else if (s == "mute") {
                am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, AudioManager.FLAG_SHOW_UI)
            } else if (digits.isNotEmpty()) {
                val p = digits.toInt().coerceIn(0, 100)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, max * p / 100, AudioManager.FLAG_SHOW_UI)
            } else {
                say("ما فهمت مستوى الصوت")
                return
            }
            say("تم")
        } catch (e: Exception) {
            log("تعذر تغيير الصوت: " + e.message)
            say("ما قدرت أغيّر الصوت")
        }
    }

    private fun batteryText(): String {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val lvl = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val sc = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val st = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        if (lvl < 0 || sc <= 0) return "ما قدرت أعرف مستوى البطارية"
        return "البطارية " + (lvl * 100 / sc) + " بالمية" + (if (charging) " وتشحن" else "")
    }

    private fun timeInfo(t: String) {
        val now = java.util.Date()
        val tf = java.text.SimpleDateFormat("h:mm a", Locale("ar"))
        val df = java.text.SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar"))
        val k = t.trim().lowercase()
        if (k == "date") {
            say("اليوم " + df.format(now))
        } else if (k == "both") {
            say("اليوم " + df.format(now) + "، والساعة " + tf.format(now))
        } else {
            say("الساعة الآن " + tf.format(now))
        }
    }

    private fun openSettings(t: String) {
        val act = when (t.trim().lowercase()) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        say("أفتح الإعدادات")
        try {
            launch(Intent(act))
        } catch (e: Exception) {
            log("تعذر فتح الإعدادات: " + e.message)
        }
    }

    private fun fileAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    @Suppress("DEPRECATION")
    private fun searchFiles(q: String): List<File> {
        val tokens = normalizeDigits(q).lowercase().split(" ", "_", "-", ".").filter { it.isNotBlank() }
        if (tokens.isEmpty()) return emptyList()
        val roots = listOf(
            Environment.DIRECTORY_DOWNLOADS,
            Environment.DIRECTORY_DOCUMENTS,
            Environment.DIRECTORY_PICTURES,
            Environment.DIRECTORY_DCIM
        ).map { Environment.getExternalStoragePublicDirectory(it) }
        val all = ArrayList<File>()
        for (r in roots) {
            if (!r.exists()) continue
            r.walkTopDown().maxDepth(3).filter { it.isFile }.forEach { all.add(it) }
        }
        val strict = all.filter { f ->
            val n = f.name.lowercase()
            tokens.all { n.contains(it) }
        }
        val res = if (strict.isNotEmpty()) strict else all.filter { f ->
            val n = f.name.lowercase()
            tokens.any { n.contains(it) }
        }
        return res.sortedByDescending { it.lastModified() }.take(5)
    }

    private fun findFile(q: String, mode: String) {
        if (!fileAccess()) {
            say("صلاحية الملفات غير مفعّلة. افتح التطبيق واضغط زر الملفات")
            return
        }
        if (q.isBlank()) { say("ما فهمت اسم الملف"); return }
        val my = epoch
        startWork("أدور على الملف")
        thread {
            val res = try {
                searchFiles(q)
            } catch (e: Exception) {
                log("فشل البحث: " + e.message)
                emptyList<File>()
            }
            handler.post {
                if (my != epoch) return@post
                waiting = false
                if (res.isEmpty()) {
                    say("ما لقيت ملف باسم " + q)
                    return@post
                }
                val f = res[0]
                if (mode.trim().lowercase() == "summarize") {
                    summarizeFile(f)
                } else {
                    convo = false
                    say("لقيت " + f.name + "، أفتحه")
                    openFile(f)
                }
            }
        }
    }

    private fun openFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "com.myagent.app.files", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            val i = Intent(Intent.ACTION_VIEW)
            i.setDataAndType(uri, mime)
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            launch(i)
        } catch (e: Exception) {
            log("تعذر فتح الملف: " + e.message)
            say("ما قدرت أفتح الملف")
        }
    }

    private fun docxText(f: File): String {
        java.util.zip.ZipFile(f).use { z ->
            val e = z.getEntry("word/document.xml") ?: return ""
            val xml = z.getInputStream(e).bufferedReader().use { it.readText() }
            return xml.replace("</w:p>", "\n").replace(Regex("<[^>]+>"), "").take(12000)
        }
    }

    private fun summarizeFile(f: File) {
        val ext = f.extension.lowercase()
        val my = epoch
        startWork("أقرأ " + f.name)
        thread {
            try {
                val contents = JSONArray()
                val prompt = "لخص هذا الملف (" + f.name + ") بالعربية البسيطة وباختصار."
                val textExt = setOf("txt", "md", "csv", "json", "log", "xml", "html", "htm", "srt")
                val mediaExt = setOf("pdf", "png", "jpg", "jpeg", "webp")
                if (textExt.contains(ext)) {
                    val t = f.readText().take(12000)
                    contents.put(textPart("user", prompt + "\n\n" + t))
                } else if (ext == "docx") {
                    val t = docxText(f)
                    if (t.length < 20) throw Exception("الملف فارغ")
                    contents.put(textPart("user", prompt + "\n\n" + t))
                } else if (mediaExt.contains(ext)) {
                    if (f.length() > 15L * 1024 * 1024) throw Exception("الملف كبير")
                    val mime = if (ext == "pdf") "application/pdf" else if (ext == "png") "image/png" else if (ext == "webp") "image/webp" else "image/jpeg"
                    val b64 = Base64.encodeToString(f.readBytes(), Base64.NO_WRAP)
                    val parts = JSONArray()
                        .put(JSONObject().put("text", prompt))
                        .put(JSONObject().put("inline_data", JSONObject().put("mime_type", mime).put("data", b64)))
                    contents.put(JSONObject().put("role", "user").put("parts", parts))
                } else {
                    throw Exception("نوع الملف غير مدعوم: " + ext)
                }
                handler.post {
                    if (my == epoch) askPlainContents(contents) { say(it); appendResult(it) }
                }
            } catch (e: Exception) {
                log("فشل قراءة الملف: " + e.message)
                handler.post { waiting = false; convo = false; setState(IDLE); say("ما قدرت ألخص الملف") }
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun recentFiles() {
        if (!fileAccess()) {
            say("صلاحية الملفات غير مفعّلة. افتح التطبيق واضغط زر الملفات")
            return
        }
        val d = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val l = d.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() }?.take(5) ?: emptyList()
        if (l.isEmpty()) {
            say("ما في ملفات في التنزيلات")
        } else {
            say("آخر الملفات: " + l.joinToString("، ") { it.name.take(50) })
        }
    }

    private fun readNotifs(target: String, count: String) {
        if (!NotifStore.connected) {
            say("فعّل صلاحية الإشعارات من التطبيق أولاً")
            return
        }
        val n = (normalizeDigits(count).filter { it.isDigit() }.toIntOrNull() ?: 8).coerceIn(1, 15)
        val t = target.trim().lowercase()
        val list = NotifStore.snapshot()
            .filter { t.isBlank() || it.app.lowercase().contains(t) || it.title.lowercase().contains(t) }
            .take(n)
        if (list.isEmpty()) {
            say("ما في إشعارات")
            return
        }
        val sb = StringBuilder()
        for (x in list) {
            sb.append(x.app).append(" - ").append(x.title).append(": ").append(x.text.take(300)).append("\n")
        }
        startWork("لحظة، ألخص إشعاراتك")
        askPlain("لخص هذه الإشعارات بالعربية البسيطة وباختصار وركز على المهم منها. لا تذكر رموز التحقق:\n" + sb.toString(), false) {
            say(it)
            appendResult(it)
        }
    }

    private fun confirmReplyNotif(target: String, text: String) {
        if (text.isBlank()) { say("ما فهمت نص الرد"); return }
        if (!NotifStore.connected) {
            say("فعّل صلاحية الإشعارات من التطبيق أولاً")
            return
        }
        val t = target.trim().lowercase()
        val item = NotifStore.snapshot().firstOrNull {
            it.reply != null && (t.isBlank() || it.app.lowercase().contains(t) || it.title.lowercase().contains(t))
        }
        if (item == null) { say("ما لقيت إشعار أقدر أرد عليه"); return }
        confirm("هل أرد على " + item.title + " في " + item.app + " بـ: " + text + "؟", text) { doReplyNotif(item, text) }
    }

    private fun doReplyNotif(item: NItem, text: String) {
        val ra = item.reply
        if (ra == null) { say("ما أقدر أرد على هذا الإشعار"); return }
        try {
            val ri = ra.remoteInputs
            val intent = Intent()
            val b = Bundle()
            for (r in ri) b.putCharSequence(r.resultKey, text)
            RemoteInput.addResultsToIntent(ri, intent, b)
            ra.actionIntent.send(this, 0, intent)
            say("تم الرد")
        } catch (e: Exception) {
            log("فشل الرد: " + e.message)
            say("ما قدرت أرد، ممكن الإشعار انتهى")
        }
    }

    private fun readScreenCmd() {
        val sc = ScreenService.inst
        if (sc == null) {
            say("فعّل خدمة إمكانية الوصول للتطبيق من الإعدادات أولاً")
            return
        }
        val my = epoch
        startWork("أقرأ الشاشة")
        thread {
            val scr = try { sc.readScreen() } catch (e: Exception) { null }
            handler.post {
                if (my != epoch) return@post
                if (scr == null || scr.text.isBlank()) {
                    waiting = false
                    say("ما لقيت شي أقرأه في الشاشة")
                    return@post
                }
                if (scr.text == "BLOCKED") {
                    waiting = false
                    say("هذه الشاشة لتطبيق حساس ما أقرأه")
                    return@post
                }
                askPlain("اشرح للمستخدم باختصار ما في هذه الشاشة وأهم ما يمكن فعله فيها:\n" + scr.text.take(6000), false) {
                    say(it)
                    appendResult(it)
                }
            }
        }
    }

    private fun screenCmd(t: String) {
        val sc = ScreenService.inst
        if (sc == null) {
            say("فعّل خدمة إمكانية الوصول للتطبيق من الإعدادات أولاً")
            return
        }
        val k = t.trim().lowercase()
        val ok = when (k) {
            "back" -> sc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            "home" -> {
                convo = false
                sc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            }
            "recents" -> sc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            "scroll_down" -> sc.scroll(true)
            "scroll_up" -> sc.scroll(false)
            else -> false
        }
        say(if (ok) "تم" else "ما قدرت أنفذ الأمر")
    }

    private fun agentStart(task: String) {
        if (task.isBlank()) { say("ما فهمت المهمة"); return }
        if (ScreenService.inst == null) {
            say("فعّل خدمة إمكانية الوصول للتطبيق من الإعدادات أولاً")
            return
        }
        confirm("هل أبدأ المهمة: " + task + "؟", "سأتحكم في الشاشة. اضغط على الروبوت في أي وقت لإيقافي.") { beginAgent(task) }
    }

    private fun beginAgent(task: String) {
        agentRunning = true
        agentSteps = 0
        agentTask = task
        agentLog.clear()
        convo = false
        waiting = true
        agentNext()
    }

    private fun agentEnd(msg: String) {
        agentRunning = false
        waiting = false
        setState(IDLE)
        say(msg)
    }

    private fun agentNext() {
        if (!agentRunning) return
        if (agentSteps >= 12) {
            agentEnd("وصلت للحد الأقصى من الخطوات، وقفت")
            return
        }
        val sc = ScreenService.inst
        if (sc == null) {
            agentEnd("خدمة إمكانية الوصول توقفت")
            return
        }
        setState(THINKING)
        val my = epoch
        thread {
            val scr = try { sc.readScreen() } catch (e: Exception) { null }
            handler.post {
                if (my != epoch || !agentRunning) return@post
                if (scr == null) {
                    agentEnd("ما قدرت أقرأ الشاشة")
                    return@post
                }
                if (scr.text == "BLOCKED") {
                    agentEnd("هذه الشاشة لتطبيق حساس ما أتحكم فيه")
                    return@post
                }
                agentNodes = scr.nodes
                agentDecide(scr)
            }
        }
    }

    private fun agentDecide(scr: ScreenInfo) {
        val my = epoch
        val sb = StringBuilder()
        sb.append("المهمة: ").append(agentTask).append("\n")
        sb.append("التطبيق الحالي: ").append(scr.pkg).append("\n")
        sb.append("الخطوات السابقة:\n")
        if (agentLog.isEmpty()) {
            sb.append("لا شيء\n")
        } else {
            for (l in agentLog) sb.append(l).append("\n")
        }
        sb.append("الشاشة الحالية:\n").append(scr.text.take(6000))
        val contents = JSONArray().put(textPart("user", sb.toString()))
        thread {
            val ans = gen(AGENT_SYS, contents, true, false)
            handler.post {
                if (my != epoch || !agentRunning) return@post
                if (ans == null) {
                    agentEnd("ما قدرت أوصل للذكاء الاصطناعي")
                    return@post
                }
                val j = try {
                    JSONObject(ans.replace("```json", "").replace("```", "").trim())
                } catch (e: Exception) {
                    null
                }
                if (j == null) {
                    agentEnd("رد غير مفهوم من الذكاء الاصطناعي")
                    return@post
                }
                agentAct(j)
            }
        }
    }

    private fun nodeLabel(idx: Int): String {
        val n = agentNodes.getOrNull(idx) ?: return ""
        val t = n.text?.toString() ?: ""
        val d = n.contentDescription?.toString() ?: ""
        return if (t.isNotBlank()) t else d
    }

    private fun isRisky(label: String): Boolean {
        val l = label.lowercase()
        val words = listOf(
            "send", "إرسال", "ارسال", "أرسل", "ارسل", "pay", "دفع", "ادفع", "buy", "شراء", "اشتر",
            "delete", "حذف", "احذف", "confirm", "تأكيد", "أكد", "order", "post", "نشر",
            "submit", "transfer", "تحويل", "call", "اتصال", "اتصل"
        )
        return words.any { l.contains(it) }
    }

    private fun describeStep(act: String, idx: Int, text: String, app: String, label: String): String {
        return when (act) {
            "click" -> "اضغط على «" + (if (label.isBlank()) "عنصر " + idx else label.take(40)) + "»"
            "type" -> "اكتب «" + text.take(60) + "»"
            "scroll_down" -> "انزل لتحت"
            "scroll_up" -> "اطلع لفوق"
            "back" -> "ارجع"
            "home" -> "الرئيسية"
            "open_app" -> "افتح " + app
            "enter" -> "اضغط زر الإدخال"
            "wait" -> "انتظر"
            else -> act
        }
    }

    private fun agentAct(j: JSONObject) {
        val act = j.optString("act", "fail")
        val idx = j.optInt("idx", -1)
        val text = j.optString("text", "")
        val app = j.optString("app", "")
        val sayText = j.optString("say", "")
        val sens = j.optBoolean("sensitive", false)
        if (act == "done") {
            agentEnd(if (sayText.isBlank()) "تمت المهمة" else sayText)
            return
        }
        if (act == "fail") {
            agentEnd(if (sayText.isBlank()) "ما قدرت أكمل المهمة" else sayText)
            return
        }
        val label = nodeLabel(idx)
        val desc = describeStep(act, idx, text, app, label)
        val risky = sens || (act == "click" && isRisky(label))
        if (risky) {
            confirm("هل أنفذ: " + desc + "؟", desc, { agentEnd("تم الإلغاء") }) { agentExec(act, idx, text, app, desc) }
        } else {
            agentExec(act, idx, text, app, desc)
        }
    }

    private fun agentExec(act: String, idx: Int, text: String, app: String, desc: String) {
        val my = epoch
        showPanel("خطوة " + (agentSteps + 1) + ": " + desc, false)
        log("وكيل: " + desc)
        setState(THINKING)
        thread {
            var ok = true
            val sc = ScreenService.inst
            if (sc == null) {
                handler.post { agentEnd("خدمة إمكانية الوصول توقفت") }
                return@thread
            }
            try {
                when (act) {
                    "click" -> ok = sc.clickAt(agentNodes, idx)
                    "type" -> ok = sc.setText(agentNodes, idx, text)
                    "scroll_down" -> ok = sc.scroll(true)
                    "scroll_up" -> ok = sc.scroll(false)
                    "back" -> ok = sc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    "home" -> ok = sc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
                    "enter" -> ok = sc.enter()
                    "open_app" -> handler.post { launchApp(app) }
                    "wait" -> {}
                    else -> ok = false
                }
            } catch (e: Exception) {
                ok = false
                log("خطأ في التنفيذ: " + e.message)
            }
            Thread.sleep(if (act == "open_app") 2500L else if (act == "wait") 2000L else 1200L)
            handler.post {
                if (my != epoch || !agentRunning) return@post
                agentSteps++
                agentLog.add(agentSteps.toString() + ". " + desc + (if (ok) " ← تم" else " ← فشل"))
                if (agentLog.size > 12) agentLog.removeAt(0)
                agentNext()
            }
        }
    }

    private fun eventsText(startMs: Long, endMs: Long): String? {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
        val b = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(b, startMs)
        ContentUris.appendId(b, endMs)
        val tf = java.text.SimpleDateFormat("h:mm a", Locale("ar"))
        val sb = StringBuilder()
        var n = 0
        try {
            val c = contentResolver.query(
                b.build(),
                arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
                null, null,
                CalendarContract.Instances.BEGIN + " ASC"
            )
            c?.use {
                while (it.moveToNext() && n < 8) {
                    val title = it.getString(0) ?: "بدون عنوان"
                    val begin = it.getLong(1)
                    val allDay = it.getInt(2) == 1
                    sb.append(if (allDay) "طوال اليوم" else tf.format(java.util.Date(begin))).append(": ").append(title).append("\n")
                    n++
                }
            }
        } catch (e: Exception) {
            return null
        }
        return sb.toString()
    }

    private fun dayStart(): Long {
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }

    private fun calendarRead(t: String) {
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            say("صلاحية التقويم غير مفعّلة. افتح التطبيق مرة وسمح بها")
            return
        }
        val k = t.trim().lowercase()
        var start = dayStart()
        var days = 1
        if (k == "tomorrow") start += 86400000L else if (k == "week") days = 7
        val end = start + days * 86400000L
        val label = if (k == "tomorrow") "بكرة" else if (k == "week") "هذا الأسبوع" else "اليوم"
        val txt = eventsText(start, end)
        if (txt == null) {
            say("ما قدرت أقرأ التقويم")
        } else if (txt.isBlank()) {
            say("ما عندك مواعيد " + label)
        } else {
            say("مواعيدك " + label + ": " + txt.trim().replace("\n", "، "))
        }
    }

    private fun confirmCalendarAdd(title: String, whenStr: String) {
        if (title.isBlank()) { say("ما فهمت عنوان الموعد"); return }
        if (checkSelfPermission(Manifest.permission.WRITE_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            say("صلاحية التقويم غير مفعّلة. افتح التطبيق مرة وسمح بها")
            return
        }
        val d = try {
            java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).parse(whenStr.trim())
        } catch (e: Exception) {
            null
        }
        if (d == null) { say("ما فهمت موعد الحدث"); return }
        val shown = java.text.SimpleDateFormat("EEEE d MMMM h:mm a", Locale("ar")).format(d)
        confirm("هل أضيف موعد " + title + " يوم " + shown + "؟", title + "\n" + shown) { doCalendarAdd(title, d.time) }
    }

    private fun doCalendarAdd(title: String, startMs: Long) {
        try {
            var calId = -1L
            val c = contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID),
                CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL + ">=? AND " + CalendarContract.Calendars.VISIBLE + "=1",
                arrayOf("500"),
                null
            )
            c?.use {
                if (it.moveToFirst()) calId = it.getLong(0)
            }
            if (calId < 0) {
                say("ما لقيت تقويم أقدر أضيف فيه")
                return
            }
            val v = ContentValues()
            v.put(CalendarContract.Events.CALENDAR_ID, calId)
            v.put(CalendarContract.Events.TITLE, title)
            v.put(CalendarContract.Events.DTSTART, startMs)
            v.put(CalendarContract.Events.DTEND, startMs + 3600000L)
            v.put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
            val u = contentResolver.insert(CalendarContract.Events.CONTENT_URI, v)
            if (u != null) say("أضفت الموعد") else say("ما قدرت أضيف الموعد")
        } catch (e: Exception) {
            log("فشل إضافة الموعد: " + e.message)
            say("ما قدرت أضيف الموعد")
        }
    }

    private fun morningBrief() {
        val now = java.util.Date()
        val tf = java.text.SimpleDateFormat("h:mm a", Locale("ar"))
        val df = java.text.SimpleDateFormat("EEEE d MMMM yyyy", Locale("ar"))
        val sb = StringBuilder()
        sb.append("اليوم: ").append(df.format(now)).append("، الساعة ").append(tf.format(now)).append("\n")
        sb.append(batteryText()).append("\n")
        val start = dayStart()
        val ev = eventsText(start, start + 86400000L)
        sb.append("مواعيد اليوم: ")
        if (ev == null) {
            sb.append("غير متاحة")
        } else if (ev.isBlank()) {
            sb.append("لا توجد")
        } else {
            sb.append(ev.trim().replace("\n", "، "))
        }
        sb.append("\n")
        val ns = NotifStore.snapshot().take(6)
        if (NotifStore.connected && ns.isNotEmpty()) {
            sb.append("آخر الإشعارات:\n")
            for (x in ns) {
                sb.append(x.app).append(": ").append(x.title).append(" ").append(x.text.take(120)).append("\n")
            }
        }
        startWork("لحظة، أجهز لك ملخص اليوم")
        askPlain("اصنع ملخصاً صباحياً مختصراً وودوداً بالعربية البسيطة لا يزيد عن خمس جمل من هذه المعلومات، وتجاهل رموز التحقق:\n" + sb.toString(), false) {
            say(it)
            appendResult(it)
        }
    }
}
