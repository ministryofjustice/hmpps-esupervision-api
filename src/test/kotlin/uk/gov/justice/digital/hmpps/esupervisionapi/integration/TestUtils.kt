package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.NotificationResults
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.UUID

fun createOffenderInfo(
  name: String = "Bob Offerman",
  dateOfBirth: LocalDate = LocalDate.of(1970, 1, 1),
  practitionerId: String = "alice",
  firstCheckinDate: LocalDate,
  checkinInterval: CheckinInterval = CheckinInterval.WEEKLY,
) = OffenderInfo(
  UUID.randomUUID(),
  practitionerId,
  name.split(" ").first(),
  name.split(" ").last(),
  dateOfBirth,
  "${name.split(" ").first()}@example.com",
  firstCheckinDate = firstCheckinDate,
  checkinInterval = checkinInterval,
)

/**
 * Creates an example practitioner instance. `name` should be unique.
 */
fun Practitioner.Companion.create(name: String): Practitioner = Practitioner(
  name.lowercase(),
  name,
  "Practitioner",
  "${name.lowercase()}@example.com",
  roles = listOf(),
)

fun Offender.Companion.create(
  name: String,
  dateOfBirth: LocalDate = LocalDate.of(1970, 1, 1),
  firstCheckinDate: ZonedDateTime,
  checkinInterval: CheckinInterval = CheckinInterval.WEEKLY,
  zoneId: ZoneId = ZoneId.of("Europe/London"),
  status: OffenderStatus = OffenderStatus.INITIAL,
  email: String? = null,
  phoneNumber: String? = null,
  createdAt: Instant = Instant.now(),
  updatedAt: Instant = Instant.now(),
  practitioner: Practitioner,
): Offender {
  val firstName = name.split(" ").first()
  val lastName = name.split(" ").last()

  return Offender(
    uuid = UUID.randomUUID(),
    firstName = firstName,
    lastName = lastName,
    dateOfBirth = dateOfBirth,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    email = email ?: "${lastName.lowercase()}@example.com",
    phoneNumber = phoneNumber,
    firstCheckin = firstCheckinDate,
    checkinInterval = checkinInterval.duration,
    practitioner = practitioner,
  )
}

fun OffenderCheckin.Companion.create(
  uuid: UUID = UUID.randomUUID(),
  offender: Offender,
  submittedAt: Instant? = null,
  createdAt: Instant = Instant.now(),
  reviewedBy: Practitioner? = null,
  createdBy: Practitioner,
  status: CheckinStatus = CheckinStatus.CREATED,
  surveyResponse: Map<String, Object>? = null,
  notifications: NotificationResults? = null,
  dueDate: ZonedDateTime = ZonedDateTime.now(ZoneId.of("UTC")),
  autoIdCheck: AutomatedIdVerificationResult? = null,
  manualIdCheck: ManualIdVerificationResult? = null,
): OffenderCheckin = OffenderCheckin(
  uuid = uuid,
  offender = offender,
  submittedAt = submittedAt,
  createdAt = createdAt,
  reviewedBy = reviewedBy,
  createdBy = createdBy,
  status = status,
  surveyResponse = surveyResponse,
  dueDate = dueDate,
  autoIdCheck = autoIdCheck,
  manualIdCheck = manualIdCheck,
)
