package uk.gov.justice.digital.hmpps.esupervisionapi.datagen

/**
 * Put "template" DTOs here for use in tests
 */

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.EligibilityChoice
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetupV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2Dto
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.ContactPreference
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import java.time.Clock
import java.time.Instant
import java.util.UUID

val offenderTemplate = OffenderV2Dto(
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

fun OffenderV2Dto.toEntity() = OffenderV2(
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

fun OffenderV2.asSetupDto(clock: Clock) = OffenderSetupV2Dto(
  UUID.randomUUID(),
  this.practitionerId,
  this.uuid,
  clock.instant(),
  null,
  EligibilityChoice.SUPPLEMENT_F2F,
  "It's fine",
  UUID.randomUUID()
)