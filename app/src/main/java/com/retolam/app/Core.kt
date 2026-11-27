package com.retolam.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.provider.Settings
import android.view.View
import android.view.accessibility.AccessibilityEvent
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

object Prefs {
    private var c0: Context? = null
    fun init(c: Context) { c0 = c.applicationContext }
    private fun p() = c0!!.getSharedPreferences("retolam", Context.MODE_PRIVATE)

    var aimbot: Boolean
        get() = p().getBoolean("aim", false)
        set(v) { p().edit().putBoolean("aim", v).apply() }
    var esp: Boolean
        get() = p().getBoolean("esp", false)
        set(v) { p().edit().putBoolean("esp", v).apply() }
    var fire: Boolean
        get() = p().getBoolean("fire", false)
        set(v) { p().edit().putBoolean("fire", v).apply() }
    var humanize: Boolean
        get() = p().getBoolean("hum", true)
        set(v) { p().edit().putBoolean("hum", v).apply() }
    var level: Int
        get() = p().getInt("lvl", 3)
        set(v) { p().edit().putInt("lvl", v).apply() }
}

object PermissionHelper {
    fun hasOverlay(c: Context) = Settings.canDrawOverlays(c)
    fun overlayIntent(c: Context) = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${c.packageName}"))
    fun hasAcc(c: Context): Boolean {
        val s = Settings.Secure.getString(c.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(c.packageName + "/" + Accessibility::class.java.name)
    }
    fun accIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
    fun batteryIntent() = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
}

class Accessibility : AccessibilityService() {
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun swipe(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long) {
        val p = Path().apply { moveTo(x1, y1); lineTo(x2, y2) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build(), null, null)
    }

    fun tap(x: Float, y: Float) {
        val p = Path().apply { moveTo(x, y) }
        dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 45)).build(), null, null)
    }

    companion object { var instance: Accessibility? = null }
}

class ScreenCapture(
    private val ctx: Context,
    private val resultCode: Int,
    private val data: Intent
) {
    private var projection: MediaProjection? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private lateinit var handler: Handler
    var w = 0
    var h = 0

    fun start() {
        val dm = ctx.resources.displayMetrics
        w = dm.widthPixels
        h = dm.heightPixels
        thread = HandlerThread("cap").also { it.start() }
        handler = Handler(thread!!.looper)
        val mgr = ctx.getSystemService(Context.MEDIA_PROJECTION_SERVICE)
                as android.media.projection.MediaProjectionManager
        projection = mgr.getMediaProjection(resultCode, data)
        projection!!.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() { stop() }
        }, handler)
        reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
        projection!!.createVirtualDisplay("retolam", w, h, dm.densityDpi,
            android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader!!.surface, null, handler)
    }

    fun grab(): Bitmap? {
        val img: Image = try { reader?.acquireLatestImage() } catch (e: Exception) { null } ?: return null
        return try { toBitmap(img) } catch (e: Exception) { null } finally { img.close() }
    }

    private fun toBitmap(img: Image): Bitmap {
        val plane = img.planes[0]
        val rowPixels = plane.rowStride / plane.pixelStride
        val bmp = Bitmap.createBitmap(rowPixels, img.height, Bitmap.Config.ARGB_8888)
        bmp.copyPixelsFromBuffer(plane.buffer)
        return Bitmap.createScaledBitmap(bmp, w, h, false)
    }

    fun stop() {
        try { projection?.stop() } catch (e: Exception) {}
        reader?.close()
        thread?.quitSafely()
    }
}

data class Box(val rect: RectF, val score: Float, val head: RectF)

class Detector(ctx: Context) {
    var ready = false
    private var interp: Interpreter? = null

    private val N = 25
    private val SZ = 320
    private val inBuf: ByteBuffer
    private val px = IntArray(SZ * SZ)
    private val oBox = Array(1) { Array(N) { FloatArray(4) } }
    private val oCat = Array(1) { FloatArray(N) }
    private val oScore = Array(1) { FloatArray(N) }
    private val oCnt = Array(1) { FloatArray(1) }
    private val outs = mutableMapOf<Int, Any>()
    private var firstRun = true
    private var swap = false

    init {
        inBuf = ByteBuffer.allocateDirect(4 * SZ * SZ * 3).order(ByteOrder.nativeOrder())
        outs[0] = oBox
        outs[1] = oCat
        outs[2] = oScore
        outs[3] = oCnt
        try {
            val fd = ctx.assets.openFd("retolam.tflite")
            val m = FileInputStream(fd.fileDescriptor).channel
                .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            interp = Interpreter(m, Interpreter.Options().apply { setNumThreads(4) })
            ready = true
        } catch (e: Throwable) { ready = false }
    }

    fun detect(bmp: Bitmap, thr: Float = 0.35f): List<Box> {
        val tf = interp ?: return emptyList()
        if (!ready) return emptyList()
        try {
            val sc = Bitmap.createScaledBitmap(bmp, SZ, SZ, true)
            sc.getPixels(px, 0, SZ, 0, 0, SZ, SZ)
            inBuf.rewind()
            for (v in px) {
                inBuf.putFloat((v shr 16 and 0xFF) / 255f)
                inBuf.putFloat((v shr 8 and 0xFF) / 255f)
                inBuf.putFloat((v and 0xFF) / 255f)
            }
            inBuf.rewind()
            tf.run(inBuf, outs)

            if (firstRun) {
                firstRun = false
                if (oScore[0][0] > 1.5f) swap = true
            }
            val scArr = if (swap) oCat else oScore
            val clArr = if (swap) oScore else oCat

            var n = oCnt[0][0].toInt()
            if (n < 1 || n > N) n = N

            var maxC = 0f
            for (i in 0 until n) {
                for (k in 0..3) if (oBox[0][i][k] > maxC) maxC = oBox[0][i][k]
            }
            val isPx = maxC > 1.5f
            val kx = if (isPx) bmp.width / SZ.toFloat() else bmp.width.toFloat()
            val ky = if (isPx) bmp.height / SZ.toFloat() else bmp.height.toFloat()

            val res = mutableListOf<Box>()
            for (i in 0 until n) {
                val s = scArr[0][i]
                if (s < thr) continue
                if (clArr[0][i].toInt() != 0) continue
                val x1 = oBox[0][i][1] * kx
                val y1 = oBox[0][i][0] * ky
                val x2 = oBox[0][i][3] * kx
                val y2 = oBox[0][i][2] * ky
                if (x2 - x1 < 8f || y2 - y1 < 16f) continue
                res.add(makeBox(x1, y1, x2, y2, s))
            }
            return nms(res)
        } catch (e: Throwable) { return emptyList() }
    }

    private fun makeBox(x1: Float, y1: Float, x2: Float, y2: Float, sc: Float): Box {
        val body = RectF(x1, y1, x2, y2)
        val hw = body.width() * 0.28f
        val head = RectF(body.left + hw, body.top, body.right - hw,
            body.top + body.height() * 0.22f)
        return Box(body, sc, head)
    }

    private fun nms(boxes: List<Box>): List<Box> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<Box>()
        while (sorted.isNotEmpty()) {
            val b = sorted.removeAt(0)
            keep.add(b)
            sorted.removeAll { iou(b.rect, it.rect) > 0.45f }
        }
        return keep.take(8)
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val i = ix * iy
        return i / (a.width() * a.height() + b.width() * b.height() - i)
    }

    fun close() { interp?.close() }
}

class AimEngine {
    private val rnd = Random(System.nanoTime())
    var lastFire = 0L

    fun jitter(lvl: Int): Float = if (lvl <= 1) 0f
        else (rnd.nextFloat() * 2f - 1f) * (lvl - 1) * 2.5f

    fun dragMs(step: Float, lvl: Int): Long =
        ((step * 0.35f).toLong() + rnd.nextLong(10, 25) * lvl).coerceIn(24L, 180L)

    fun canFire(lvl: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now - lastFire < 90L + lvl * 35L) return false
        lastFire = now
        return true
    }
}

class EspRenderer(ctx: Context) : View(ctx) {
    var boxes: List<Box> = emptyList()
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f }
    private val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.parseColor("#FF3B30") }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#00E5FF") }
    private val dbg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#39FF14"); textSize = 30f }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height * 0.93f
        for (b in boxes) {
            body.color = if (b.score > 0.65f) Color.parseColor("#39FF14")
                         else Color.parseColor("#FFD60A")
            c.drawRect(b.rect, body)
            c.drawCircle(b.head.centerX(), b.head.centerY(),
                maxOf(b.head.width() * 0.75f, 10f), headP)
            c.drawLine(cx, cy, b.rect.centerX(), b.rect.centerY(), lineP)
        }
        val top = boxes.maxOfOrNull { it.score }
        c.drawText("det:${boxes.size} " + (top?.toString() ?: "-"), 24f, 46f, dbg)
    }
}

object LaunchFreeFire {
    private val PKG = "com.dts.freefireth"
    private val ALT = "com.dts.freefiremax"
    fun open(c: Context): Boolean {
        val p = if (c.packageManager.getLaunchIntentForPackage(PKG) != null) PKG
                else if (c.packageManager.getLaunchIntentForPackage(ALT) != null) ALT
                else null
        if (p == null) return false
        c.startActivity(c.packageManager.getLaunchIntentForPackage(p)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
