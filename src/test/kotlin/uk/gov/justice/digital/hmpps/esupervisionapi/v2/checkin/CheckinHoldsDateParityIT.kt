package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import jakarta.persistence.EntityManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinLinkStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * Whether a check-in holds its date is decided in Kotlin (OffenderCheckin.holdsDate) and in SQL
 * (checkin_holds_date, used by the creation job and the custom questions reminder). They must
 * agree, or the date shown as the next check-in isn't the one that gets created.
 */
@Transactional
class CheckinHoldsDateParityIT : IntegrationTestBase() {

  @Autowired
  private lateinit var offenderRepository: OffenderRepository

  @Autowired
  private lateinit var checkinRepository: OffenderCheckinRepository

  @Autowired
  private lateinit var entityManager: EntityManager

  @Test
  fun `checkin_holds_date agrees with OffenderCheckin holdsDate for every combination`() {
    val scheduleSetAt = Instant.now().truncatedTo(ChronoUnit.MILLIS)
    var crn = 0
    val checkins = buildList {
      for (scheduleUpdatedAt in listOf(null, scheduleSetAt)) {
        for (status in CheckinStatus.entries) {
          for (linkStatus in listOf(null) + CheckinLinkStatus.entries) {
            for (cancelledBy in listOf(null, "PRACTITIONER")) {
              for (cancelledAt in listOf(null, scheduleSetAt.minusSeconds(60), scheduleSetAt.plusSeconds(60))) {
                // one offender each: an offender can have only one SCHEDULED check-in
                val offender = offenderRepository.save(
                  offenderTemplate.copy(uuid = UUID.randomUUID(), crn = "P%06d".format(++crn)).toEntity()
                    .also { it.scheduleUpdatedAt = scheduleUpdatedAt },
                )
                add(
                  checkinRepository.save(
                    OffenderCheckin(
                      uuid = UUID.randomUUID(),
                      offender = offender,
                      status = status,
                      dueDate = LocalDate.now(),
                      createdAt = Instant.now(),
                      createdBy = "SYSTEM",
                      linkStatus = linkStatus,
                    ).also {
                      it.cancelledBy = cancelledBy
                      it.cancelledAt = cancelledAt
                    },
                  ),
                )
              }
            }
          }
        }
      }
    }
    entityManager.flush()

    val inSql = entityManager.createNativeQuery(
      """
      select c.id, checkin_holds_date(c, o)
      from offender_checkin_v2 c join offender_v2 o on o.id = c.offender_id
      where c.id in (:ids)
      """,
    ).setParameter("ids", checkins.map { it.id })
      .resultList
      .associate { row -> (row as Array<*>).let { (it[0] as Number).toLong() to it[1] as Boolean } }

    val disagreements = checkins.filter { inSql[it.id] != it.holdsDate() }.map {
      "status=${it.status} link=${it.linkStatus} cancelledBy=${it.cancelledBy} cancelledAt=${it.cancelledAt} " +
        "scheduleUpdatedAt=${it.offender.scheduleUpdatedAt}: sql=${inSql[it.id]} kotlin=${it.holdsDate()}"
    }
    assertThat(inSql).hasSize(checkins.size)
    assertThat(disagreements).isEmpty()
    // the matrix covers both answers
    assertThat(checkins.map { it.holdsDate() }.toSet()).containsExactlyInAnyOrder(true, false)
  }
}
