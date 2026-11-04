package uk.gov.justice.digital.hmpps.esupervisionapi.v2.checkin

import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderRepository
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
  private val offenderRepository: OffenderRepository,
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
    // Check-ins were stopped while the invite was sending. NDelius has the check-in as cancelled,
    // which a link event after it would overwrite. The database's status: under open-in-view a
    // cached entity can be from before the invite was sent.
    if (checkinRepository.lockAndGetStatus(checkinId) != CheckinStatus.CREATED.name) return@execute true
    val checkin = checkinRepository.findById(checkinId).orElseThrow()
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
   * GOV.UK Notify failed in a way that leaves unknown whether it sent the invite (a timeout, or a
   * 502/504 from its gateway), so the person may have the link. The check-in is opened as if it was
   * sent, so they can use it, but NDelius isn't told the link was sent. If they didn't get it, the
   * check-in is missed.
   * @return true if this changed the check-in
   */
  fun linkMaybeSent(checkinId: Long): Boolean = transactionTemplate.execute {
    val changed = checkinRepository.markLinkSent(checkinId, clock.instant()) > 0
    if (changed) LOGGER.warn("Checkin id={} opened without telling NDelius: unknown whether its invite was sent", checkinId)
    changed
  }!!

  /**
   * The invite couldn't be sent. The check-in is cancelled, not left to be missed, and the next
   * check-in is scheduled. Questions assigned to it stay with it.
   * @param reason why, for the NDelius contact
   * @return true if this changed the check-in
   */
  fun linkFailed(checkinId: Long, reason: String): Boolean = transactionTemplate.execute {
    // Serialises this with creating a check-in for the same offender (see CheckinPersistenceService.checkinCreation),
    // which must either see this check-in as created, or its invite as failed.
    offenderRepository.findByIdForUpdate(checkinRepository.findOffenderId(checkinId) ?: return@execute false)
    if (checkinRepository.markLinkFailed(checkinId, clock.instant()) == 0) return@execute false
    val checkin = checkinRepository.findById(checkinId).orElseThrow()
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

    // TODO ESUP-2223: agreed for now; to change or remove if probation integration take over the NDelius note text
    const val NO_ACTIVE_EVENT = "They have no active event"
    const val NO_CONTACT_DETAILS = "Their contact details could not be found"
    const val NO_ADDRESS = "They have no mobile number or email address for their contact preference"
    const val CHANNEL_DISABLED = "Sending check in links by their contact preference is switched off"
    const val NOTIFY_FAILED = "GOV.UK Notify could not send it"
  }
}
