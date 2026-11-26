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
import org.tensorflow.lite.gpu.GpuDelegate
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
    private val SIZE = 320
    var ready = false
    private lateinit var interp: Interpreter

    init {
        try {
            val buf = loadModel(ctx, "retolam.tflite")
            val opts = Interpreter.Options().apply {
                try { addDelegate(GpuDelegate()) } catch (e: Throwable) { setNumThreads(4) }
            }
            interp = Interpreter(buf, opts)
            ready = true
        } catch (e: Throwable) { ready = false }
    }

    fun detect(bmp: Bitmap, thr: Float = 0.40f): List<Box> {
        if (!ready) return emptyList()
        val scaled = Bitmap.createScaledBitmap(bmp, SIZE, SIZE, true)
        val inBuf = ByteBuffer.allocateDirect(4 * SIZE * SIZE * 3)
            .order(ByteOrder.nativeOrder())
        val px = IntArray(SIZE * SIZE)
        scaled.getPixels(px, 0, SIZE, 0, 0, SIZE, SIZE)
        for (v in px) {
            inBuf.putFloat((v shr 16 and 0xFF) / 255f)
            inBuf.putFloat((v shr 8 and 0xFF) / 255f)
            inBuf.putFloat((v and 0xFF) / 255f)
        }
        inBuf.rewind()
        val out = Array(1) { Array(5) { FloatArray(2100) } }
        interp.run(inBuf, out)

        val sx = bmp.width / SIZE.toFloat()
        val sy = bmp.height / SIZE.toFloat()
        val res = mutableListOf<Box>()
        for (i in 0 until 2100) {
            val conf = out[0][4][i]
            if (conf < thr) continue
            val cx = out[0][0][i] * sx
            val cy = out[0][1][i] * sy
            val bw = out[0][2][i] * sx
            val bh = out[0][3][i] * sy
            val body = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
            val head = RectF(body.left + bw * 0.22f, body.top,
                body.right - bw * 0.22f, body.top + bh * 0.22f)
            res.add(Box(body, conf, head))
        }
        return nms(res)
    }

    private fun nms(boxes: List<Box>): List<Box> {
        val sorted = boxes.sortedByDescending { it.score }.toMutableList()
        val keep = mutableListOf<Box>()
        while (sorted.isNotEmpty()) {
            val b = sorted.removeAt(0)
            keep.add(b)
            sorted.removeAll { iou(b.rect, it.rect) > 0.45f }
        }
        return keep
    }

    private fun iou(a: RectF, b: RectF): Float {
        val ix = maxOf(0f, minOf(a.right, b.right) - maxOf(a.left, b.left))
        val iy = maxOf(0f, minOf(a.bottom, b.bottom) - maxOf(a.top, b.top))
        val i = ix * iy
        return i / (a.width() * a.height() + b.width() * b.height() - i)
    }

    private fun loadModel(ctx: Context, name: String): ByteBuffer {
        val fd = ctx.assets.openFd(name)
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() { if (ready) interp.close() }
}

class AimEngine {
    private var nextAllowed = 0L
    private val rnd = Random(System.nanoTime())

    fun shouldFire(target: RectF, sw: Int, sh: Int, lvl: Int): Boolean {
        val now = System.currentTimeMillis()
        if (now < nextAllowed) return false
        if (hypot(abs(sw / 2f - target.centerX()),
                abs(sh * 0.45f - target.centerY())) < 12f * lvl) return false
        val react = when (lvl) {
            1 -> rnd.nextLong(20, 55)
            2 -> rnd.nextLong(45, 110)
            3 -> rnd.nextLong(90, 190)
            4 -> rnd.nextLong(150, 300)
            else -> rnd.nextLong(230, 430)
        }
        nextAllowed = now + react
        return true
    }

    fun path(fromX: Float, fromY: Float, t: RectF, lvl: Int): Pair<Float, Float> {
        val err = (lvl - 1) * 2.2f
        val jx = rnd.nextFloat() * err * 2 - err
        val jy = rnd.nextFloat() * err * 2 - err
        val over = if (rnd.nextFloat() < 0.35f) rnd.nextFloat() * 6f * lvl else 0f
        val tx = t.centerX() + jx + if (fromX < t.centerX()) over else -over
        return tx to (t.centerY() + jy)
    }

    fun dragMs(dist: Float, lvl: Int): Long {
        val base = (dist * 0.55f).toLong()
        return (base + rnd.nextLong(8, 30) * lvl).coerceIn(28L, 340L)
    }

    fun reset() { nextAllowed = 0L }
}

class EspRenderer(ctx: Context) : View(ctx) {
    var boxes: List<Box> = emptyList()
    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 3f }
    private val headP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2.5f; color = Color.parseColor("#FF3B30") }
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f; color = Color.parseColor("#00E5FF") }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val cx = width / 2f
        val cy = height * 0.93f
        for (b in boxes) {
            body.color = if (b.score > 0.65f) Color.parseColor("#39FF14")
                         else Color.parseColor("#FFD60A")
            c.drawRect(b.rect, body)
            c.drawRect(b.head, headP)
            c.drawLine(cx, cy, b.rect.centerX(), b.rect.centerY(), lineP)
        }
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
