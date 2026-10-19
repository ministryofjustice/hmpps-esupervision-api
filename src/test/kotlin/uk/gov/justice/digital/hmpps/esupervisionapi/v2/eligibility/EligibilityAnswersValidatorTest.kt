package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import jakarta.validation.Validation
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EligibilityAnswers
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupCompletionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.ReactivateOffenderRequest
import java.util.UUID

class EligibilityAnswersValidatorTest {
  private val factory = Validation.buildDefaultValidatorFactory()
  private val validator = factory.validator

  @AfterEach
  fun closeValidatorFactory() {
    factory.close()
  }

  @ParameterizedTest
  @MethodSource("answerCombinations")
  fun `validates paired nullness independently of boolean values`(
    accreditedProgramme: Boolean?,
    youthSentence: Boolean?,
    deviceRestriction: Boolean,
    valid: Boolean,
  ) {
    val violations = validator.validate(EligibilityAnswers(accreditedProgramme, deviceRestriction, youthSentence))

    assertEquals(valid, violations.isEmpty())
    if (!valid) {
      val violation = violations.single()
      assertEquals("youthSentence", violation.propertyPath.toString())
      assertEquals(
        "accreditedProgramme and youthSentence must either both be null or both be non-null",
        violation.message,
      )
    }
  }

  @ParameterizedTest
  @MethodSource("answerCombinations")
  fun `cascades validation through every enclosing request`(
    accreditedProgramme: Boolean?,
    youthSentence: Boolean?,
    deviceRestriction: Boolean,
    valid: Boolean,
  ) {
    val answers = EligibilityAnswers(accreditedProgramme, deviceRestriction, youthSentence)
    for (request in requests(answers)) {
      val violations = validator.validate(request)
      assertEquals(valid, violations.isEmpty(), request.javaClass.simpleName)
      if (!valid) {
        assertEquals("eligibilityAnswers.youthSentence", violations.single().propertyPath.toString())
      }
    }
  }

  @Test
  fun `optional eligibility answers remain valid`() {
    for (request in requests(null)) {
      assertTrue(validator.validate(request).isEmpty(), request.javaClass.simpleName)
    }
  }

  private fun requests(answers: EligibilityAnswers?): List<Any> = listOf(
    OffenderInfo(
      setupUuid = UUID.randomUUID(),
      practitionerId = "PRACT001",
      crn = "X123456",
      firstCheckin = null,
      checkinInterval = null,
      contactPreference = ContactPreference.PHONE,
      eligibilityAnswers = answers,
    ),
    OffenderSetupCompletionDto(answers),
    ReactivateOffenderRequest(
      requestedBy = "PRACT001",
      reason = "Resuming check-ins",
      eligibilityAnswers = answers,
    ),
  )

  companion object {
    @JvmStatic
    fun answerCombinations(): List<Arguments> = listOf(null, true, false).flatMap { accreditedProgramme ->
      listOf(null, true, false).flatMap { youthSentence ->
        listOf(true, false).map { deviceRestriction ->
          Arguments.of(
            accreditedProgramme,
            youthSentence,
            deviceRestriction,
            (accreditedProgramme == null) == (youthSentence == null),
          )
        }
      }
    }
  }
}
