package com.ailife.clox.rtos.fakesettings

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.ailife.clox.watchface.WatchfacePrefs
import com.ailife.clox.watchface.WatchfaceRepository
import com.ailife.clox.watchface.WatchfaceSnapshot
import com.ailife.clox.watchface.selector.WatchfaceSelectorActivity
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * Fake RTOS settings — matches the reference RTOS watch UI:
 * dark navy background, time top-right, blue section title, rounded list items.
 */
class FakeSettingsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_START_PAGE = "start_page"
        const val PAGE_QR = "qr"
    }

    private val BG      = Color.parseColor("#0D1B2A")
    private val TOPBAR  = Color.parseColor("#0D1B2A")
    private val ITEM_BG = Color.parseColor("#1A2B3C")
    private val WHITE   = Color.WHITE
    private val BLUE    = Color.parseColor("#4FC3F7")
    private val ARROW   = Color.parseColor("#4FC3F7")

    private lateinit var contentFrame: FrameLayout
    private val fakeBleMac = generateMac()
    private var currentTitle = "Settings"

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        hideSystemBars()

        val root = FrameLayout(this).apply { setBackgroundColor(BG) }

        // Top bar: back button left, title right, time right
        val topBar = buildTopBar()
        root.addView(topBar, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, dp(52)
        ))

        contentFrame = FrameLayout(this)
        root.addView(contentFrame, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { topMargin = dp(52) })

        setContentView(root)

        if (intent.getStringExtra(EXTRA_START_PAGE) == PAGE_QR) showQrPage()
        else showMenuPage()
    }

    // ── Top bar ───────────────────────────────────────────────────────────────

    private lateinit var titleView: TextView
    private lateinit var timeView: TextView

    private fun buildTopBar(): FrameLayout {
        return FrameLayout(this).apply {
            setBackgroundColor(TOPBAR)

            // Back button — left
            val back = TextView(this@FakeSettingsActivity).apply {
                text = "‹"
                textSize = 28f
                setTextColor(WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(16), 0, dp(8), 0)
                setOnClickListener { onBackAction() }
            }
            addView(back, FrameLayout.LayoutParams(dp(52), FrameLayout.LayoutParams.MATCH_PARENT).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            })

            // Right side: time on top, title below in blue
            val rightCol = LinearLayout(this@FakeSettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), dp(12), dp(4))
            }
            timeView = TextView(this@FakeSettingsActivity).apply {
                text = currentTime()
                textSize = 13f
                setTextColor(WHITE)
                gravity = Gravity.END
            }
            titleView = TextView(this@FakeSettingsActivity).apply {
                text = currentTitle
                textSize = 13f
                setTextColor(BLUE)
                gravity = Gravity.END
            }
            rightCol.addView(timeView)
            rightCol.addView(titleView)

            addView(rightCol, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL })
        }
    }

    private var backAction: (() -> Unit)? = null
    private fun onBackAction() = backAction?.invoke() ?: finish()

    private fun setTitle(t: String) {
        currentTitle = t
        if (::titleView.isInitialized) titleView.text = t
        if (::timeView.isInitialized) timeView.text = currentTime()
    }

    // ── Pages ─────────────────────────────────────────────────────────────────

    private fun showMenuPage() {
        backAction = null   // top-level: back exits
        setTitle("General")
        showList(listOf(
            "General"     to { showGeneralPage() },
            "Language"    to { showFakePage("Language", listOf("Language" to "English")) },
            "Date & time" to { showFakePage("Date & time", listOf(
                "Time format" to "24h",
                "Time zone"   to "UTC+8"
            )) },
            "QR code"     to { showQrPage() }
        ))
    }

    private fun showGeneralPage() {
        backAction = { showMenuPage() }
        setTitle("General")
        showList(listOf(
            "Watch Face"  to { openWatchfaceSelector() },
            "Bluetooth"   to { showFakePage("Bluetooth", listOf(
                "Status"     to "Connected",
                "Device"     to "Phone",
                "MAC"        to fakeBleMac,
                "Auto-conn." to "On"
            )) },
            "Sound"       to { showFakePage("Sound", listOf(
                "Ringtone"   to "On",
                "Vibration"  to "On",
                "Volume"     to "75%"
            )) },
            "Display"     to { showFakePage("Display", listOf(
                "Brightness" to "Auto",
                "Timeout"    to "15s",
                "Always On"  to "Off"
            )) },
            "About"       to { showFakePage("About", listOf(
                "Model"      to "SmartWatch Pro",
                "Firmware"   to "v2.4.1",
                "MAC"        to fakeBleMac,
                "Battery"    to "${getBattery()}%"
            )) }
        ))
    }

    private fun showFakePage(title: String, rows: List<Pair<String, String>>) {
        backAction = { showGeneralPage() }
        setTitle(title)
        val items = rows.map { (k, v) ->
            k to { showFakePage(k, listOf(k to v)) }
        }
        showList(items, showArrow = false, valueMap = rows.toMap())
    }

    private fun showQrPage() {
        backAction = { showMenuPage() }
        setTitle("QR code")
        contentFrame.removeAllViews()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity     = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }

        val qrSize = dp(180)
        val iv = ImageView(this).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            setImageBitmap(generateQr("MAC:$fakeBleMac\nMODEL:SmartWatch Pro\nFW:2.4.1", qrSize))
        }
        col.addView(iv, LinearLayout.LayoutParams(qrSize, qrSize).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(12)
        })

        val macTv = TextView(this).apply {
            text = fakeBleMac
            textSize = 12f
            setTextColor(Color.parseColor("#AAAAAA"))
            gravity = Gravity.CENTER
        }
        col.addView(macTv)

        contentFrame.addView(col, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply { gravity = Gravity.CENTER })
    }

    // ── Shared list builder ───────────────────────────────────────────────────

    private fun showList(
        items: List<Pair<String, () -> Unit>>,
        showArrow: Boolean = true,
        valueMap: Map<String, String> = emptyMap()
    ) {
        contentFrame.removeAllViews()
        val scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        for ((label, action) in items) {
            col.addView(buildListItem(label, valueMap[label], if (showArrow) action else null))
            col.addView(spacer(dp(6)))
        }
        scroll.addView(col, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ))
        contentFrame.addView(scroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
    }

    private fun buildListItem(label: String, value: String?, action: (() -> Unit)?): View {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity     = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background  = roundedBg(ITEM_BG, dp(10))
            if (action != null) setOnClickListener { action() }

            val labelTv = TextView(this@FakeSettingsActivity).apply {
                text     = label
                textSize = 14f
                setTextColor(WHITE)
            }
            addView(labelTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

            if (value != null) {
                val valTv = TextView(this@FakeSettingsActivity).apply {
                    text     = value
                    textSize = 13f
                    setTextColor(Color.parseColor("#AAAAAA"))
                }
                addView(valTv)
            }

            if (action != null) {
                val arrow = TextView(this@FakeSettingsActivity).apply {
                    text     = " ›"
                    textSize = 18f
                    setTextColor(ARROW)
                }
                addView(arrow)
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun openWatchfaceSelector() {
        val activePath = WatchfacePrefs.getActivePath(this)
        if (activePath != null) {
            WatchfaceSnapshot.bitmap = WatchfaceRepository.getPreviewCached(activePath)
        }
        startActivity(Intent(this, WatchfaceSelectorActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        })
    }

    private fun roundedBg(color: Int, radiusPx: Int) =
        android.graphics.drawable.GradientDrawable().apply {
            shape        = android.graphics.drawable.GradientDrawable.RECTANGLE
            setColor(color)
            cornerRadius = radiusPx.toFloat()
        }

    private fun spacer(h: Int) = View(this).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h)
    }

    private fun currentTime() = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

    private fun generateMac(): String = (1..6).map { "%02X".format(Random.nextInt(256)) }.joinToString(":")

    private fun getBattery(): Int {
        val bm = getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        return bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
    }

    private fun generateQr(content: String, sizePx: Int): Bitmap {
        val bits = QRCodeWriter().encode(
            content, BarcodeFormat.QR_CODE, sizePx, sizePx,
            mapOf(EncodeHintType.MARGIN to 1)
        )
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
        for (x in 0 until sizePx) for (y in 0 until sizePx)
            bmp.setPixel(x, y, if (bits[x, y]) Color.BLACK else Color.WHITE)
        return bmp
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun hideSystemBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onBackPressed() {
        val action = backAction
        if (action != null) action() else super.onBackPressed()
    }
}
