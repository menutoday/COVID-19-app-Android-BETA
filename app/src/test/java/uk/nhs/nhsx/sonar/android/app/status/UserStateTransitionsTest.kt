/*
 * Copyright © 2020 NHSX. All rights reserved.
 */

package uk.nhs.nhsx.sonar.android.app.status

import org.assertj.core.api.Assertions.assertThat
import org.joda.time.DateTime
import org.joda.time.DateTimeZone.UTC
import org.joda.time.LocalDate
import org.junit.Test
import uk.nhs.nhsx.sonar.android.app.status.Symptom.ANOSMIA
import uk.nhs.nhsx.sonar.android.app.status.Symptom.COUGH
import uk.nhs.nhsx.sonar.android.app.status.Symptom.SNEEZE
import uk.nhs.nhsx.sonar.android.app.status.Symptom.STOMACH
import uk.nhs.nhsx.sonar.android.app.status.Symptom.TEMPERATURE
import uk.nhs.nhsx.sonar.android.app.status.UserStateTransitions.diagnose
import uk.nhs.nhsx.sonar.android.app.status.UserStateTransitions.isSymptomatic
import uk.nhs.nhsx.sonar.android.app.status.UserStateTransitions.diagnoseForCheckin
import uk.nhs.nhsx.sonar.android.app.status.UserStateTransitions.expireAmberState
import uk.nhs.nhsx.sonar.android.app.status.UserStateTransitions.transitionOnContactAlert
import uk.nhs.nhsx.sonar.android.app.util.nonEmptySetOf

class UserStateTransitionsTest {

    private val today = LocalDate(2020, 4, 10)
    private val symptomsWithoutTemperature = nonEmptySetOf(COUGH)
    private val symptomsWithTemperature = nonEmptySetOf(TEMPERATURE)

    @Test
    fun `diagnose - when symptoms date is 7 days ago or more, and no temperature`() {
        val sevenDaysAgoOrMore = today.minusDays(7)

        val state = diagnose(DefaultState, sevenDaysAgoOrMore, symptomsWithoutTemperature, today)

        assertThat(state).isEqualTo(RecoveryState)
    }

    @Test
    fun `diagnose - when symptoms date is 7 days ago or more, no temperature, and current state is Amber`() {
        val amberState = buildAmberState()
        val sevenDaysAgoOrMore = today.minusDays(7)

        val state = diagnose(amberState, sevenDaysAgoOrMore, symptomsWithoutTemperature, today)

        assertThat(state).isEqualTo(amberState)
    }

    @Test
    fun `diagnose - when symptoms date is 7 days ago or more, with temperature`() {
        val sevenDaysAgoOrMore = today.minusDays(7)
        val sevenDaysAfterSymptoms = DateTime(2020, 4, 11, 7, 0).toDateTime(UTC)

        val state = diagnose(DefaultState, sevenDaysAgoOrMore, symptomsWithTemperature, today)

        assertThat(state).isEqualTo(RedState(sevenDaysAfterSymptoms, symptomsWithTemperature))
    }

    @Test
    fun `diagnose - when symptoms date is less than 7 days ago, and no temperature`() {
        val sevenDaysAgoOrMore = today.minusDays(6)
        val sevenDaysAfterSymptoms = DateTime(2020, 4, 11, 7, 0).toDateTime(UTC)

        val state = diagnose(DefaultState, sevenDaysAgoOrMore, symptomsWithoutTemperature, today)

        assertThat(state).isEqualTo(RedState(sevenDaysAfterSymptoms, symptomsWithoutTemperature))
    }

    @Test
    fun `diagnose - when symptoms date is less than 7 days ago, with temperature`() {
        val sevenDaysAgoOrMore = today.minusDays(6)
        val sevenDaysAfterSymptoms = DateTime(2020, 4, 11, 7, 0).toDateTime(UTC)

        val state = diagnose(DefaultState, sevenDaysAgoOrMore, symptomsWithTemperature, today)

        assertThat(state).isEqualTo(RedState(sevenDaysAfterSymptoms, symptomsWithTemperature))
    }

    @Test
    fun `diagnoseForCheckin - with temperature`() {
        val tomorrow = DateTime(2020, 4, 11, 7, 0).toDateTime(UTC)

        val state = diagnoseForCheckin(setOf(TEMPERATURE), today)

        assertThat(state).isEqualTo(CheckinState(tomorrow, nonEmptySetOf(TEMPERATURE)))
    }

    @Test
    fun `diagnoseForCheckin - with cough and temperature`() {
        val tomorrow = DateTime(2020, 4, 11, 7, 0).toDateTime(UTC)

        val state = diagnoseForCheckin(setOf(COUGH, TEMPERATURE), today)

        assertThat(state).isEqualTo(CheckinState(tomorrow, nonEmptySetOf(COUGH, TEMPERATURE)))
    }

    @Test
    fun `diagnoseForCheckin - with cough`() {
        val state = diagnoseForCheckin(setOf(COUGH), today)

        assertThat(state).isEqualTo(RecoveryState)
    }

    @Test
    fun `diagnoseForCheckin - with anosmia`() {
        val state = diagnoseForCheckin(setOf(ANOSMIA), today)

        assertThat(state).isEqualTo(RecoveryState)
    }

    @Test
    fun `diagnoseForCheckin - with no symptoms`() {
        val state = diagnoseForCheckin(emptySet(), today)

        assertThat(state).isEqualTo(DefaultState)
    }

    @Test
    fun `test transitionOnContactAlert`() {
        assertThat(transitionOnContactAlert(DefaultState)).isInstanceOf(AmberState::class.java)
        assertThat(transitionOnContactAlert(RecoveryState)).isInstanceOf(AmberState::class.java)
        assertThat(transitionOnContactAlert(buildAmberState())).isNull()
        assertThat(transitionOnContactAlert(buildRedState())).isNull()
        assertThat(transitionOnContactAlert(buildCheckinState())).isNull()
    }

    @Test
    fun `test expireAmberState`() {
        val amberState = buildAmberState()
        val redState = buildRedState()
        val checkinState = buildCheckinState()

        val expiredAmberState = buildAmberState(until = DateTime.now().minusSeconds(1))
        val expiredRedState = buildRedState(until = DateTime.now().minusSeconds(1))
        val expiredCheckinState = buildCheckinState(until = DateTime.now().minusSeconds(1))

        assertThat(expireAmberState(DefaultState)).isEqualTo(DefaultState)
        assertThat(expireAmberState(RecoveryState)).isEqualTo(RecoveryState)
        assertThat(expireAmberState(amberState)).isEqualTo(amberState)
        assertThat(expireAmberState(redState)).isEqualTo(redState)
        assertThat(expireAmberState(checkinState)).isEqualTo(checkinState)
        assertThat(expireAmberState(expiredRedState)).isEqualTo(expiredRedState)
        assertThat(expireAmberState(expiredCheckinState)).isEqualTo(expiredCheckinState)

        assertThat(expireAmberState(expiredAmberState)).isEqualTo(DefaultState)
    }

    @Test
    fun `isSymptomatic - with cough, temperature or loss of smell`() {
        assertThat(isSymptomatic(setOf(COUGH))).isTrue()
        assertThat(isSymptomatic(setOf(TEMPERATURE))).isTrue()
        assertThat(isSymptomatic(setOf(ANOSMIA))).isTrue()
    }

    @Test
    fun `isSymptomatic - with anything other than cough, temperature or loss of smell`() {
        assertThat(isSymptomatic(setOf(STOMACH, SNEEZE))).isFalse()
    }
}
