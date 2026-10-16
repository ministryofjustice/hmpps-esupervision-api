package uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OutboxItemRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class OffenderResourceIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var questionListAssignmentRepository: QuestionListAssignmentRepository

  @Autowired
  private lateinit var outboxItemRepository: OutboxItemRepository

  @Autowired
  private lateinit var clock: Clock

  @AfterEach
  fun cleanUp() {
    questionListAssignmentRepository.deleteAll()
    checkinRepository.deleteAll()
    offenderRepository.deleteAll()
    offenderRepository.flush()
    outboxItemRepository.deleteAll()
  }

  // Stopping check-ins cancels today's check-in but keeps the row, and restarting them the same day
  // creates another, so one day can hold several rows. The single-row lookup update_details used to
  // make only fails against a real database ("Query did not return a unique result"), so this runs
  // the request against one.
  @Test
  fun `update_details succeeds when a person has several check-in rows for today`() {
    val today = LocalDate.now(clock)
    val offender = offenderRepository.save(
      offenderTemplate.copy(uuid = UUID.randomUUID(), crn = "X000123", status = OffenderStatus.VERIFIED, firstCheckin = today.minusWeeks(2)).toEntity(),
    )
    listOf(CheckinStatus.CANCELLED, CheckinStatus.CREATED).forEach { status ->
      checkinRepository.save(
        OffenderCheckin(uuid = UUID.randomUUID(), offender = offender, status = status, dueDate = today, createdAt = Instant.now(), createdBy = "PRACT001"),
      )
    }

    webTestClient.post()
      .uri("/v2/offenders/${offender.uuid}/update_details")
      .headers(setAuthorisation(roles = listOf("ROLE_ESUPERVISION__ESUPERVISION_UI")))
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue("""{"checkinSchedule":{"requestedBy":"PRACT001","firstCheckin":"$today","checkinInterval":"FOUR_WEEKS","mode":"SCHEDULED"}}""")
      .exchange()
      .expectStatus().isOk
      .expectBody()
      .jsonPath("$.checkinInterval").isEqualTo("FOUR_WEEKS")
      .jsonPath("$.firstCheckin").isEqualTo(today.toString())

    // The open check-in already occupies today, so the update does not add a third row.
    assertEquals(2, checkinRepository.findAllByOffenderAndDueDate(offender, today).size)
  }
}
