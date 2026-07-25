package dev.komkov.m2sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Пакеты сняты с Mi Smart Scale 2 в эфире: сырое 14530 при делителе 200
 * даёт те самые 72.65 кг, которые весы показали на экране.
 */
class ScaleClientTest {

    /** ctrl + сырой вес little-endian + семь байт отметки времени. */
    private fun packet(ctrl: Int, raw: Int): ByteArray = byteArrayOf(
        ctrl.toByte(),
        (raw and 0xFF).toByte(),
        ((raw shr 8) and 0xFF).toByte(),
        0xEA.toByte(), 0x07, 7, 25, 8, 29, 24,
    )

    @Test
    fun `stabilized reading in kilograms`() {
        val r = ScaleClient.parse(packet(0x22, 14530))!!
        assertEquals(72.65, r.kilograms, 0.001)
        assertTrue(r.stabilized)
        assertFalse(r.removed)
    }

    @Test
    fun `stepping off keeps the value and raises the flag`() {
        val r = ScaleClient.parse(packet(0xA2, 14530))!!
        assertEquals(72.65, r.kilograms, 0.001)
        assertTrue(r.stabilized)
        assertTrue(r.removed)
    }

    @Test
    fun `a measurement in progress is not stabilized`() {
        val r = ScaleClient.parse(packet(0x02, 13450))!!
        assertEquals(67.25, r.kilograms, 0.001)
        assertFalse(r.stabilized)
    }

    @Test
    fun `pounds and jin are converted to kilograms`() {
        // 160.19 lb — это 72.66 кг, 145.3 цзиня — ровно 72.65.
        assertEquals(72.661, ScaleClient.parse(packet(0x21, 16019))!!.kilograms, 0.01)
        assertEquals(72.65, ScaleClient.parse(packet(0x30, 14530))!!.kilograms, 0.01)
    }

    @Test
    fun `short packets and zero weight are ignored`() {
        assertNull(ScaleClient.parse(byteArrayOf(0x22, 0x02, 0x38)))
        assertNull(ScaleClient.parse(packet(0x02, 0)))
    }
}
