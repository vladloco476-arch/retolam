package com.retolam.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {
    private lateinit var statusOverlay: TextView
    private lateinit var statusAcc: TextView

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        Prefs.init(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#0B0F14"))
            setPadding(60, 90, 60, 60)
        }

        root.addView(TextView(this).apply {
            text = "RETOLAM"
            textSize = 34f
            setTextColor(Color.parseColor("#FF3B30"))
            setTypeface(null, android.graphics.Typeface.BOLD)
        })
        root.addView(TextView(this).apply {
            text = "Overlay para Free Fire"
            setTextColor(Color.parseColor("#8A94A6"))
            setPadding(0, 0, 0, 40)
        })

        statusOverlay = TextView(this).apply { setTextColor(Color.WHITE) }
        statusAcc = TextView(this).apply { setTextColor(Color.WHITE) }
        root.addView(statusOverlay)
        root.addView(statusAcc)

        root.addView(btn("1. Permitir confiar en esta app") { trust() })
        root.addView(btn("2. Iniciar overlay") { startOverlay() })
        root.addView(btn("3. Abrir Free Fire") {
            if (!LaunchFreeFire.open(this)) toast("Free Fire no está instalado")
        })

        setContentView(root)
    }

    private fun btn(t: String, f: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { f() }
    }

    private fun trust() {
        AlertDialog.Builder(this)
            .setTitle("Confiar en Retolam")
            .setMessage("Concede los 3 permisos. Sin ellos el overlay no funciona.\n\n" +
                "1) Sobre otras apps\n2) Accesibilidad\n3) Quitar ahorro de batería")
            .setPositiveButton("Permiso de overlay") { _, _ ->
                startActivity(PermissionHelper.overlayIntent(this)) }
            .setNeutralButton("Accesibilidad") { _, _ ->
                startActivity(PermissionHelper.accIntent()) }
            .setNegativeButton("Batería") { _, _ ->
                startActivity(PermissionHelper.batteryIntent()) }
            .show()
    }

    private fun startOverlay() {
        if (!PermissionHelper.hasOverlay(this)) {
            toast("Activa 'Sobre otras apps'")
            startActivity(PermissionHelper.overlayIntent(this))
            return
        }
        if (!PermissionHelper.hasAcc(this)) {
            toast("Activa Retolam en Accesibilidad")
            startActivity(PermissionHelper.accIntent())
            return
        }
        val m = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(m.createScreenCaptureIntent(), 1)
    }

    override fun onActivityResult(req: Int, code: Int, data: Intent?) {
        super.onActivityResult(req, code, data)
        if (req == 1 && code == RESULT_OK && data != null) {
            startForegroundService(Intent(this, OverlayService::class.java).apply {
                putExtra("code", code)
                putExtra("data", data)
            })
            toast("Overlay activo. Abre Free Fire.")
        }
    }

    override fun onResume() {
        super.onResume()
        statusOverlay.text = "Sobre otras apps: " +
            if (PermissionHelper.hasOverlay(this)) "LISTO" else "FALTA"
        statusAcc.text = "Accesibilidad: " +
            if (PermissionHelper.hasAcc(this)) "LISTO" else "FALTA"
    }

    private fun toast(t: String) =
        Toast.makeText(this, t, Toast.LENGTH_SHORT).show()
}
