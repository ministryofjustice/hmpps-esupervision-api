package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit.EventAuditV2Service
import java.time.Clock
import java.util.UUID

/**
 * Handles composite operations related to checkins. It's meant to be a convenience layer for the [CheckinV2Service]
 * and should not be used anywhere else.
 *
 * The way to use this service is to pass all required data in a state that's ready to be persisted - don't put any logic here.
 */
@Service
class CheckinPersistenceService(
  private val checkinRepository: OffenderCheckinV2Repository,
  private val eventAuditService: EventAuditV2Service,
  private val appEventPublisher: ApplicationEventPublisher,
  private val offenderEventLogRepository: OffenderEventLogV2Repository,
  private val clock: Clock,
) {

  @Transactional
  fun findCheckin(uuid: UUID): OffenderCheckinV2? = checkinRepository.findByUuid(uuid).orElse(null)

  @Transactional
  fun checkinSubmission(checkin: OffenderCheckinV2, event: CheckinSubmittedEvent) {
    checkinRepository.save(checkin)
    eventAuditService.recordCheckinSubmitted(checkin, event.checkin.personalDetails)

    appEventPublisher.publishEvent(event)
  }

  @Transactional
  fun checkinReview(checkin: OffenderCheckinV2, event: CheckinReviewedEvent, reviewInfo: CheckinReviewInfo) {
    require(event.checkin.reviewedBy != null)
    checkinRepository.save(checkin)
    eventAuditService.recordCheckinReviewed(checkin, event.checkin.personalDetails)
    offenderEventLogRepository.save(
      OffenderEventLogV2(
        comment = reviewInfo.comment,
        sensitive = checkin.sensitive,
        createdAt = clock.instant(),
        logEntryType = reviewInfo.logEntryType,
        practitioner = event.checkin.reviewedBy,
        uuid = UUID.randomUUID(),
        checkin = checkin.id,
        offender = checkin.offender,
      ),
    )

    appEventPublisher.publishEvent(event)
  }

  @Transactional
  fun checkinAnnotation(checkin: OffenderCheckinV2, event: PartialCheckinAnnotatedEvent, request: AnnotateCheckinV2Request) {
    checkinRepository.save(checkin)
    val logEntry = offenderEventLogRepository.save(
      OffenderEventLogV2(
        comment = request.notes,
        sensitive = checkin.sensitive,
        createdAt = clock.instant(),
        logEntryType = LogEntryType.OFFENDER_CHECKIN_ANNOTATED,
        practitioner = request.updatedBy,
        uuid = UUID.randomUUID(),
        offender = checkin.offender,
        checkin = checkin.id,
      ),
    )

    appEventPublisher.publishEvent(event.finalise(logEntry))
  }
}
