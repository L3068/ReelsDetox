package dev.reelsguard.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import dev.reelsguard.MainActivity
import dev.reelsguard.R
import dev.reelsguard.ReelsGuardApp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun interfaceIsEnglishRegardlessOfDeviceLanguage() {
        assertEquals("en", compose.activity.resources.configuration.locales[0].language)
        assertEquals("Protection is off", compose.activity.getString(R.string.status_off))
    }

    @Test fun disclosureCanBeDeclinedWithoutEnablingProtection() {
        val repository = (compose.activity.application as ReelsGuardApp).repository
        compose.waitUntil(5000) { repository.state.value.ready }
        runBlocking { repository.revokeConsent() }
        compose.waitForIdle()
        fun text(id: Int) = compose.activity.getString(id)
        compose.onNodeWithText(text(R.string.review_access)).performScrollTo().performClick()
        compose.onNodeWithText(text(R.string.disclosure_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.decline)).performScrollTo().performClick()
        assertFalse(repository.state.value.settings.consent)
        assertFalse(repository.state.value.settings.enabled)
        compose.onNodeWithText(text(R.string.status_off)).performScrollTo().assertIsDisplayed()
    }
}
