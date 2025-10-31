package com.tober.glyphmatrix.calls

import android.annotation.SuppressLint
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.Manifest
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

import androidx.core.content.ContextCompat
import androidx.core.graphics.createBitmap
import androidx.core.graphics.get
import androidx.core.graphics.scale
import androidx.core.graphics.set

import com.nothing.ketchum.Glyph
import com.nothing.ketchum.GlyphMatrixFrame
import com.nothing.ketchum.GlyphMatrixManager
import com.nothing.ketchum.GlyphMatrixObject

import java.io.File
import kotlin.math.ceil
import kotlin.math.sqrt
import org.json.JSONArray

class GlyphMatrixService : Service() {
    private val tag = "Glyph Matrix Service"

    private var telephonyRegistered = false
    private lateinit var telephonyManager: TelephonyManager
    private val telephonyCallback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
        override fun onCallStateChanged(state: Int) {
            Log.d(tag, "Telephony Callback: $state")
            when (state) {
                TelephonyManager.CALL_STATE_OFFHOOK -> onCallAnswered()
                TelephonyManager.CALL_STATE_IDLE -> onCallEnded()
            }
        }
    }

    private var glyphMatrixManager: GlyphMatrixManager? = null
    private var glyphMatrixManagerCallback: GlyphMatrixManager.Callback? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private var clearRunnable: Runnable? = null
    private var animationRunnable: Runnable? = null

    private var initialized = false
    private var glyph: Bitmap? = null
    private val matrixSize = 25
    private val cx = (matrixSize - 1) / 2.0
    private val cy = (matrixSize - 1) / 2.0
    private val maxRadius = ceil(sqrt(cx * cx + cy * cy)).toInt()

    private var wakeLock: PowerManager.WakeLock? = null
    private var lastRendered: IntArray? = null
    private val resendTimeout = 5L
    private var resendRunnable: Runnable? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        Log.d(tag, "onCreate")

        telephonyManager = getSystemService(TelephonyManager::class.java)

        val hasPhoneState = ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_PHONE_STATE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPhoneState) {
            try {
                telephonyManager.registerTelephonyCallback(mainExecutor, telephonyCallback)
                telephonyRegistered = true

                Log.d(tag, "Telephony callback registered")
            } catch (e: Exception) {
                Log.e(tag, "Failed to register Telephony callback: $e")
            }
        } else {
            Log.w(tag, "READ_PHONE_STATE not granted")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

        val active = preferences.getBoolean(Constants.PREFERENCES_ACTIVE, true)
        if (!active) return START_REDELIVER_INTENT

        if (intent?.action == Constants.ACTION_ON_CALL) {
            val contact = intent.getStringExtra(Constants.CALL_EXTRA_CONTACT)

            if (!contact.isNullOrBlank()) {
                val ignoredContacts = preferences.getString(Constants.PREFERENCES_IGNORED_CONTACTS, null)
                if (!ignoredContacts.isNullOrBlank()) {
                    try {
                        val arr = JSONArray(ignoredContacts)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val ignoredContact = obj.optString("contact")
                            if (ignoredContact == contact) {
                                return START_REDELIVER_INTENT
                            }
                        }
                    } catch (_: Throwable) {}
                }
            }

            clearRunnable?.let { mainHandler.removeCallbacks(it) }
            clearRunnable = null

            animationRunnable?.let { mainHandler.removeCallbacks(it) }
            animationRunnable = null

            glyphMatrixManager?.closeAppMatrix()

            if (initialized) onGlyph(contact)
            else onInit { onGlyph(contact) }
        }

        return START_REDELIVER_INTENT
    }

    override fun onDestroy() {
        super.onDestroy()

        Log.d(tag, "onDestroy")

        stopRefresh()

        initialized = false

        if (telephonyRegistered) {
            try {
                telephonyManager.unregisterTelephonyCallback(telephonyCallback)
            } catch (_: Throwable) {}

            telephonyRegistered = false
        }
    }

    private fun onCallAnswered() {
        Log.d(tag, "onCallAnswered")
    }

    private fun onCallEnded() {
        Log.d(tag, "onCallEnded")

        if (glyph == null) return

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)
        val speed = preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L).coerceAtLeast(1L)

        fun clear() {
            try {
                stopRefresh()
                glyph = null
                glyphMatrixManager?.closeAppMatrix()
            } catch (e: Exception) {
                Log.e(tag, "Failed to close glyph matrix: $e")
            }
        }

        if (preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)) {
            glyph?.let { g ->
                hideAnimated(g, speed) { clear() }
            }
        }
        else {
            clear()
        }
    }

    private fun onInit(operation: () -> Unit) {
        if (initialized) return

        Log.d(tag, "onInit")

        glyphMatrixManager = GlyphMatrixManager.getInstance(this)
        glyphMatrixManagerCallback = object : GlyphMatrixManager.Callback {
            override fun onServiceConnected(componentName: ComponentName?) {
                Log.d(tag, "Connected: $componentName")

                try {
                    glyphMatrixManager?.register(Glyph.DEVICE_23112)
                    initialized = true

                    Log.d(tag, "Initialized")

                    operation()
                } catch (e: Exception) {
                    Log.e(tag, "Failed initialization: $e")
                }
            }

            override fun onServiceDisconnected(componentName: ComponentName?) {
                Log.d(tag, "Disconnected: $componentName")
                initialized = false
            }
        }

        glyphMatrixManager?.init(glyphMatrixManagerCallback)
    }

    private fun onGlyph(callContact: String?) {
        Log.d(tag, "onGlyph")

        val preferences = getSharedPreferences(Constants.PREFERENCES_NAME, MODE_PRIVATE)

        val contactGlyphs = preferences.getString(Constants.PREFERENCES_CONTACT_GLYPHS, null)
        if (!contactGlyphs.isNullOrBlank() && !callContact.isNullOrBlank()) {
            try {
                val arr = JSONArray(contactGlyphs)
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    val contactGlyphContact = obj.optString("contact")
                    val contactGlyph = obj.optString("glyph")
                    if (contactGlyph.isNotBlank() && contactGlyphContact == callContact) {
                        val f = File(contactGlyph)
                        if (f.exists()) {
                            glyph = BitmapFactory.decodeFile(contactGlyph)
                            break
                        }
                    }
                }
            } catch (_: Throwable) {}
        }

        if (glyph == null) {
            val defaultGlyph = preferences.getString(Constants.PREFERENCES_DEFAULT_GLYPH, null)
            if (!defaultGlyph.isNullOrBlank()) {
                try {
                    val f = File(defaultGlyph)
                    if (f.exists()) {
                        glyph = BitmapFactory.decodeFile(defaultGlyph)
                    }
                } catch (_: Throwable) {}
            }
        }

        if (glyph == null) return

        if (preferences.getBoolean(Constants.PREFERENCES_ANIMATE_GLYPHS, true)) {
            val speed = preferences.getLong(Constants.PREFERENCES_ANIMATE_SPEED, 10L).coerceAtLeast(1L)

            startRefresh()

            glyph?.let { g ->
                showAnimated(g, speed) {}
            }
        }
        else {
            startRefresh()

            glyph?.let { g ->
                showSimple(g)
            }
        }
    }

    private fun showSimple(glyph: Bitmap) {
        try {
            val objBuilder = GlyphMatrixObject.Builder()
            val image = objBuilder
                .setImageSource(glyph)
                .setScale(100)
                .setOrientation(0)
                .setPosition(0, 0)
                .setReverse(false)
                .build()

            val frameBuilder = GlyphMatrixFrame.Builder()
            val frame = frameBuilder.addTop(image).build(this)
            val rendered = frame.render()

            lastRendered = rendered
            glyphMatrixManager?.setAppMatrixFrame(rendered)
        } catch (e: Exception) {
            Log.e(tag, "Failed to show glyph: $e")
        }
    }

    private fun showAnimated(glyph: Bitmap, speed: Long, operation: () -> Unit) {
        val src = glyph.scale(matrixSize, matrixSize)

        var radius = 0

        val runnable = object : Runnable {
            override fun run() {
                if (radius <= maxRadius) {
                    val masked = buildMaskedFrame(radius, src)
                    try {
                        val objBuilder = GlyphMatrixObject.Builder()
                        val image = objBuilder
                            .setImageSource(masked)
                            .setScale(100)
                            .setOrientation(0)
                            .setPosition(0, 0)
                            .setReverse(false)
                            .build()

                        val frameBuilder = GlyphMatrixFrame.Builder()
                        val frame = frameBuilder.addTop(image).build(this@GlyphMatrixService)
                        val rendered = frame.render()

                        lastRendered = rendered
                        glyphMatrixManager?.setAppMatrixFrame(rendered)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to show glyph: $e")
                    }

                    radius++

                    mainHandler.postDelayed(this, speed)
                } else {
                    animationRunnable = null
                    operation()
                }
            }
        }

        animationRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun hideAnimated(glyph: Bitmap, speed: Long, operation: () -> Unit) {
        clearRunnable = null

        val src = glyph.scale(matrixSize, matrixSize)

        var radius = maxRadius

        val runnable = object : Runnable {
            override fun run() {
                if (radius >= 0) {
                    val masked = buildMaskedFrame(radius, src)
                    try {
                        val objBuilder = GlyphMatrixObject.Builder()
                        val image = objBuilder
                            .setImageSource(masked)
                            .setScale(100)
                            .setOrientation(0)
                            .setPosition(0, 0)
                            .setReverse(false)
                            .build()

                        val frameBuilder = GlyphMatrixFrame.Builder()
                        val frame = frameBuilder.addTop(image).build(this@GlyphMatrixService)
                        val rendered = frame.render()

                        lastRendered = rendered
                        glyphMatrixManager?.setAppMatrixFrame(rendered)
                    } catch (e: Exception) {
                        Log.e(tag, "Failed to show glyph: $e")
                    }

                    radius--

                    mainHandler.postDelayed(this, speed)
                } else {
                    animationRunnable = null
                    operation()
                }
            }
        }

        animationRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun buildMaskedFrame(radius: Int, src: Bitmap): Bitmap {
        val out = createBitmap(matrixSize, matrixSize)
        val rSq = radius * radius
        for (y in 0 until matrixSize) {
            val dy = (y - cy)
            val dySq = dy * dy
            for (x in 0 until matrixSize) {
                val dx = (x - cx)
                val distSq = dx * dx + dySq
                if (distSq <= rSq) {
                    out[x, y] = src[x, y]
                } else {
                    out[x, y] = 0
                }
            }
        }
        return out
    }

    private fun startRefresh() {
        stopRefresh()

        setWakeLock()

        val runnable = object : Runnable {
            override fun run() {
                try {
                    lastRendered?.let { glyphMatrixManager?.setAppMatrixFrame(it) }
                } catch (e: Exception) {
                    Log.e(tag, "Failed periodic resend: $e")
                }
                mainHandler.postDelayed(this, resendTimeout)
            }
        }

        resendRunnable = runnable
        mainHandler.post(runnable)
    }

    private fun stopRefresh() {
        resendRunnable?.let { mainHandler.removeCallbacks(it) }
        resendRunnable = null
        releaseWakeLock()
    }

    @SuppressLint("WakelockTimeout")
    private fun setWakeLock() {
        try {
            val pm = getSystemService(POWER_SERVICE) as PowerManager

            if (wakeLock == null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GlyphMatrixService:WakeLock").apply {
                    setReferenceCounted(false)
                }
            }

            if (!wakeLock!!.isHeld) {
                wakeLock!!.acquire()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to set wakelock: $e")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.w(tag, "Failed to release wakelock: $e")
        } finally {
            wakeLock = null
        }
    }
}
