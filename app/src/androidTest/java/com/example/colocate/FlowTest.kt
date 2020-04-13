/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

package com.example.colocate

import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.bluetooth.BluetoothManager
import android.content.Context
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.ActivityTestRule
import androidx.test.rule.GrantPermissionRule
import com.example.colocate.persistence.SharedPreferencesSonarIdProvider
import com.example.colocate.status.CovidStatus
import com.example.colocate.status.SharedPreferencesStatusStorage
import com.example.colocate.testhelpers.TestApplicationContext
import com.example.colocate.testhelpers.TestCoLocateServiceDispatcher
import com.example.colocate.testhelpers.isToast
import org.hamcrest.CoreMatchers.not
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowTest {

    lateinit var targetContext: Context

    @get:Rule
    val activityRule: ActivityTestRule<FlowTestStartActivity> =
        ActivityTestRule(FlowTestStartActivity::class.java)

    @get:Rule
    val permissionRule: GrantPermissionRule =
        GrantPermissionRule.grant(ACCESS_FINE_LOCATION)

    private lateinit var testAppContext: TestApplicationContext

    @Before
    fun setup() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
        testAppContext = TestApplicationContext(activityRule)
        ensureBluetoothEnabled()
    }

    @After
    fun teardown() {
        testAppContext.shutdownMockServer()
    }

    @Test
    fun testRegistration() {
        resetStatusStorage()
        unsetSonarId()
        testAppContext.simulateBackendDelay(400)

        onView(withId(R.id.start_main_activity)).perform(click())

        onView(withId(R.id.confirm_onboarding)).perform(click())

        checkPermissionActivityIsShown()

        onView(withId(R.id.permission_continue)).perform(click())

        checkOkActivityIsShown()

        testAppContext.simulateActivationCodeReceived()

        verifyText(R.id.registrationStatusText, R.string.registration_finalising_setup)

        testAppContext.verifyRegistrationFlow()
        verifyText(R.id.registrationStatusText, R.string.registration_everything_is_working_ok)
        verifyCheckMySymptomsButtonState(isEnabled = true)
    }

    @Test
    fun testRegistrationRetry() {
        resetStatusStorage()
        unsetSonarId()
        testAppContext.simulateBackendResponse(isError = true)
        testAppContext.simulateBackendDelay(0)

        onView(withId(R.id.start_main_activity)).perform(click())

        onView(withId(R.id.confirm_onboarding)).perform(click())

        checkPermissionActivityIsShown()

        onView(withId(R.id.permission_continue)).perform(click())

        checkOkActivityIsShown()

        testAppContext.verifyReceivedRegistrationRequest()
        verifyText(R.id.registrationStatusText, R.string.registration_finalising_setup)
        verifyCheckMySymptomsButtonState(isEnabled = false)

        Thread.sleep(2_000)
        verifyText(R.id.registrationStatusText, R.string.registration_app_setup_failed)
        onView(withId(R.id.registrationRetryButton)).check(matches(isDisplayed()))
        verifyCheckMySymptomsButtonState(isEnabled = false)

        testAppContext.simulateBackendResponse(isError = false)

        onView(withId(R.id.registrationRetryButton)).perform(click())

        testAppContext.simulateActivationCodeReceived()

        testAppContext.verifyRegistrationFlow()
        verifyText(R.id.registrationStatusText, R.string.registration_everything_is_working_ok)
        onView(withId(R.id.registrationRetryButton)).check(matches(not(isDisplayed())))
        verifyCheckMySymptomsButtonState(isEnabled = true)
    }

    private fun verifyCheckMySymptomsButtonState(isEnabled: Boolean) {
        val matcher = if (isEnabled) {
            isEnabled()
        } else {
            not(isEnabled())
        }
        onView(withId(R.id.status_not_feeling_well)).check(matches(matcher))
    }

    @Test
    fun testBluetoothInteractions() {
        clearDatabase()
        setStatus(CovidStatus.OK)
        setValidSonarIdAndSecretKey()

        onView(withId(R.id.start_main_activity)).perform(click())

        testAppContext.simulateDeviceInProximity()

        checkCanTransitionToIsolateActivity()

        testAppContext.verifyReceivedProximityRequest()

        onView(withText(R.string.successfull_data_upload))
            .inRoot(isToast())
            .check(matches(isDisplayed()))
    }

    @Test
    fun testReceivingStatusUpdateNotification() {
        setStatus(CovidStatus.OK)
        setValidSonarId()

        onView(withId(R.id.start_main_activity)).perform(click())

        testAppContext.apply {
            simulateStatusUpdateReceived()
            clickOnStatusNotification()
        }

        // TODO: Is there a better way to detect AtRiskActivity without using a delay?
        // We believe without this delay it tries to find the disclaimer title in the notification panel and fails.
        Thread.sleep(100)
        checkAtRiskActivityIsShown()
    }

    @Test
    fun testExplanation() {
        unsetSonarId()

        onView(withId(R.id.start_main_activity)).perform(click())

        onView(withId(R.id.explanation_link)).perform(click())

        checkExplanationActivityIsShown()

        onView(withId(R.id.explanation_back)).perform(click())

        checkMainActivityIsShown()
    }

    @Test
    fun testLaunchWhenStateIsOk() {
        setStatus(CovidStatus.OK)
        setValidSonarId()

        onView(withId(R.id.start_main_activity)).perform(click())

        checkOkActivityIsShown()
    }

    @Test
    fun testLaunchWhenStateIsPotential() {
        setStatus(CovidStatus.POTENTIAL)
        setValidSonarId()

        onView(withId(R.id.start_main_activity)).perform(click())

        checkAtRiskActivityIsShown()
    }

    @Test
    fun testLaunchWhenStateIsRed() {
        setStatus(CovidStatus.RED)
        setValidSonarId()

        onView(withId(R.id.start_main_activity)).perform(click())

        checkIsolateActivityIsShown()
    }

    private fun checkMainActivityIsShown() {
        onView(withId(R.id.confirm_onboarding)).check(matches(isDisplayed()))
    }

    private fun checkPermissionActivityIsShown() {
        onView(withId(R.id.permission_continue)).check(matches(isDisplayed()))
    }

    private fun checkExplanationActivityIsShown() {
        onView(withId(R.id.explanation_back)).check(matches(isDisplayed()))
    }

    private fun checkOkActivityIsShown() {
        onView(withId(R.id.status_initial)).check(matches(isDisplayed()))
    }

    private fun checkAtRiskActivityIsShown() {
        onView(withId(R.id.status_amber)).check(matches(isDisplayed()))
    }

    private fun checkIsolateActivityIsShown() {
        onView(withId(R.id.status_red)).check(matches(isDisplayed()))
    }

    private fun setStatus(covidStatus: CovidStatus) {
        val storage = activityRule.activity.statusStorage as SharedPreferencesStatusStorage
        storage.update(covidStatus)
    }

    private fun resetStatusStorage() {
        val storage = activityRule.activity.statusStorage as SharedPreferencesStatusStorage
        storage.reset()
    }

    private fun unsetSonarId() {
        val sonarIdProvider =
            activityRule.activity.sonarIdProvider as SharedPreferencesSonarIdProvider
        sonarIdProvider.clear()
    }

    private fun setValidSonarId() {
        val sonarIdProvider = activityRule.activity.sonarIdProvider
        sonarIdProvider.setSonarId(TestCoLocateServiceDispatcher.RESIDENT_ID)
    }

    private fun setValidSonarIdAndSecretKey() {
        setValidSonarId()

        val keyStorage = activityRule.activity.encryptionKeyStorage
        keyStorage.putBase64Key(TestCoLocateServiceDispatcher.encodedKey)
    }

    private fun clearDatabase() {
        val appDb = activityRule.activity.appDatabase
        appDb.clearAllTables()
    }

    private fun ensureBluetoothEnabled() {
        val activity = activityRule.activity
        val context = activity.application.applicationContext
        val manager = context.getSystemService(BluetoothManager::class.java) as BluetoothManager
        val adapter = manager.adapter

        adapter.enable()

        var attempts = 1
        while (attempts <= 20 && !adapter.isEnabled) {
            Thread.sleep(200)
            attempts++
        }

        if (!adapter.isEnabled) {
            fail("Failed enabling bluetooth")
        }
    }

    private fun checkCanTransitionToIsolateActivity() {
        onView(withId(R.id.status_not_feeling_well)).perform(scrollTo(), click())

        // Temperature step
        onView(withId(R.id.temperature_question)).check(matches(isDisplayed()))
        onView(withId(R.id.yes)).perform(click())
        onView(withId(R.id.yes)).check(matches(isChecked()))
        onView(withId(R.id.confirm_diagnosis)).perform(click())

        // Cough step
        onView(withId(R.id.cough_question)).check(matches(isDisplayed()))
        onView(withId(R.id.yes)).perform(click())
        onView(withId(R.id.yes)).check(matches(isChecked()))
        onView(withId(R.id.confirm_diagnosis)).perform(click())

        // Review Step
        onView(withId(R.id.review_description)).check(matches(isDisplayed()))
        onView(withId(R.id.confirm_diagnosis)).perform(click())

        onView(withId(R.id.status_red)).check(matches(isDisplayed()))
    }

    private fun verifyText(textViewId: Int, stringId: Int) {
        val text = targetContext.getString(stringId)
        onView(withId(textViewId)).check(matches(withText(text)))
    }
}
