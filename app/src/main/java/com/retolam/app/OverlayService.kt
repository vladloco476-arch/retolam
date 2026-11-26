package com.retolam.app

import android.app.*
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.*
import android.widget.*
import kotlinx.coroutines.*
import kotlin.math.abs

class OverlayService : Service() {
    private lateinit var wm: WindowManager
    private var menu: View? = null
    private var draw: EspRenderer? = null
    private var panel: LinearLayout? = null
    private var capture: ScreenCapture? = null
    private var detector: Detector? = null
    private val aim = AimEngine()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var running = false

    override fun onBind(i: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        notif()
        addDraw()
        addMenu()
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        val code = i?.getIntExtra("code", 0) ?: 0
        val data = i?.getParcelableExtra<Intent>("data")
        if (data != null) {
            capture = ScreenCapture(this, code, data).also { it.start() }
            detector = Detector(this)
            startLoop()
        }
        return START_STICKY
    }

    private fun overlayType() =
        if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

    private fun addDraw() {
        draw = EspRenderer(this)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT)
        wm.addView(draw, lp)
    }

    private fun addMenu() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

        panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD0B0F14"))
            setPadding(28, 28, 28, 28)
            visibility = View.GONE
        }
        panel!!.addView(TextView(this).apply {
            text = "RETOLAM"
            setTextColor(Color.parseColor("#FF3B30"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })

        fun sw(t: String, get: () -> Boolean, set: (Boolean) -> Unit) {
            panel!!.addView(Switch(this).apply {
                text = t
                setTextColor(Color.WHITE)
                isChecked = get()
                setOnCheckedChangeListener { _, v -> set(v) }
            })
        }
        sw("Aimbot", { Prefs.aimbot }, { Prefs.aimbot = it; aim.reset() })
        sw("ESP", { Prefs.esp }, { Prefs.esp = it
            if (!it) { draw?.boxes = emptyList(); draw?.invalidate() } })
        sw("Humanizar", { Prefs.humanize }, { Prefs.humanize = it })

        val lbl = TextView(this).apply {
            setTextColor(Color.parseColor("#8A94A6"))
            text = "Humanización: ${Prefs.level}"
        }
        panel!!.addView(lbl)
        panel!!.addView(SeekBar(this).apply {
            max = 4
            progress = Prefs.level - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, f: Boolean) {
                    Prefs.level = p + 1
                    lbl.text = "Humanización: ${p + 1}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        })
        panel!!.addView(Button(this).apply {
            text = "Cerrar"
            setOnClickListener { stopSelf() }
        })

        val bubble = TextView(this).apply {
            text = "R"
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#FF3B30"))
            textSize = 20f
        }

        root.addView(panel)
        root.addView(bubble, LinearLayout.LayoutParams(dp(52), dp(52)))
        menu = root

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            overlayType(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT)
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 40
        lp.y = 180
        wm.addView(root, lp)

        var x0 = 0
        var y0 = 0
        var tx = 0
        var ty = 0
        var moved = false

        bubble.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    x0 = lp.x
                    y0 = lp.y
                    tx = e.rawX.toInt()
                    ty = e.rawY.toInt()
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (abs(e.rawX.toInt() - tx) > 15 || abs(e.rawY.toInt() - ty) > 15) {
                        moved = true
                        lp.x = x0 + (e.rawX.toInt() - tx)
                        lp.y = y0 + (e.rawY.toInt() - ty)
                        wm.updateViewLayout(root, lp)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        panel!!.visibility =
                            if (panel!!.visibility == View.GONE) View.VISIBLE else View.GONE
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun startLoop() {
        running = true
        scope.launch {
            while (running) {
                val bmp = capture?.grab()
                if (bmp != null && detector?.ready == true) {
                    val found = detector!!.detect(bmp)
                    if (Prefs.esp) withContext(Dispatchers.Main) {
                        draw?.boxes = found
                        draw?.invalidate()
                    }
                    if (Prefs.aimbot) doAim(found, bmp.width, bmp.height)
                }
                delay(if (Prefs.aimbot) 16 else 40)
            }
        }
    }

    private suspend fun doAim(boxes: List<Box>, w: Int, h: Int) {
        val a = Accessibility.instance ?: return
        val t = boxes.minByOrNull { kotlin.math.abs(it.rect.centerX() - w / 2f) } ?: return
        val lvl = if (Prefs.humanize) Prefs.level else 1
        if (!aim.shouldFire(t.head, w, h, lvl)) return
        val (tx, ty) = aim.path(w / 2f, h * 0.45f, t.head, lvl)
        val ms = aim.dragMs(kotlin.math.hypot(tx - w / 2f, ty - h * 0.45f), lvl)
        withContext(Dispatchers.Main) { a.swipe(w / 2f, h * 0.45f, tx, ty, ms) }
    }

    private fun notif() {
        val ch = "retolam"
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(ch, "Retolam",
                NotificationManager.IMPORTANCE_LOW))
        val n = Notification.Builder(this, ch)
            .setContentTitle("Retolam activo")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true).build()
        startForeground(1, n)
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        capture?.stop()
        detector?.close()
        try { draw?.let { wm.removeView(it) } } catch (e: Exception) {}
        try { menu?.let { wm.removeView(it) } } catch (e: Exception) {}
        super.onDestroy()
    }
}
