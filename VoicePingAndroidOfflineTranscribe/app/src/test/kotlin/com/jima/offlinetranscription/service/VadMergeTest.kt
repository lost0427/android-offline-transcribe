package com.voiceping.offlinetranscription.service

import org.junit.Test
import kotlin.test.assertEquals

class VadMergeTest {

    private fun seg(a: Long, b: Long) = VadSegment(a, b)

    @Test
    fun emptyStaysEmpty() {
        assertEquals(emptyList(), mergeVadSegments(emptyList(), 30_000))
    }

    @Test
    fun singleSegmentPassesThrough() {
        assertEquals(listOf(seg(0, 5_000)), mergeVadSegments(listOf(seg(0, 5_000)), 30_000))
    }

    @Test
    fun shortFragmentsMergeIntoOneChunk() {
        // Four 1-2 s fragments within 30 s collapse into a single chunk.
        val merged = mergeVadSegments(
            listOf(seg(0, 1_900), seg(3_900, 5_000), seg(8_600, 11_000), seg(13_000, 14_100)),
            30_000
        )
        assertEquals(listOf(seg(0, 14_100)), merged)
    }

    @Test
    fun chunkClosesBeforeExceedingMax() {
        val merged = mergeVadSegments(
            listOf(seg(0, 20_000), seg(25_000, 31_000)),
            30_000
        )
        // 31_000 - 0 > 30_000, so the second segment starts a new chunk.
        assertEquals(listOf(seg(0, 20_000), seg(25_000, 31_000)), merged)
    }

    @Test
    fun oversizeSingleSegmentIsKeptForLaterSplitting() {
        val merged = mergeVadSegments(listOf(seg(22_500, 55_500)), 30_000)
        assertEquals(listOf(seg(22_500, 55_500)), merged)
    }

    @Test
    fun meetingLayoutProducesThreeChunks() {
        val merged = mergeVadSegments(
            listOf(seg(0, 1_900), seg(3_900, 5_000), seg(8_600, 11_000),
                seg(13_000, 14_100), seg(22_500, 55_500), seg(55_700, 60_000)),
            30_000
        )
        assertEquals(listOf(seg(0, 14_100), seg(22_500, 55_500), seg(55_700, 60_000)), merged)
    }
}
