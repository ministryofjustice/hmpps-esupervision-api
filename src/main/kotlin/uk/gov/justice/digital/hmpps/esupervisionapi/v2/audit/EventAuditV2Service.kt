package uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit

import com.google.common.collect.Lists
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinReviewedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinSubmittedEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ICheckinEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.UUID

enum class OffenderAuditEventType {
  SETUP_COMPLETED,

  /** Practitioner-initiated deactivation via the deactivate endpoint. */
  OFFENDER_DEACTIVATED,
  OFFENDER_REACTIVATED,

  /** Automated deactivation by a scheduled job because the POP's NDelius contact is suspended (in reset). */
  OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED,

  /** Automated deactivation by a scheduled job because the POP has no active probation events in NDelius. */
  OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
}

enum class CheckinAuditEventType {
  CHECKIN_CREATED,
  CHECKIN_SUBMITTED,
  CHECKIN_REVIEWED,
  CHECKIN_EXPIRED,
  CHECKIN_REMINDER,
}

/**
 * V2 Event Audit Service
 * Records all major events for reporting and analytics
 * NO PII stored - only organizational data and metrics
 *
 * Callers must provide all required data - this service does not fetch external data
 */
@Service
class EventAuditV2Service(
  private val auditRepository: EventAuditV2Repository,
  private val clock: Clock,
  private val transactionTemplate: TransactionTemplate,
) {
  /**
   * Record setup completed event
   */
  fun recordSetupCompleted(offender: OffenderV2, contactDetails: ContactDetails?, setup: OffenderSetupV2Dto) =
    recordOffenderEvent(OffenderAuditEventType.SETUP_COMPLETED,
      offender = offender,
      contactDetails = contactDetails,
      notes = "Rationale for sign up: ${setup.rationale ?: "not provided"}\nEligibility: ${setup.eligibilityChoice?.name ?: "not provided"}\n",
      sensitive = false)

  fun recordCheckinEvent(eventType: CheckinAuditEventType, checkin: OffenderCheckinV2, event: ICheckinEvent) {
    if (event.checkin.personalDetails?.practitioner == null) {
      LOGGER.warn("Cannot record audit event {} for checkin {}: practitioner details not found", eventType.name, checkin.uuid)
      return
    }

    try {
      val payload = event.toAudit(eventType, checkin)
      transactionTemplate.execute { auditRepository.save(payload) }
      LOGGER.info("Recorded {} audit event for checkin {}", eventType.name, checkin.uuid)
    } catch (e: Exception) {
      LOGGER.error("Failed to record {} audit event: {}", eventType.name, PiiSanitizer.sanitizeException(e, event.checkin.crn, checkin.uuid))
    }
  }

  fun recordCheckinEvents(eventType: CheckinAuditEventType, checkins: Iterable<Pair<OffenderCheckinV2, ContactDetails?>>) {
    assert(eventType == CheckinAuditEventType.CHECKIN_EXPIRED || eventType == CheckinAuditEventType.CHECKIN_REMINDER) // those events are not using the event pattern yet
    val skipped = mutableListOf<UUID>()
    val audits = Lists.newArrayListWithCapacity<EventAuditV2>(checkins.count())
    for ((checkin, contactDetails) in checkins) {
      when (contactDetails?.practitioner) {
        null -> skipped.add(checkin.uuid)
        else -> audits.add(checkin.toAudit(eventType, contactDetails))
      }
    }
    try {
      if (audits.isNotEmpty()) {
        transactionTemplate.execute { auditRepository.saveAll(audits) }
      }
      LOGGER.info("Audit event of type {} - {}, {} skipped", eventType.name, if (audits.isEmpty()) "none to record" else "${audits.size} recorded", skipped.size)
    } catch (e: Exception) {
      val ids = checkins.map { "${it.first.offender.crn}:${it.first.uuid}" }.toTypedArray()
      LOGGER.error("Failed to record {} audit events: {}  {}", eventType.name, ids, PiiSanitizer.sanitizeException(e, null, null))
    } finally {
      if (skipped.isNotEmpty()) {
        LOGGER.warn("Skipped recording {} audit events for {} checkins - no contact details: {}", eventType.name, skipped.size, skipped)
      }
    }
  }

  /**
   * Record checkin created event
   */
  fun recordCheckinCreated(checkin: OffenderCheckinV2, event: ICheckinEvent) = recordCheckinEvent(CheckinAuditEventType.CHECKIN_CREATED, checkin, event)

  /**
   * Record checkin submitted event
   */
  fun recordCheckinSubmitted(checkin: OffenderCheckinV2, event: CheckinSubmittedEvent) = recordCheckinEvent(CheckinAuditEventType.CHECKIN_SUBMITTED, checkin, event)

  /**
   * Record checkin reviewed event
   */
  fun recordCheckinReviewed(checkin: OffenderCheckinV2, event: CheckinReviewedEvent) = recordCheckinEvent(CheckinAuditEventType.CHECKIN_REVIEWED, checkin, event)

  /**
   * Record checkin expired event
   */
  fun recordCheckinExpired(checkins: Iterable<Pair<OffenderCheckinV2, ContactDetails?>>) = recordCheckinEvents(CheckinAuditEventType.CHECKIN_EXPIRED, checkins)

  /**
   * Record checkin reminder event
   */
  fun recordCheckinReminded(checkins: Iterable<Pair<OffenderCheckinV2, ContactDetails?>>) = recordCheckinEvents(CheckinAuditEventType.CHECKIN_REMINDER, checkins)

  fun recordOffenderEvent(eventType: OffenderAuditEventType, offender: OffenderV2, contactDetails: ContactDetails?, notes: String?, sensitive: Boolean = false) {
    if (contactDetails == null) {
      LOGGER.warn("Cannot record audit event {} for CRN {}: contact details not found", eventType.name, offender.crn)
      return
    }
    if (contactDetails.practitioner == null) {
      // Still record the event (e.g. an automated deactivation of a POP in reset) - the practitioner
      // org-unit columns are nullable, so we keep the audit trail rather than dropping it entirely.
      LOGGER.warn("Recording audit event {} for CRN {} without practitioner organisation details", eventType.name, offender.crn)
    }

    try {
      transactionTemplate.execute { auditRepository.save(offender.toAudit(eventType, contactDetails, notes, sensitive)) }
      LOGGER.info("Recorded {} event audit for offender {}", eventType.name, offender.crn)
    } catch (e: Exception) {
      LOGGER.error("Failed to record {} audit event for CRN={}: {}", eventType.name, offender.crn, PiiSanitizer.sanitizeException(e, offender.crn))
    }
  }

  private fun buildAudit(
    eventType: String,
    crn: CRN,
    practitionerId: ExternalUserId,
    contactDetails: ContactDetails,
    checkin: OffenderCheckinV2? = null,
    timeToSubmitHours: BigDecimal? = null,
    timeToReviewHours: BigDecimal? = null,
    reviewDurationHours: BigDecimal? = null,
    autoIdCheckResult: String? = null,
    livenessResult: String? = null,
    manualIdCheckResult: String? = null,
    notes: String? = null,
    sensitive: Boolean = false,
  ): EventAuditV2 = EventAuditV2(
    eventType = eventType,
    occurredAt = clock.instant(),
    crn = crn,
    practitionerId = practitionerId,
    localAdminUnitCode = contactDetails.practitioner?.localAdminUnit?.code,
    localAdminUnitDescription = contactDetails.practitioner?.localAdminUnit?.description,
    pduCode = contactDetails.practitioner?.probationDeliveryUnit?.code,
    pduDescription = contactDetails.practitioner?.probationDeliveryUnit?.description,
    providerCode = contactDetails.practitioner?.provider?.code,
    providerDescription = contactDetails.practitioner?.provider?.description,
    checkinUuid = checkin?.uuid,
    checkinStatus = checkin?.status?.name,
    checkinDueDate = checkin?.dueDate,
    timeToSubmitHours = timeToSubmitHours,
    timeToReviewHours = timeToReviewHours,
    reviewDurationHours = reviewDurationHours,
    autoIdCheckResult = autoIdCheckResult,
    livenessResult = livenessResult,
    manualIdCheckResult = manualIdCheckResult,
    notes = notes,
    sensitive = sensitive,
  )

  private fun OffenderV2.toAudit(eventType: OffenderAuditEventType, contactDetails: ContactDetails, notes: String?, sensitive: Boolean = false): EventAuditV2 {
    val offender = this
    return when (eventType) {
      OffenderAuditEventType.SETUP_COMPLETED,
      OffenderAuditEventType.OFFENDER_REACTIVATED,
      OffenderAuditEventType.OFFENDER_DEACTIVATED,
      OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_CONTACT_SUSPENDED,
      OffenderAuditEventType.OFFENDER_AUTO_DEACTIVATED_NO_ACTIVE_EVENTS,
      -> buildAudit(
        eventType.name,
        offender.crn,
        offender.practitionerId,
        contactDetails,
        notes = notes,
        sensitive = sensitive,
      )
    }
  }

  private fun ICheckinEvent.toAudit(eventType: CheckinAuditEventType, checkin: OffenderCheckinV2): EventAuditV2 {
    val checkinDto = this.checkin
    require(checkinDto.personalDetails != null)
    return when (eventType) {
      CheckinAuditEventType.CHECKIN_CREATED -> buildAudit(
        eventType = eventType.name,
        crn = checkinDto.crn,
        practitionerId = this.practitionerId,
        contactDetails = checkinDto.personalDetails,
        checkin = checkin,
        notes = "Created by scheduled job",
      )

      CheckinAuditEventType.CHECKIN_SUBMITTED -> buildAudit(
        eventType = eventType.name,
        crn = checkinDto.crn,
        practitionerId = this.practitionerId,
        contactDetails = checkinDto.personalDetails,
        checkin = checkin,
        timeToSubmitHours = checkin.hoursToSubmit(),
        autoIdCheckResult = checkin.autoIdCheck?.name,
        livenessResult = checkin.livenessResult?.name,
      )

      CheckinAuditEventType.CHECKIN_REVIEWED -> {
        val notes = if (checkin.reviewedBy != null && checkin.reviewedBy != this.practitionerId) {
          "Reviewed by ${checkin.reviewedBy} (possibly covering for ${this.practitionerId})"
        } else {
          null
        }
        buildAudit(
          eventType = eventType.name,
          crn = checkinDto.crn,
          practitionerId = this.practitionerId,
          contactDetails = checkinDto.personalDetails,
          checkin = checkin,
          timeToReviewHours = checkin.hoursToReview(),
          reviewDurationHours = checkin.reviewDurationHours(),
          manualIdCheckResult = checkin.manualIdCheck?.name,
          notes = notes,
          sensitive = checkin.sensitive,
        )
      }

      CheckinAuditEventType.CHECKIN_EXPIRED -> buildAudit(
        eventType = eventType.name,
        crn = checkinDto.crn,
        practitionerId = this.practitionerId,
        contactDetails = checkinDto.personalDetails,
        checkin = checkin,
        notes = "Expired by scheduled job",
      )

      CheckinAuditEventType.CHECKIN_REMINDER -> buildAudit(
        eventType = eventType.name,
        crn = checkinDto.crn,
        practitionerId = this.practitionerId,
        contactDetails = checkinDto.personalDetails,
        checkin = checkin,
        notes = "Reminder sent by scheduled job",
      )
    }
  }

  private fun OffenderCheckinV2.toAudit(event: CheckinAuditEventType, contactDetails: ContactDetails): EventAuditV2 {
    val checkin = this
    return when (event) {
      CheckinAuditEventType.CHECKIN_EXPIRED -> buildAudit(
        event.name,
        checkin.offender.crn,
        checkin.offender.practitionerId,
        contactDetails,
        checkin,
        notes = "Expired by scheduled job",
      )

      CheckinAuditEventType.CHECKIN_REMINDER -> buildAudit(
        event.name,
        checkin.offender.crn,
        checkin.offender.practitionerId,
        contactDetails,
        checkin,
        notes = "Reminder sent by scheduled job",
      )
      else -> throw IllegalArgumentException("Unexpected event type: $event")
    }
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(EventAuditV2Service::class.java)

    private fun duration(duration: Duration): BigDecimal = BigDecimal.valueOf(duration.toMinutes()).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP)

    fun OffenderCheckinV2.hoursToSubmit(): BigDecimal? = if (checkinStartedAt != null && submittedAt != null) {
      duration(Duration.between(checkinStartedAt, submittedAt))
    } else {
      null
    }

    fun OffenderCheckinV2.hoursToReview(): BigDecimal? = if (this.reviewStartedAt != null && this.submittedAt != null) {
      duration(Duration.between(this.submittedAt, this.reviewStartedAt))
    } else {
      null
    }

    fun OffenderCheckinV2.reviewDurationHours(): BigDecimal? = if (this.reviewedAt != null && this.reviewStartedAt != null) {
      duration(Duration.between(this.reviewStartedAt, this.reviewedAt))
    } else {
      null
    }
  }
}
