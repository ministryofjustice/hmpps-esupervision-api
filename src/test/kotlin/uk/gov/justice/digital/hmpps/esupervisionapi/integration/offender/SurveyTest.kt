package uk.gov.justice.digital.hmpps.esupervisionapi.integration.offender

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.SurveyContents
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

class SurveyTest {

  val offender = OffenderDto(
    UUID.randomUUID(),
    "Bob",
    "Bronson",
    LocalDate.of(1990, 1, 1),
    OffenderStatus.VERIFIED,
    createdAt = Instant.now(),
    "bob@example.com",
    photoUrl = null,
    firstCheckin = LocalDate.now(ZoneId.of("UTC")).plusDays(10),
    checkinInterval = CheckinInterval.FOUR_WEEKS,
  )

  val checkinTemplate = OffenderCheckinDto(
    UUID.randomUUID(),
    status = CheckinStatus.SUBMITTED,
    dueDate = LocalDate.now().atStartOfDay(ZoneId.of("UTC")),
    offender = offender,
    submittedOn = Instant.now(),
    surveyResponse = mapOf(),
    createdBy = "alice",
    createdAt = Instant.now(),
    reviewedBy = null,
    videoUrl = null,
    autoIdCheck = AutomatedIdVerificationResult.MATCH,
    manualIdCheck = null,
  )

  @Test
  fun `get flagged fields`() {
    val survey = makeSurvey(SurveyVersion.V20250710pilot)
    val checkin = checkinTemplate.copy(surveyResponse = survey)

    val flagged = checkin.flaggedResponses.toSet()
    Assertions.assertEquals(setOf<String>(), flagged)

    val updatedSurvey = survey.toMutableMap()

    updatedSurvey.put("mentalHealth", "STRUGGLING" as Object)
    Assertions.assertEquals(
      setOf("mentalHealth"),
      checkin.copy(surveyResponse = updatedSurvey.toMap()).flaggedResponses.toSet(),
    )

    updatedSurvey.put("assistance", listOf("HOUSING", "DRUGS") as Object)
    Assertions.assertEquals(
      setOf("mentalHealth", "assistance"),
      checkin.copy(surveyResponse = updatedSurvey.toMap()).flaggedResponses.toSet(),
    )
  }

  @Test
  fun `get flagged fields when no version is present`() {
    val checkin = checkinTemplate.copy(surveyResponse = mapOf())
    Assertions.assertEquals(
      listOf<String>(),
      checkin.flaggedResponses,
    )

    Assertions.assertEquals(
      listOf<String>(),
      checkin.copy(surveyResponse = mapOf("callback" to "YES" as Object)).flaggedResponses,
    )
  }
}

enum class SurveyVersion(val version: String) {
  V20250710pilot("2025-07-10@pilot"),
}

fun makeSurvey(version: SurveyVersion): SurveyContents {
  when (version) {
    SurveyVersion.V20250710pilot -> {
      val survey = mapOf(
        "version" to version.version as Object,
        "mentalHealth" to "WELL" as Object,
        "assistance" to listOf("NO_HELP") as Object,
        "callback" to "NO" as Object,
      )
      return survey
    }
  }
}
