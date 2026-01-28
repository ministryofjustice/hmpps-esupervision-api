package uk.gov.justice.digital.hmpps.esupervisionapi.v2.audit

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EventAuditV2Repository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security.PiiSanitizer
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration

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
  fun recordSetupCompleted(offender: OffenderV2, contactDetails: ContactDetails?) {
    try {
      if (contactDetails?.practitioner == null) {
        LOGGER.warn("Cannot record audit for setup {}: practitioner details not found", offender.crn)
        return
      }

      val audit = buildAudit("SETUP_COMPLETED", offender, contactDetails)
      transactionTemplate.execute { auditRepository.save(audit) }
      LOGGER.info("Recorded SETUP_COMPLETED audit event for crn={}", offender.crn)
    } catch (e: Exception) {
      LOGGER.error("Failed to record setup audit: {}", PiiSanitizer.sanitizeException(e, offender.crn))
    }
  }

  /**
   * Record checkin created event
   */
  fun recordCheckinCreated(checkin: OffenderCheckinV2, contactDetails: ContactDetails?) {
    try {
      if (contactDetails?.practitioner == null) {
        LOGGER.warn("Cannot record audit for checkin {}: practitioner details not found", checkin.uuid)
        return
      }

      val audit = buildAudit("CHECKIN_CREATED", checkin.offender, contactDetails, checkin, notes = "Created by scheduled job")
      transactionTemplate.execute { auditRepository.save(audit) }
      LOGGER.info("Recorded CHECKIN_CREATED audit event for checkin={}", checkin.uuid)
    } catch (e: Exception) {
      LOGGER.error("Failed to record checkin created audit: {}", PiiSanitizer.sanitizeException(e, checkin.offender.crn, checkin.uuid))
    }
  }

  /**
   * Record checkin submitted event
   */
  fun recordCheckinSubmitted(checkin: OffenderCheckinV2, contactDetails: ContactDetails?) {
    try {
      if (contactDetails?.practitioner == null) {
        LOGGER.warn("Cannot record audit for checkin {}: practitioner details not found", checkin.uuid)
        return
      }

      val timeToSubmitHours = if (checkin.submittedAt != null) {
        val duration = Duration.between(checkin.createdAt, checkin.submittedAt)
        BigDecimal.valueOf(duration.toMinutes()).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP)
      } else {
        null
      }

      val audit = buildAudit(
        "CHECKIN_SUBMITTED",
        checkin.offender,
        contactDetails,
        checkin,
        timeToSubmitHours = timeToSubmitHours,
        autoIdCheckResult = checkin.autoIdCheck?.name,
      )
      transactionTemplate.execute { auditRepository.save(audit) }
      LOGGER.info("Recorded CHECKIN_SUBMITTED audit event for checkin={}", checkin.uuid)
    } catch (e: Exception) {
      LOGGER.error("Failed to record checkin submitted audit: {}", PiiSanitizer.sanitizeException(e, checkin.offender.crn, checkin.uuid))
    }
  }

  /**
   * Record checkin reviewed event
   */
  fun recordCheckinReviewed(checkin: OffenderCheckinV2, contactDetails: ContactDetails?) {
    try {
      if (contactDetails?.practitioner == null) {
        LOGGER.warn("Cannot record audit for checkin {}: practitioner details not found", checkin.uuid)
        return
      }

      val timeToReviewHours = if (checkin.reviewStartedAt != null && checkin.submittedAt != null) {
        val duration = Duration.between(checkin.submittedAt, checkin.reviewStartedAt)
        BigDecimal.valueOf(duration.toMinutes()).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP)
      } else {
        null
      }

      val reviewDurationHours = if (checkin.reviewedAt != null && checkin.reviewStartedAt != null) {
        val duration = Duration.between(checkin.reviewStartedAt, checkin.reviewedAt)
        BigDecimal.valueOf(duration.toMinutes()).divide(BigDecimal.valueOf(60), 2, java.math.RoundingMode.HALF_UP)
      } else {
        null
      }

      val notes = if (checkin.reviewedBy != null && checkin.reviewedBy != checkin.offender.practitionerId) {
        "Reviewed by ${checkin.reviewedBy} (possibly covering for ${checkin.offender.practitionerId})"
      } else {
        null
      }

      val audit = buildAudit(
        "CHECKIN_REVIEWED",
        checkin.offender,
        contactDetails,
        checkin,
        timeToReviewHours = timeToReviewHours,
        reviewDurationHours = reviewDurationHours,
        manualIdCheckResult = checkin.manualIdCheck?.name,
        notes = notes,
      )

      transactionTemplate.execute { auditRepository.save(audit) }
      LOGGER.info("Recorded CHECKIN_REVIEWED audit event for checkin={}", checkin.uuid)
    } catch (e: Exception) {
      LOGGER.error("Failed to record checkin reviewed audit: {}", PiiSanitizer.sanitizeException(e, checkin.offender.crn, checkin.uuid))
    }
  }

  /**
   * Record checkin expired event
   */
  fun recordCheckinExpired(checkins: Iterable<Pair<OffenderCheckinV2, ContactDetails?>>) {
    try {
      val audits = mutableListOf<EventAuditV2>()
      for ((checkin, contactDetails) in checkins) {
        if (contactDetails?.practitioner == null) {
          LOGGER.warn("Cannot record audit for checkin {}: practitioner details not found", checkin.uuid)
          continue
        }

        audits.add(
          buildAudit(
            "CHECKIN_EXPIRED",
            checkin.offender,
            contactDetails,
            checkin,
            notes = "Expired by scheduled job",
          ),
        )
      }

      if (audits.isNotEmpty()) {
        transactionTemplate.execute { auditRepository.saveAll(audits) }
        LOGGER.info("Recorded CHECKIN_EXPIRED audit events for {} checkins", audits.size)
      } else {
        LOGGER.info("No CHECKIN_EXPIRED audits to record")
      }
    } catch (e: Exception) {
      val ids = checkins.map { "${it.first.offender.crn}:${it.first.uuid}" }.toTypedArray()
      LOGGER.error("Failed to record checkin expired audits: {}  {}", ids, PiiSanitizer.sanitizeException(e, null, null))
    }
  }

  /**
   * Record checkin reminder event
   */
  fun recordCheckinReminded(checkins: Iterable<Pair<OffenderCheckinV2, ContactDetails?>>) {
    try {
      val audits = mutableListOf<EventAuditV2>()
      for ((checkin, contactDetails) in checkins) {
        if (contactDetails == null) {
          LOGGER.warn("Cannot record audit for checkin {}: contact details not found", checkin.uuid)
          continue
        }

        audits.add(
          buildAudit(
            "CHECKIN_REMINDER",
            checkin.offender,
            contactDetails,
            checkin,
            notes = "Reminder sent by scheduled job",
          ),
        )
      }

      if (audits.isNotEmpty()) {
        transactionTemplate.execute { auditRepository.saveAll(audits) }
        LOGGER.info("Recorded CHECKIN_REMINDER audit events for {} checkins", audits.size)
      } else {
        LOGGER.info("No CHECKIN_REMINDER audits to record")
      }
    } catch (e: Exception) {
      val ids = checkins.map { "${it.first.offender.crn}:${it.first.uuid}" }.toTypedArray()
      LOGGER.error("Failed to record checkin reminder audits: {}  {}", ids, PiiSanitizer.sanitizeException(e, null, null))
    }
  }

  private fun buildAudit(
    eventType: String,
    offender: OffenderV2,
    contactDetails: ContactDetails,
    checkin: OffenderCheckinV2? = null,
    timeToSubmitHours: BigDecimal? = null,
    timeToReviewHours: BigDecimal? = null,
    reviewDurationHours: BigDecimal? = null,
    autoIdCheckResult: String? = null,
    manualIdCheckResult: String? = null,
    notes: String? = null,
  ): EventAuditV2 = EventAuditV2(
    eventType = eventType,
    occurredAt = clock.instant(),
    crn = offender.crn,
    practitionerId = offender.practitionerId,
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
    manualIdCheckResult = manualIdCheckResult,
    notes = notes,
  )

  companion object {
    private val LOGGER = LoggerFactory.getLogger(EventAuditV2Service::class.java)
  }
}
