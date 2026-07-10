package uk.gov.justice.digital.hmpps.esupervisionapi.datagen

/**
 * Put "template" DTOs here for use in tests
 */

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EligibilityChoice
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

val offenderTemplate = OffenderDto(
  uuid = UUID.randomUUID(),
  crn = "X000000",
  practitionerId = "BARRY.WHITE",
  status = OffenderStatus.VERIFIED,
  firstCheckin = java.time.LocalDate.now().minusDays(5),
  checkinInterval = CheckinInterval.WEEKLY,
  createdAt = Instant.now(),
  createdBy = "SYSTEM",
  updatedAt = Instant.now(),
  contactPreference = ContactPreference.EMAIL,
)

fun OffenderDto.toEntity() = Offender(
  uuid = uuid,
  crn = crn,
  practitionerId = practitionerId,
  status = status,
  firstCheckin = firstCheckin,
  checkinInterval = checkinInterval.duration,
  createdAt = createdAt,
  createdBy = createdBy,
  updatedAt = updatedAt,
  contactPreference = contactPreference,
)

fun Offender.asSetupDto(clock: Clock) = OffenderSetupDto(
  uuid = UUID.randomUUID(),
  practitionerId = this.practitionerId,
  offenderUuid = this.uuid,
  createdAt = clock.instant(),
  startedAt = null,
  eligibilityChoice = EligibilityChoice.SUPPLEMENT_F2F,
  rationale = "It's fine",
  setupId = UUID.randomUUID(),
)
