package dev.komkov.m2sync

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Словарь отклика: каждое событие экрана должно уходить своим эффектом, иначе
 * «переключили» и «готово» ощущаются одинаково и подсказка пропадает.
 */
class HapticsTest {
    private class Recorder : HapticFeedback {
        val performed = mutableListOf<HapticFeedbackType>()

        override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) {
            performed += hapticFeedbackType
        }
    }

    private fun effectOf(call: HapticFeedback.() -> Unit): HapticFeedbackType {
        val recorder = Recorder()
        recorder.call()
        assertEquals(1, recorder.performed.size)
        return recorder.performed.single()
    }

    @Test
    fun `discrete switching ticks`() {
        assertEquals(HapticFeedbackType.SegmentTick, effectOf { tick() })
    }

    @Test
    fun `scrubbing uses the weaker of the two ticks`() {
        assertEquals(HapticFeedbackType.SegmentFrequentTick, effectOf { scrubTick() })
    }

    @Test
    fun `a toggle tells on from off`() {
        assertEquals(HapticFeedbackType.ToggleOn, effectOf { toggle(true) })
        assertEquals(HapticFeedbackType.ToggleOff, effectOf { toggle(false) })
    }

    @Test
    fun `holding and releasing have their own effects`() {
        assertEquals(HapticFeedbackType.LongPress, effectOf { longPress() })
        assertEquals(HapticFeedbackType.GestureEnd, effectOf { gestureEnd() })
    }

    @Test
    fun `the end of a long job says how it went`() {
        assertEquals(HapticFeedbackType.Confirm, effectOf { done() })
        assertEquals(HapticFeedbackType.Reject, effectOf { failed() })
    }
}
