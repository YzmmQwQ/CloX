package com.ailife.clox.crash

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.ailife.clox.databinding.ActivityCrashBinding
import java.io.File

class CrashActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCrashBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        binding = ActivityCrashBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val report = loadReport()

        binding.crashText.text = report

        binding.btnCopy.setOnClickListener {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CloX crash report", report))
            Toast.makeText(this, "Copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        binding.btnShare.setOnClickListener {
            startActivity(Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_SUBJECT, "CloX crash report")
                    putExtra(Intent.EXTRA_TEXT, report)
                },
                "Share crash report"
            ))
        }

        binding.btnClose.setOnClickListener { finish() }
    }

    private fun loadReport(): String {
        val path = intent.getStringExtra(EXTRA_CRASH_FILE) ?: return "No crash data available."
        return try {
            File(path).readText()
        } catch (_: Exception) {
            "Failed to read crash report."
        }
    }

    companion object {
        const val EXTRA_CRASH_FILE = "crash_file"
    }
}
