package com.voiceping.offlinetranscription.service

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnergyCutTest {

    private fun flat(n: Int, amp: Float) = FloatArray(n) { amp }

    @Test
    fun picksTheSilentFrameCentre() {
        val frame = 100
        // loud, loud, silent, loud → quietest is the 3rd frame; centre = 2*100 + 50
        val samples = flat(frame, 0.8f) + flat(frame, 0.7f) + flat(frame, 0.0f) + flat(frame, 0.9f)
        assertEquals(250, quietestFrameOffset(samples, frame))
    }

    @Test
    fun fallsBackToEndWhenTooShort() {
        val samples = FloatArray(50) { 0.5f }
        assertEquals(50, quietestFrameOffset(samples, 100))
    }

    @Test
    fun allLoudReturnsSomeFrameCentreNotEnd() {
        val frame = 100
        val samples = flat(frame, 0.5f) + flat(frame, 0.5f)
        val offset = quietestFrameOffset(samples, frame)
        assertTrue(offset in 1 until samples.size, "expected an in-range cut, got $offset")
    }

    @Test
    fun degenerateFrameIsSafe() {
        assertEquals(10, quietestFrameOffset(FloatArray(10) { 0.1f }, 0))
    }
}
