package com.yzmm.clox.watchface.remote

import android.content.Context
import android.util.Log
import java.security.KeyStore
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocketFactory

object WatchfaceServerManager {

    private const val TAG = "WatchfaceServerMgr"

    // Bundled self-signed cert so the server can also speak HTTPS. Not a security boundary — the
    // key ships in the APK — it only lets browsers connect over https://. Clients still see a
    // "not private" warning to click through (self-signed cert on a raw LAN IP).
    private const val KEYSTORE_ASSET = "clox_remote_keystore.p12"
    private val KEYSTORE_PASS = "cloxremote".toCharArray()

    private var httpServer: WatchfaceWebServer? = null
    private var httpsServer: WatchfaceWebServer? = null

    val isRunning: Boolean get() = httpServer?.isAlive == true
    val port: Int get() = WatchfaceWebServer.DEFAULT_PORT
    val httpsPort: Int get() = WatchfaceWebServer.DEFAULT_HTTPS_PORT

    fun start(ctx: Context): Boolean {
        if (isRunning) return true
        val app = ctx.applicationContext

        // Plain HTTP (primary — no browser warning).
        val ok = try {
            val s = WatchfaceWebServer(app, port)
            s.start()
            httpServer = s
            Log.i(TAG, "HTTP server started on port $port")
            true
        } catch (e: Exception) {
            Log.e(TAG, "HTTP start failed", e)
            false
        }
        if (!ok) return false

        // HTTPS on a separate port — best-effort; a keystore problem must not take HTTP down.
        try {
            val s = WatchfaceWebServer(app, httpsPort)
            s.makeSecure(buildSslFactory(app), null)
            s.start()
            httpsServer = s
            Log.i(TAG, "HTTPS server started on port $httpsPort")
        } catch (e: Exception) {
            Log.e(TAG, "HTTPS start failed (HTTP still running)", e)
            httpsServer = null
        }
        return true
    }

    fun stop() {
        httpServer?.stop();  httpServer = null
        httpsServer?.stop(); httpsServer = null
        Log.i(TAG, "Servers stopped")
    }

    private fun buildSslFactory(ctx: Context): SSLServerSocketFactory {
        val keystore = KeyStore.getInstance("PKCS12")
        ctx.assets.open(KEYSTORE_ASSET).use { keystore.load(it, KEYSTORE_PASS) }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keystore, KEYSTORE_PASS)
        val sslContext = SSLContext.getInstance("TLS")
        sslContext.init(kmf.keyManagers, null, null)
        return sslContext.serverSocketFactory
    }
}
