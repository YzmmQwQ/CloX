package com.ailife.clox.watchface.remote

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Bundle
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.ailife.clox.R
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class RemoteManagementActivity : AppCompatActivity() {

    private lateinit var switchServer: MaterialSwitch
    private lateinit var tvServerStatus: TextView
    private lateinit var tvAddress: TextView
    private lateinit var addressRow: View
    private lateinit var ivQrCode: ImageView
    private lateinit var tvQrHint: TextView
    private lateinit var btnCopy: ImageButton
    private lateinit var btnShare: ImageButton
    private lateinit var btnOpenBrowser: MaterialButton

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_remote_management)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        title = getString(R.string.pref_wf_remote_title)

        // fitsSystemWindows="true" on AppBarLayout handles status bar inset automatically

        switchServer    = findViewById(R.id.switchServer)
        tvServerStatus  = findViewById(R.id.tvServerStatus)
        tvAddress       = findViewById(R.id.tvAddress)
        addressRow      = findViewById(R.id.addressRow)
        ivQrCode        = findViewById(R.id.ivQrCode)
        tvQrHint        = findViewById(R.id.tvQrHint)
        btnCopy         = findViewById(R.id.btnCopy)
        btnShare        = findViewById(R.id.btnShare)
        btnOpenBrowser  = findViewById(R.id.btnOpenBrowser)

        switchServer.setOnCheckedChangeListener { _, checked ->
            if (checked) startServer() else WatchfaceServerManager.stop().also { showStopped() }
        }

        btnCopy.setOnClickListener {
            val url = currentUrl() ?: return@setOnClickListener
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("CloX Remote URL", url))
            Toast.makeText(this, R.string.wf_remote_copied, Toast.LENGTH_SHORT).show()
        }

        btnShare.setOnClickListener {
            val url = currentUrl() ?: return@setOnClickListener
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply { type = "text/plain"; putExtra(Intent.EXTRA_TEXT, url) }, null
            ))
        }

        btnOpenBrowser.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(currentUrl() ?: return@setOnClickListener)))
        }
    }

    override fun onResume() {
        super.onResume()
        if (WatchfaceServerManager.isRunning) {
            switchServer.isChecked = true
            val ip = getLocalIpAddress() ?: "?.?.?.?"
            showRunning("http://$ip:${WatchfaceServerManager.port}")
        } else {
            switchServer.isChecked = false
            showStopped()
        }
    }

    override fun onSupportNavigateUp(): Boolean { finish(); return true }

    private fun startServer() {
        val ok = WatchfaceServerManager.start(this)
        if (!ok) {
            switchServer.isChecked = false
            Toast.makeText(this, "Failed to start server", Toast.LENGTH_LONG).show()
            return
        }
        val ip = getLocalIpAddress() ?: "?.?.?.?"
        showRunning("http://$ip:${WatchfaceServerManager.port}")
    }

    private fun currentUrl(): String? {
        val ip = getLocalIpAddress() ?: return null
        return "http://$ip:${WatchfaceServerManager.port}"
    }

    private fun showRunning(url: String) {
        tvServerStatus.text = getString(R.string.wf_remote_running)
        tvAddress.text = url
        addressRow.visibility = View.VISIBLE
        btnOpenBrowser.visibility = View.VISIBLE
        ivQrCode.visibility = View.VISIBLE
        tvQrHint.visibility = View.VISIBLE

        lifecycleScope.launch {
            val qrSize = (240 * resources.displayMetrics.density).toInt()
            val bmp = withContext(Dispatchers.Default) { QrCodeHelper.generate(url, qrSize) }
            ivQrCode.setImageBitmap(bmp)
        }
    }

    private fun showStopped() {
        tvServerStatus.text = getString(R.string.wf_remote_stopped)
        addressRow.visibility = View.GONE
        ivQrCode.visibility = View.GONE
        tvQrHint.visibility = View.GONE
        btnOpenBrowser.visibility = View.GONE
    }

    private fun getLocalIpAddress(): String? {
        try {
            @Suppress("DEPRECATION")
            val ip = (getSystemService(Context.WIFI_SERVICE) as? WifiManager)
                ?.connectionInfo?.ipAddress ?: 0
            if (ip != 0) return String.format("%d.%d.%d.%d",
                ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
        } catch (_: Exception) {}
        return try {
            NetworkInterface.getNetworkInterfaces()?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.asSequence() }
                ?.filterIsInstance<java.net.Inet4Address>()
                ?.firstOrNull()?.hostAddress
        } catch (_: Exception) { null }
    }
}
