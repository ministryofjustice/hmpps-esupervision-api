package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.MediaType.APPLICATION_JSON
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import uk.gov.justice.digital.hmpps.esupervisionapi.config.HmppsESupervisionExceptionHandler
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.storage.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup.OffenderSetupResource
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup.OffenderSetupService
import java.time.Instant
import java.util.UUID

class EligibilityAnswersRequestValidationTest {
  private val service: OffenderSetupService = mock()
  private val uploadService: S3UploadService = mock()
  private val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
  private val mvc = standaloneSetup(OffenderSetupResource(service, uploadService))
    .setControllerAdvice(HmppsESupervisionExceptionHandler())
    .setValidator(validator)
    .build()

  @AfterEach
  fun closeValidatorFactory() {
    validator.close()
  }

  @ParameterizedTest
  @CsvSource("true, null", "false, null", "null, true", "null, false")
  fun `setup endpoints reject mismatched nullness before calling services`(
    accreditedProgramme: String,
    youthSentence: String,
  ) {
    val answers = """
      {"accreditedProgramme":$accreditedProgramme,"deviceRestriction":false,"youthSentence":$youthSentence}
    """.trimIndent()
    val requests = listOf(
      "/v2/offender_setup" to setupRequest(""""eligibilityAnswers":$answers"""),
      "/v2/offender_setup/${UUID.randomUUID()}/complete" to """{"eligibilityAnswers":$answers}""",
    )

    for ((url, body) in requests) {
      mvc.perform(post(url).contentType(APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest)
        .andExpect(jsonPath("$.userMessage", containsString("eligibilityAnswers.youthSentence")))
        .andExpect(jsonPath("$.userMessage", containsString("accreditedProgramme and youthSentence")))
    }
    verifyNoInteractions(service, uploadService)
  }

  @ParameterizedTest
  @ValueSource(
    strings = [
      "",
      """"eligibilityAnswers":null""",
      """"eligibilityAnswers":{"accreditedProgramme":null,"deviceRestriction":false,"youthSentence":null}""",
      """"eligibilityAnswers":{"accreditedProgramme":true,"deviceRestriction":false,"youthSentence":false}""",
    ],
  )
  fun `setup accepts omitted null and valid paired answers`(answersProperty: String) {
    val setupUuid = UUID.randomUUID()
    whenever(service.startOffenderSetup(any())).thenReturn(
      OffenderSetupDto(
        uuid = setupUuid,
        practitionerId = "PRACT001",
        offenderUuid = UUID.randomUUID(),
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        startedAt = null,
        setupId = setupUuid,
      ),
    )

    mvc.perform(post("/v2/offender_setup").contentType(APPLICATION_JSON).content(setupRequest(answersProperty)))
      .andExpect(status().isOk)
    verify(service).startOffenderSetup(any())
  }

  private fun setupRequest(answersProperty: String): String = """
    {
      "setupUuid":"${UUID.randomUUID()}",
      "practitionerId":"PRACT001",
      "crn":"X123456",
      "firstCheckin":null,
      "checkinInterval":null,
      "mode":"AD_HOC",
      "contactPreference":"PHONE"
      ${if (answersProperty.isEmpty()) "" else ",$answersProperty"}
    }
  """.trimIndent()
}
