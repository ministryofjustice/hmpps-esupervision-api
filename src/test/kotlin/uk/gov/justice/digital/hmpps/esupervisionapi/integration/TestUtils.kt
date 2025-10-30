package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationResults
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

fun createOffenderInfo(
  name: String = "Bob Offerman",
  dateOfBirth: LocalDate = LocalDate.of(1970, 1, 1),
  practitionerId: String = PRACTITIONER_ALICE.externalUserId(),
  firstCheckinDate: LocalDate,
  checkinInterval: CheckinInterval = CheckinInterval.WEEKLY,
) = OffenderInfo(
  UUID.randomUUID(),
  practitionerId,
  name.split(" ").first(),
  name.split(" ").last(),
  UUID.randomUUID().toString().substring(0, 7),
  dateOfBirth,
  "${name.split(" ").first()}@example.com",
  firstCheckinDate = firstCheckinDate,
  checkinInterval = checkinInterval,
)

/**
 * Creates an example practitioner instance. `username` should be unique.
 */
fun createNewPractitioner(username: ExternalUserId): Practitioner {
  val name = username.split(".").joinToString(" ")
  return Practitioner(
    username = username,
    name = name,
    email = "${username.lowercase()}@example.com",
  )
}

val PRACTITIONER_ALICE = createNewPractitioner("Alice.Smith")
val PRACTITIONER_BOB = createNewPractitioner("Bob.Jones")
val PRACTITIONER_DAVE = createNewPractitioner("Dave.Allen")

fun Offender.Companion.create(
  name: String,
  crn: String,
  dateOfBirth: LocalDate = LocalDate.of(1970, 1, 1),
  firstCheckinDate: LocalDate,
  checkinInterval: CheckinInterval = CheckinInterval.WEEKLY,
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
    crn = crn,
    dateOfBirth = dateOfBirth,
    status = status,
    createdAt = createdAt,
    updatedAt = updatedAt,
    email = email ?: "${lastName.lowercase()}@example.com",
    phoneNumber = phoneNumber,
    firstCheckin = firstCheckinDate,
    checkinInterval = checkinInterval.duration,
    practitioner = practitioner.externalUserId(),
  )
}

fun OffenderCheckin.Companion.create(
  uuid: UUID = UUID.randomUUID(),
  offender: Offender,
  submittedAt: Instant? = null,
  createdAt: Instant = Instant.now(),
  reviewedBy: ExternalUserId? = null,
  createdBy: ExternalUserId,
  status: CheckinStatus = CheckinStatus.CREATED,
  surveyResponse: Map<String, Object>? = null,
  notifications: NotificationResults? = null,
  dueDate: LocalDate = LocalDate.now(),
  autoIdCheck: AutomatedIdVerificationResult? = null,
  manualIdCheck: ManualIdVerificationResult? = null,
): OffenderCheckin = OffenderCheckin(
  uuid = uuid,
  offender = offender,
  submittedAt = submittedAt,
  createdAt = createdAt,
  reviewedBy = reviewedBy,
  reviewedAt = null,
  createdBy = createdBy,
  status = status,
  surveyResponse = surveyResponse,
  dueDate = dueDate,
  autoIdCheck = autoIdCheck,
  manualIdCheck = manualIdCheck,
)

/**
 * Returns a range of dates [startDate endDate]
 */
fun datesBetweenExclusive(startDate: LocalDate, endDate: LocalDate): List<LocalDate> {
  var d = startDate.plusDays(1)
  val dates = mutableListOf<LocalDate>()

  while (d.isBefore(endDate)) {
    dates.add(d)
    d = d.plusDays(1)
  }

  return dates
}
