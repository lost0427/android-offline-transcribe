package com.voiceping.offlinetranscription.service

import org.junit.Test
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VadNormalizeTest {

    private fun rms(x: FloatArray): Double {
        var s = 0.0
        for (v in x) s += v.toDouble() * v
        return sqrt(s / x.size)
    }

    @Test
    fun boostsQuietFarFieldAudioTowardsTarget() {
        val quiet = FloatArray(1600) { if (it % 2 == 0) 0.02f else -0.02f }  // rms 0.02
        val out = normalizeForVad(quiet)
        assertTrue(rms(out) > 0.09, "expected ~0.1 rms, got ${rms(out)}")
    }

    @Test
    fun leavesAlreadyLoudAudioSane() {
        val loud = FloatArray(1600) { if (it % 2 == 0) 0.5f else -0.5f }  // rms 0.5
        val out = normalizeForVad(loud)
        // gain clamps at 0.25 → rms ~0.125, still well within range and never a no-op
        assertTrue(rms(out) < 0.2 && out.all { kotlin.math.abs(it) <= 1f })
    }

    @Test
    fun nearSilenceIsUnchanged() {
        val silence = FloatArray(1600) { 1e-6f }
        assertTrue(normalizeForVad(silence) === silence)
    }

    @Test
    fun normalLevelMayBeReturnedAsIs() {
        val atTarget = FloatArray(1600) { if (it % 2 == 0) 0.1f else -0.1f }
        val out = normalizeForVad(atTarget)
        assertEquals(0.1, rms(out), 0.01)
    }

    @Test
    fun emptyInputIsSafe() {
        assertEquals(0, normalizeForVad(FloatArray(0)).size)
    }
}
