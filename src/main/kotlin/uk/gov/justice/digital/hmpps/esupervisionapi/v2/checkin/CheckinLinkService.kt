package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.QuestionListAssignmentRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventNotes
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.deliusevents.CheckinDeliusEventService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock

/**
 * ESUP-2223: records whether a check-in's invite (the link) was sent, and tells NDelius.
 *
 * Only check-ins whose link status is SENDING are affected, which is only those created with the
 * feature on; for any other check-in these are no-ops.
 */
@Service
class CheckinLinkService(
  private val checkinRepository: OffenderCheckinRepository,
  private val questionListAssignmentRepository: QuestionListAssignmentRepository,
  private val checkinDeliusEventService: CheckinDeliusEventService,
  private val upcomingCheckinService: UpcomingCheckinService,
  private val transactionTemplate: TransactionTemplate,
  private val clock: Clock,
) {

  /**
   * GOV.UK Notify accepted the invite: the person can now submit the check-in.
   * @param channel how it was sent, as recorded on the notification (SMS or EMAIL)
   * @return true if this changed the check-in
   */
  fun linkSent(checkinId: Long, channel: String): Boolean = transactionTemplate.execute {
    val now = clock.instant()
    if (checkinRepository.markLinkSent(checkinId, now) == 0) return@execute false
    val checkin = checkinRepository.findById(checkinId).orElseThrow()
    // Check-ins were stopped while the invite was sending. NDelius has the check-in as cancelled,
    // which a link event after it would overwrite.
    if (checkin.status != CheckinStatus.CREATED) return@execute true
    checkinDeliusEventService.record(
      DomainEventType.V2_CHECKIN_LINK_DELIVERED,
      checkin,
      CheckinDeliusEventNotes.linkDelivered(channelName(channel), now),
      eventNumber = null,
    )
    LOGGER.info("Checkin {} link sent by {}", checkin.uuid, channel)
    true
  }!!

  /**
   * The invite couldn't be sent. The check-in is cancelled, not left to be missed; questions
   * assigned to it carry over; and the next check-in is scheduled.
   * @param reason why, for the NDelius contact
   * @return true if this changed the check-in
   */
  fun linkFailed(checkinId: Long, reason: String): Boolean = transactionTemplate.execute {
    if (checkinRepository.markLinkFailed(checkinId, clock.instant()) == 0) return@execute false
    val checkin = checkinRepository.findById(checkinId).orElseThrow()
    questionListAssignmentRepository.releaseCheckinAssignment(checkin.offender.id, checkinId)
    checkinDeliusEventService.record(
      DomainEventType.V2_CHECKIN_LINK_NOT_DELIVERED,
      checkin,
      CheckinDeliusEventNotes.linkNotDelivered(reason),
      eventNumber = null,
    )
    upcomingCheckinService.scheduleNext(checkin.offender.id)
    LOGGER.warn("Checkin {} cancelled, link not sent: {}", checkin.uuid, reason)
    true
  }!!

  private fun channelName(channel: String) = when (channel) {
    "SMS" -> "text message"
    "EMAIL" -> "email"
    else -> channel
  }

  companion object {
    private val LOGGER = logger<CheckinLinkService>()

    // TODO ESUP-2223: wording to be agreed with product
    const val NO_ACTIVE_EVENT = "They have no active event"
    const val NO_CONTACT_DETAILS = "Their contact details could not be found"
    const val NO_ADDRESS = "They have no mobile number or email address for their contact preference"
    const val NOTIFY_FAILED = "GOV.UK Notify could not send it"
  }
}
