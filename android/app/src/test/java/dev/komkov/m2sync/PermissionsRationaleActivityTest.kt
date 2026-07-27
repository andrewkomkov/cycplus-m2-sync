package dev.komkov.m2sync

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Экран обоснования доступа. Сюда человек попадает из системного диалога
 * Health Connect, поэтому активити поднимается целиком — вместе с темой,
 * которую она себе ставит в onCreate.
 */
@RunWith(RobolectricTestRunner::class)
class PermissionsRationaleActivityTest {
    @get:Rule
    val compose = createAndroidComposeRule<PermissionsRationaleActivity>()

    private fun string(id: Int): String = compose.activity.getString(id)

    @Test
    fun `the screen names the app and explains why it asks`() {
        compose.onNodeWithText(string(R.string.app_name)).assertExists()
        compose.onNodeWithText(string(R.string.rationale)).assertExists()
        compose.onNodeWithText(string(R.string.rationale_what)).assertExists()
    }

    /** Обещание «ничего не уходит наружу» подкреплено списком того, что уходит внутрь. */
    @Test
    fun `the screen lists everything that goes into health connect`() {
        compose.onNodeWithText(string(R.string.rationale_ride)).assertExists()
        compose.onNodeWithText(string(R.string.rationale_heart)).assertExists()
        compose.onNodeWithText(string(R.string.rationale_route)).assertExists()
        compose.onNodeWithText(string(R.string.rationale_calories)).assertExists()
        compose.onNodeWithText(string(R.string.rationale_weight)).assertExists()
    }

    @Test
    fun `the button closes the screen`() {
        assertFalse(compose.activity.isFinishing)

        compose.onNodeWithText(string(R.string.rationale_close)).performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(compose.activity.isFinishing)
    }

    /** Экран переживает поворот: содержимое не зависит от сохранённого состояния. */
    @Test
    fun `the screen survives a recreation`() {
        compose.activityRule.scenario.recreate()
        compose.waitForIdle()

        compose.onNodeWithText(string(R.string.rationale)).assertExists()
        compose.onNodeWithText(string(R.string.rationale_close)).assertExists()
    }
}
