package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Event
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

class OffenderSetupPersistenceServiceIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderSetupPersistenceService: OffenderSetupPersistenceService

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var jdbcTemplate: JdbcTemplate

  @Autowired
  private lateinit var clock: Clock

  @AfterEach
  fun tearDown() {
    jdbcTemplate.update("TRUNCATE TABLE generic_notification_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE event_audit_log_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE outbox_items RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_checkin_v2 RESTART IDENTITY CASCADE")
    jdbcTemplate.update("TRUNCATE TABLE offender_v2 RESTART IDENTITY CASCADE")
  }

  @Test
  fun `completing setup with a first check-in today creates the check-in`() {
    val offender = offenderRepository.save(
      offenderTemplate.copy(
        uuid = UUID.randomUUID(),
        crn = "X${(100000..999999).random()}",
        status = OffenderStatus.INITIAL,
        firstCheckin = clock.today(),
      ).toEntity(),
    )
    offender.status = OffenderStatus.VERIFIED

    val result = offenderSetupPersistenceService.completeOffenderSetupAndMaybeCreateCheckin(offender, contactDetails(offender.crn), true)

    assertThat(checkinRepository.findByUuid(result.checkin!!).get().status).isEqualTo(CheckinStatus.CREATED)
  }

  private fun contactDetails(crn: String) = ContactDetails(
    crn = crn,
    name = Name("John", "Smith"),
    email = "pop@example.com",
    practitioner = PractitionerDetails(name = Name("Jane", "Jones"), email = "practitioner@example.com"),
    events = listOf(Event(number = 1L, mainOffence = CodedDescription("OFF01", "Test"), sentence = null)),
    dateOfBirth = LocalDate.of(1980, 1, 1),
  )
}
