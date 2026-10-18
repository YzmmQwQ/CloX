package com.yzmm.clox.qs

import android.util.Log

class QsUserService : IQsUserService.Stub() {

    companion object { private const val TAG = "QsUserService" }

    override fun destroy() { System.exit(0) }
    override fun exit() { System.exit(0) }

    override fun execShell(command: String): Boolean {
        Log.d(TAG, "execShell: $command")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            process.waitFor() == 0
        } catch (e: Exception) {
            Log.e(TAG, "execShell failed: ${e.message}")
            false
        }
    }

    override fun execShellOutput(command: String): String {
        Log.d(TAG, "execShellOutput: $command")
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = process.inputStream.bufferedReader().readText()
            process.waitFor()
            out
        } catch (e: Exception) {
            Log.e(TAG, "execShellOutput failed: ${e.message}")
            ""
        }
    }
}
