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
import org.tensorflow.lite.DataType
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
    private lateinit var interp: Interpreter
    private var inW = 320
    private var inH = 320
    private var nchw = false
    private var floatIn = true
    private lateinit var inBuf: ByteBuffer
    private var px = IntArray(0)

    private var ssd = false
    private var yoloRows = 5
    private var yoloAnchors = 2100
    private var yoloOut: Array<Array<FloatArray>>? = null

    private var ssdOuts: MutableMap<Int, Any> = mutableMapOf()
    private var boxIdx = -1
    private var catIdx = -1
    private var scoreIdx = -1
    private var detCount = 25
    private var firstRun = true

    init {
        try {
            val buf = loadModel(ctx, "retolam.tflite")
            interp = Interpreter(buf, Interpreter.Options().apply { setNumThreads(4) })

            val inp = interp.getInputTensor(0)
            val s = inp.shape()
            if (s[1] == 3) { nchw = true; inH = s[2]; inW = s[3] }
            else { inH = s[1]; inW = s[2] }
            floatIn = inp.dataType() == DataType.FLOAT32
            inBuf = ByteBuffer.allocateDirect(inp.numBytes()).order(ByteOrder.nativeOrder())
            px = IntArray(inW * inH)

            val nOut = interp.outputTensorCount
            if (nOut >= 3) {
                ssd = true
                for (i in 0 until nOut) {
                    val o = interp.getOutputTensor(i)
                    val os = o.shape()
                    if (os.size == 3 && os[2] == 4) { boxIdx = i; detCount = os[1] }
                    else if (os.size == 2 && os[1] > 1) {
                        if (catIdx < 0) catIdx = i else if (scoreIdx < 0) scoreIdx = i
                    }
                    ssdOuts[i] = alloc(os)
                }
            } else {
                val o = interp.getOutputTensor(0).shape()
                yoloRows = o[1]
                yoloAnchors = o[2]
                yoloOut = Array(1) { Array(yoloRows) { FloatArray(yoloAnchors) } }
            }
            ready = if (ssd) boxIdx >= 0 && catIdx >= 0 && scoreIdx >= 0
                    else yoloOut != null
        } catch (e: Throwable) { ready = false }
    }

    private fun alloc(shape: IntArray): Any {
        if (shape.size <= 1) return FloatArray(if (shape.isEmpty()) 1 else shape[0])
        return Array(shape[0]) { alloc(shape.copyOfRange(1, shape.size)) }
    }

    fun detect(bmp: Bitmap, thr: Float = 0.35f): List<Box> {
        if (!ready) return emptyList()
        try {
            val scaled = Bitmap.createScaledBitmap(bmp, inW, inH, true)
            scaled.getPixels(px, 0, inW, 0, 0, inW, inH)
            inBuf.rewind()
            if (floatIn) {
                if (nchw) {
                    for (c in 0..2) for (v in px)
                        inBuf.putFloat((v shr (16 - c * 8) and 0xFF) / 255f)
                } else {
                    for (v in px) {
                        inBuf.putFloat((v shr 16 and 0xFF) / 255f)
                        inBuf.putFloat((v shr 8 and 0xFF) / 255f)
                        inBuf.putFloat((v and 0xFF) / 255f)
                    }
                }
            } else {
                if (nchw) {
                    for (c in 0..2) for (v in px)
                        inBuf.put((v shr (16 - c * 8) and 0xFF).toByte())
                } else {
                    for (v in px) {
                        inBuf.put((v shr 16 and 0xFF).toByte())
                        inBuf.put((v shr 8 and 0xFF).toByte())
                        inBuf.put((v and 0xFF).toByte())
                    }
                }
            }
            inBuf.rewind()

            if (ssd) return detectSsd(bmp, thr)
            return detectYolo(bmp, thr)
        } catch (e: Throwable) { return emptyList() }
    }

    private fun detectSsd(bmp: Bitmap, thr: Float): List<Box> {
        interp.run(inBuf, ssdOuts as Map<Int, Any>)
        @Suppress("UNCHECKED_CAST")
        val boxes = ssdOuts[boxIdx] as Array<Array<FloatArray>>
        @Suppress("UNCHECKED_CAST")
        var cats = ssdOuts[catIdx] as Array<FloatArray>
        @Suppress("UNCHECKED_CAST")
        var scores = ssdOuts[scoreIdx] as Array<FloatArray>

        if (firstRun) {
            firstRun = false
            if (scores[0][0] > 1.5f) {
                val t = cats
                cats = scores
                scores = t
            }
        }

        val res = mutableListOf<Box>()
        val n = minOf(detCount, boxes[0].size, scores[0].size)
        for (i in 0 until n) {
            val sc = scores[0][i]
            if (sc < thr) continue
            if (cats[0][i].toInt() != 0) continue
            res.add(makeBox(
                boxes[0][i][1] * bmp.width,
                boxes[0][i][0] * bmp.height,
                boxes[0][i][3] * bmp.width,
                boxes[0][i][2] * bmp.height, sc))
        }
        return nms(res)
    }

    private fun detectYolo(bmp: Bitmap, thr: Float): List<Box> {
        val out = yoloOut ?: return emptyList()
        interp.run(inBuf, out)
        val sx = bmp.width / inW.toFloat()
        val sy = bmp.height / inH.toFloat()
        val res = mutableListOf<Box>()
        for (i in 0 until yoloAnchors) {
            var conf = 0f
            for (r in 4 until yoloRows) if (out[0][r][i] > conf) conf = out[0][r][i]
            if (conf < thr) continue
            val cx = out[0][0][i] * sx
            val cy = out[0][1][i] * sy
            val bw = out[0][2][i] * sx
            val bh = out[0][3][i] * sy
            res.add(makeBox(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2, conf))
        }
        return nms(res)
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

    private fun loadModel(ctx: Context, name: String): ByteBuffer {
        val fd = ctx.assets.openFd(name)
        return FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
    }

    fun close() { if (ready) interp.close() }
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
