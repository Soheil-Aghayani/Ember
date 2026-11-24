package ember.subs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NodeTesterTest {
    @Test
    fun threeSamplesUseMedianAndSpread() {
        val result = summarizePings(listOf(31, 11, 20))

        assertEquals(20, result.medianMs)
        assertEquals(20, result.jitterMs)
        assertEquals(1.0, result.successRate, 0.0)
    }

    @Test
    fun twoSamplesUseTheirAverageAndReportPartialReliability() {
        val result = summarizePings(listOf(10, 21))

        assertEquals(15, result.medianMs)
        assertEquals(11, result.jitterMs)
        assertEquals(2.0 / 3.0, result.successRate, 0.0)
    }

    @Test
    fun oneSampleIsNotReportedAsReliableLatency() {
        val result = summarizePings(listOf(12))

        assertNull(result.medianMs)
        assertNull(result.jitterMs)
        assertEquals(1.0 / 3.0, result.successRate, 0.0)
    }
}
