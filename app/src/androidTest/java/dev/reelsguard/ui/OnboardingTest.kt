package dev.reelsguard.ui

import android.app.Activity
import android.app.Instrumentation
import android.content.IntentFilter
import android.provider.Settings as AndroidSettings
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import dev.reelsguard.MainActivity
import dev.reelsguard.R
import dev.reelsguard.ReelsGuardApp
import dev.reelsguard.core.Mode
import dev.reelsguard.core.CoverTheme
import dev.reelsguard.core.Settings
import dev.reelsguard.data.AppRepository
import java.util.Collections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class OnboardingTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var repository: AppRepository

    @Before fun resetSettings() {
        repository = (compose.activity.application as ReelsGuardApp).repository
        compose.waitUntil(5000) { repository.state.value.ready }
        runBlocking { repository.updateSettings { Settings() } }
        compose.waitForIdle()
    }

    @Test fun interfaceIsEnglishRegardlessOfDeviceLanguage() {
        assertEquals("en", compose.activity.resources.configuration.locales[0].language)
        assertEquals("Protection is off", compose.activity.getString(R.string.status_off))
    }

    @Test fun firstLaunchShowsConsentAndAccessibilityActionDirectly() {
        compose.onNodeWithText(text(R.string.disclosure_title)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.agree_open_settings)).assertIsDisplayed()
        compose.onNodeWithText(text(R.string.decline)).assertIsDisplayed()
        assertFalse(repository.state.value.settings.consent)
        assertFalse(repository.state.value.settings.enabled)
    }

    @Test fun acceptingEnablesProtectionBeforeOpeningAccessibilityAndReturnsToDashboard() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = instrumentation.addMonitor(
            IntentFilter(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS),
            Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null),
            true,
        )
        val observed = Collections.synchronizedList(mutableListOf<Settings>())
        val observerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        repository.state.onEach { observed.add(it.settings) }.launchIn(observerScope)
        try {
            compose.onNodeWithText(text(R.string.agree_open_settings)).performScrollTo().performClick()
            compose.waitUntil(5000) { monitor.hits == 1 }
            assertTrue(repository.state.value.settings.consent)
            assertTrue(repository.state.value.settings.enabled)
            assertFalse(synchronized(observed) { observed.any { it.consent && !it.enabled } })
            compose.onNodeWithText(text(R.string.protection)).performScrollTo().assertIsDisplayed()
            compose.activityRule.scenario.recreate()
            compose.onNodeWithText(text(R.string.protection)).performScrollTo().assertIsDisplayed()
        } finally {
            observerScope.cancel()
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test fun disclosureCanBeDeclinedWithoutEnablingProtection() {
        compose.onNodeWithText(text(R.string.decline)).performScrollTo().performClick()
        assertFalse(repository.state.value.settings.consent)
        assertFalse(repository.state.value.settings.enabled)
        compose.onNodeWithText(text(R.string.status_off)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.mode_block)).performScrollTo().assertIsDisplayed()
    }

    @Test fun privacyAndAboutAreReachableFromSetupAndDashboard() {
        openInformationAndReturn(R.string.privacy_title, R.string.privacy_local)
        openInformationAndReturn(R.string.about_title, R.string.about_description)
        compose.onNodeWithText(text(R.string.decline)).performScrollTo().performClick()
        openInformationAndReturn(R.string.privacy_title, R.string.privacy_local)
        openInformationAndReturn(R.string.about_title, R.string.about_description)
        assertFalse(repository.state.value.settings.consent)
        assertFalse(repository.state.value.settings.enabled)
    }

    @Test fun existingConsentKeepsSettingsAndOpensDashboard() {
        val saved = Settings(consent = true, enabled = false, mode = Mode.DAILY_LIMIT, dailyLimitMinutes = 27, hideButton = false)
        runBlocking { repository.updateSettings { saved } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.status_off)).performScrollTo().assertIsDisplayed()
        assertEquals(saved, repository.state.value.settings)
    }

    @Test fun coverThemeCanOverrideDeviceAppearanceAndSurvivesRecreation() {
        runBlocking { repository.updateSettings { it.copy(consent = true) } }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.settings_title)).performClick()
        compose.onNodeWithText(text(R.string.cover_color_dark)).performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.settings.coverTheme == CoverTheme.DARK }
        compose.activityRule.scenario.recreate()
        compose.onNodeWithText(text(R.string.cover_color_dark)).performScrollTo().assertIsSelected()
        compose.onNodeWithText(text(R.string.cover_color_device)).performScrollTo().performClick()
        compose.waitUntil(5000) { repository.state.value.settings.coverTheme == CoverTheme.SYSTEM }
    }

    private fun openInformationAndReturn(title: Int, body: Int) {
        compose.onNodeWithText(text(title)).assertIsDisplayed().performClick()
        compose.onNodeWithText(text(body)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(text(R.string.back_home)).assertIsDisplayed().performClick()
    }

    private fun text(id: Int) = compose.activity.getString(id)
}
