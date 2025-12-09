package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Index
import jakarta.persistence.JoinColumn
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.persistence.V2BaseEntity
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * V2 Offender Entity (no PII, only CRN)
 */
@Entity
@Table(
  name = "offender_v2",
  indexes = [
    Index(name = "idx_offender_v2_crn", columnList = "crn", unique = false),
    Index(name = "idx_offender_v2_status", columnList = "status", unique = false),
    Index(name = "idx_offender_v2_practitioner", columnList = "practitioner_id", unique = false),
  ],
)
open class OffenderV2(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @Column(name = "crn", nullable = false, unique = true, length = 7)
  open var crn: String,

  @Column(name = "practitioner_id", nullable = false)
  open var practitionerId: ExternalUserId,

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  open var status: OffenderStatus = OffenderStatus.INITIAL,

  @Column(name = "first_checkin", nullable = false)
  open var firstCheckin: LocalDate,

  @Column(name = "checkin_interval", nullable = false)
  open var checkinInterval: Duration,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "created_by", nullable = false)
  open var createdBy: String,

  @Column(name = "updated_at", nullable = false)
  open var updatedAt: Instant,
) : V2BaseEntity() {
  fun dto(personalDetails: ContactDetails? = null): OffenderV2Dto = OffenderV2Dto(
    uuid = uuid,
    crn = crn,
    practitionerId = practitionerId,
    status = status,
    firstCheckin = firstCheckin,
    checkinInterval = CheckinInterval.fromDuration(checkinInterval),
    createdAt = createdAt,
    createdBy = createdBy,
    updatedAt = updatedAt,
    personalDetails = personalDetails,
  )
}

/**
 * V2 Offender Setup Entity
 */
@Entity
@Table(
  name = "offender_setup_v2",
  indexes = [
    Index(name = "idx_setup_v2_offender", columnList = "offender_id", unique = false),
    Index(name = "idx_setup_v2_practitioner", columnList = "practitioner_id", unique = false),
  ],
)
open class OffenderSetupV2(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @ManyToOne(cascade = [CascadeType.DETACH])
  @JoinColumn(name = "offender_id", referencedColumnName = "id", nullable = false)
  open var offender: OffenderV2,

  @Column(name = "practitioner_id", nullable = false)
  open var practitionerId: ExternalUserId,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "started_at", nullable = true)
  open var startedAt: Instant? = null,
) : V2BaseEntity()

/**
 * V2 Checkin Entity
 */
@Entity
@Table(
  name = "offender_checkin_v2",
  indexes = [
    Index(name = "idx_checkin_v2_offender", columnList = "offender_id", unique = false),
    Index(name = "idx_checkin_v2_status", columnList = "status", unique = false),
    Index(name = "idx_checkin_v2_due_date", columnList = "due_date", unique = false),
    Index(name = "idx_checkin_v2_created_by", columnList = "created_by", unique = false),
  ],
)
open class OffenderCheckinV2(
  @Column(unique = true, nullable = false)
  open var uuid: UUID,

  @ManyToOne(cascade = [CascadeType.DETACH])
  @JoinColumn(name = "offender_id", referencedColumnName = "id", nullable = false)
  open var offender: OffenderV2,

  @Column(nullable = false)
  @Enumerated(EnumType.STRING)
  open var status: CheckinV2Status,

  @Column(name = "due_date", nullable = false)
  open var dueDate: LocalDate,

  @Column(name = "survey_response", nullable = true)
  @JdbcTypeCode(SqlTypes.JSON)
  open var surveyResponse: Map<String, Any>? = null,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "created_by", nullable = false)
  open var createdBy: String,

  @Column(name = "submitted_at", nullable = true)
  open var submittedAt: Instant? = null,

  @Column(name = "review_started_at", nullable = true)
  open var reviewStartedAt: Instant? = null,

  @Column(name = "review_started_by", nullable = true)
  open var reviewStartedBy: String? = null,

  @Column(name = "reviewed_at", nullable = true)
  open var reviewedAt: Instant? = null,

  @Column(name = "reviewed_by", nullable = true)
  open var reviewedBy: String? = null,

  @Column(name = "checkin_started_at", nullable = true)
  open var checkinStartedAt: Instant? = null,

  @Column(name = "auto_id_check", nullable = true, length = 50)
  @Enumerated(EnumType.STRING)
  open var autoIdCheck: AutomatedIdVerificationResult? = null,

  @Column(name = "manual_id_check", nullable = true, length = 50)
  @Enumerated(EnumType.STRING)
  open var manualIdCheck: ManualIdVerificationResult? = null,
) : V2BaseEntity() {
  fun dto(personalDetails: ContactDetails? = null, videoUrl: java.net.URL? = null, snapshotUrl: java.net.URL? = null): CheckinV2Dto = CheckinV2Dto(
    uuid = uuid,
    crn = offender.crn,
    status = status,
    dueDate = dueDate,
    createdAt = createdAt,
    createdBy = createdBy,
    submittedAt = submittedAt,
    reviewStartedAt = reviewStartedAt,
    reviewStartedBy = reviewStartedBy,
    reviewedAt = reviewedAt,
    reviewedBy = reviewedBy,
    checkinStartedAt = checkinStartedAt,
    autoIdCheck = autoIdCheck,
    manualIdCheck = manualIdCheck,
    surveyResponse = surveyResponse,
    personalDetails = personalDetails,
    videoUrl = videoUrl,
    snapshotUrl = snapshotUrl,
  )
}

/**
 * Notification Configuration Entity
 */
@Entity
@Table(name = "notification_config_v2")
open class NotificationConfig(
  @Column(name = "event_type", nullable = false, unique = true, length = 100)
  open var eventType: String,

  @Column(name = "offender_sms_enabled", nullable = false)
  open var offenderSmsEnabled: Boolean = true,

  @Column(name = "offender_email_enabled", nullable = false)
  open var offenderEmailEnabled: Boolean = true,

  @Column(name = "offender_sms_template_id", nullable = true)
  open var offenderSmsTemplateId: String? = null,

  @Column(name = "offender_email_template_id", nullable = true)
  open var offenderEmailTemplateId: String? = null,

  @Column(name = "practitioner_sms_enabled", nullable = false)
  open var practitionerSmsEnabled: Boolean = false,

  @Column(name = "practitioner_email_enabled", nullable = false)
  open var practitionerEmailEnabled: Boolean = false,

  @Column(name = "practitioner_sms_template_id", nullable = true)
  open var practitionerSmsTemplateId: String? = null,

  @Column(name = "practitioner_email_template_id", nullable = true)
  open var practitionerEmailTemplateId: String? = null,

  @Column(name = "updated_at", nullable = false)
  open var updatedAt: Instant,

  @Column(name = "updated_by", nullable = false)
  open var updatedBy: String,
) : V2BaseEntity() {
  fun dto(): NotificationConfigDto = NotificationConfigDto(
    eventType = eventType,
    offenderSmsEnabled = offenderSmsEnabled,
    offenderEmailEnabled = offenderEmailEnabled,
    practitionerSmsEnabled = practitionerSmsEnabled,
    practitionerEmailEnabled = practitionerEmailEnabled,
    updatedAt = updatedAt,
    updatedBy = updatedBy,
  )
}

/**
 * V2 Generic Notification Entity
 */
@Entity
@Table(
  name = "generic_notification_v2",
  indexes = [
    Index(name = "idx_notification_v2_offender", columnList = "offender_id", unique = false),
    Index(name = "idx_notification_v2_event_type", columnList = "event_type", unique = false),
    Index(name = "idx_notification_v2_reference", columnList = "reference", unique = false),
  ],
)
open class GenericNotificationV2(
  @Column(name = "notification_id", unique = true, nullable = false)
  open var notificationId: UUID,

  @Column(name = "event_type", nullable = false, length = 100)
  open var eventType: String,

  @Column(name = "recipient_type", nullable = false, length = 50)
  open var recipientType: String, // OFFENDER or PRACTITIONER

  @Column(name = "channel", nullable = false, length = 50)
  open var channel: String, // SMS or EMAIL

  @ManyToOne(cascade = [CascadeType.DETACH])
  @JoinColumn(name = "offender_id", referencedColumnName = "id", nullable = true)
  open var offender: OffenderV2? = null,

  @Column(name = "practitioner_id", nullable = true)
  open var practitionerId: String? = null,

  @Column(name = "status", nullable = true, length = 50)
  open var status: String? = null,

  @Column(name = "reference", nullable = false)
  open var reference: String,

  @Column(name = "template_id", nullable = true)
  open var templateId: String? = null,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "sent_at", nullable = true)
  open var sentAt: Instant? = null,

  @Column(name = "updated_at", nullable = true)
  open var updatedAt: Instant? = null,

  @Column(name = "error_message", nullable = true, length = 1000)
  open var errorMessage: String? = null,
) : V2BaseEntity()

/**
 * V2 Event Audit Log Entity
 * Records all major events for reporting and analytics
 */
@Entity
@Table(
  name = "event_audit_log_v2",
  indexes = [
    Index(name = "idx_audit_v2_occurred_at", columnList = "occurred_at", unique = false),
    Index(name = "idx_audit_v2_event_type", columnList = "event_type", unique = false),
    Index(name = "idx_audit_v2_crn", columnList = "crn", unique = false),
    Index(name = "idx_audit_v2_practitioner", columnList = "practitioner_id", unique = false),
    Index(name = "idx_audit_v2_lau_code", columnList = "local_admin_unit_code", unique = false),
    Index(name = "idx_audit_v2_pdu_code", columnList = "pdu_code", unique = false),
    Index(name = "idx_audit_v2_provider_code", columnList = "provider_code", unique = false),
    Index(name = "idx_audit_v2_checkin_uuid", columnList = "checkin_uuid", unique = false),
    Index(name = "idx_audit_v2_checkin_status", columnList = "checkin_status", unique = false),
  ],
)
open class EventAuditV2(
  @Column(name = "event_type", nullable = false, length = 100)
  open var eventType: String,

  @Column(name = "occurred_at", nullable = false)
  open var occurredAt: Instant,

  @Column(name = "crn", nullable = false, length = 7)
  open var crn: String,

  @Column(name = "practitioner_id", nullable = true)
  open var practitionerId: String? = null,

  @Column(name = "local_admin_unit_code", nullable = true, length = 50)
  open var localAdminUnitCode: String? = null,

  @Column(name = "local_admin_unit_description", nullable = true)
  open var localAdminUnitDescription: String? = null,

  @Column(name = "pdu_code", nullable = true, length = 50)
  open var pduCode: String? = null,

  @Column(name = "pdu_description", nullable = true)
  open var pduDescription: String? = null,

  @Column(name = "provider_code", nullable = true, length = 50)
  open var providerCode: String? = null,

  @Column(name = "provider_description", nullable = true)
  open var providerDescription: String? = null,

  @Column(name = "checkin_uuid", nullable = true)
  open var checkinUuid: UUID? = null,

  @Column(name = "checkin_status", nullable = true, length = 50)
  open var checkinStatus: String? = null,

  @Column(name = "checkin_due_date", nullable = true)
  open var checkinDueDate: LocalDate? = null,

  @Column(name = "time_to_submit_hours", nullable = true, precision = 10, scale = 2)
  open var timeToSubmitHours: BigDecimal? = null,

  @Column(name = "time_to_review_hours", nullable = true, precision = 10, scale = 2)
  open var timeToReviewHours: BigDecimal? = null,

  @Column(name = "review_duration_hours", nullable = true, precision = 10, scale = 2)
  open var reviewDurationHours: BigDecimal? = null,

  @Column(name = "auto_id_check_result", nullable = true, length = 50)
  open var autoIdCheckResult: String? = null,

  @Column(name = "manual_id_check_result", nullable = true, length = 50)
  open var manualIdCheckResult: String? = null,

  @Column(name = "notes", nullable = true, columnDefinition = "TEXT")
  open var notes: String? = null,
) : V2BaseEntity()

/**
 * V2 Job Log Entity
 * Separate from V1 job_log for complete decoupling
 */
@Entity
@Table(
  name = "job_log_v2",
  indexes = [
    Index(name = "idx_job_log_v2_created_at", columnList = "created_at"),
    Index(name = "idx_job_log_v2_job_type", columnList = "job_type"),
  ],
)
open class JobLogV2(
  @Column(name = "job_type", nullable = false)
  open var jobType: String,

  @Column(name = "created_at", nullable = false)
  open var createdAt: Instant,

  @Column(name = "ended_at", nullable = true)
  open var endedAt: Instant? = null,
) : V2BaseEntity()
