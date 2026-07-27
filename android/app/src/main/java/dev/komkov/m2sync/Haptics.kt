package dev.komkov.m2sync

import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/*
 * Отклик мотором: короткий словарь на весь экранный код.
 *
 * На месте вызова говорится, что произошло («переключили», «готово»), а каким
 * именно эффектом это отзовётся — решается здесь, в одном месте. Эффекты берём
 * из системной палитры: она сама учитывает и настройку «вибрация при касании»,
 * и то, на что способен мотор конкретного телефона. Своих длительностей не
 * задаём — оттого отклик и получается коротким, на грани заметности.
 */

/** Дискретное переключение: чип, слой карты, шаг вперёд. */
fun HapticFeedback.tick() = performHapticFeedback(HapticFeedbackType.SegmentTick)

/** Деление под пальцем при протяжке: их много подряд, поэтому эффект слабее. */
fun HapticFeedback.scrubTick() = performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)

/** Тумблер: включили или выключили. */
fun HapticFeedback.toggle(on: Boolean) = performHapticFeedback(if (on) HapticFeedbackType.ToggleOn else HapticFeedbackType.ToggleOff)

/** Долгое нажатие сработало — дальше начинается выделение. */
fun HapticFeedback.longPress() = performHapticFeedback(HapticFeedbackType.LongPress)

/** Палец отпущен, состояние вернулось на место: жест закончен. */
fun HapticFeedback.gestureEnd() = performHapticFeedback(HapticFeedbackType.GestureEnd)

/** Весомое подтверждение: долгая работа закончилась удачно или сменился экран. */
fun HapticFeedback.done() = performHapticFeedback(HapticFeedbackType.Confirm)

/** Долгая работа закончилась ошибкой. */
fun HapticFeedback.failed() = performHapticFeedback(HapticFeedbackType.Reject)
