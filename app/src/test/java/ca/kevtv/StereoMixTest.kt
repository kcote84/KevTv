package ca.kevtv

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StereoMixTest {
    @Test fun centerVoicesReachBothChannels() {
        for (channels in listOf(3, 5, 6, 7, 8)) {
            val matrix = StereoMix.coefficients(channels)
            assertTrue(matrix[4] > 0f)
            assertEquals(matrix[4], matrix[5], 0f)
        }
    }

    @Test fun inputStereoIsUnchangedAndMonoIsDuplicated() {
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 1f), StereoMix.coefficients(2), 0f)
        assertArrayEquals(floatArrayOf(1f, 1f), StereoMix.coefficients(1), 0f)
    }

    @Test fun remixHasHeadroomAndKeepsLeftAndRightDistinct() {
        for (channels in 3..8) {
            val matrix = StereoMix.coefficients(channels)
            for (out in 0..1) assertTrue((0 until channels).sumOf { matrix[it * 2 + out].toDouble() } <= 1.000001)
            assertEquals(0f, matrix[1], 0f)
            assertEquals(0f, matrix[2], 0f)
        }
    }
}
