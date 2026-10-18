package com.yzmm.clox.rtos.password

import java.security.MessageDigest

object PasswordValidator {

    fun hash(sequence: List<Int>): String {
        val bytes = sequence.joinToString(",").toByteArray()
        return MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }

    /** Keypad: 4+ digits, not all the same value. */
    fun isValidKeypadPassword(sequence: List<Int>): Boolean {
        if (sequence.size < 4) return false
        return sequence.toSet().size > 1
    }

    /** Morse: 4+ symbols (0=dot, 1=dash), not all the same. */
    fun isValidMorsePassword(sequence: List<Int>): Boolean {
        if (sequence.size < 4) return false
        return sequence.toSet().size > 1
    }

    /**
     * Sliding-window match: check if [target] appears as a contiguous subsequence
     * anywhere in [window]. Used to unlock with a stream of inputs — every new tap
     * tries to match the last N inputs against the password.
     */
    fun slidingMatch(window: List<Int>, target: List<Int>): Boolean {
        if (target.isEmpty() || window.size < target.size) return false
        val n = target.size
        for (i in 0..window.size - n) {
            if (window.subList(i, i + n) == target) return true
        }
        return false
    }
}
