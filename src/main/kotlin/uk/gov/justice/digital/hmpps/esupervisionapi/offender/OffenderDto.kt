package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class OffenderDto(
  val uuid: UUID,
  val firstName: String,
  val lastName: String,
  val dateOfBirth: LocalDate?,
  val status: OffenderStatus = OffenderStatus.INITIAL,
  val createdAt: Instant,
  // val updatedAt: Instant,
  val email: String? = null,
  val phoneNumber: String? = null,
  // not every context requires the photo URL, so we only include when needed
  val photoUrl: URL?,
)

data class OffenderSetupDto(
  val uuid: UUID,
  val practitioner: UUID,
  val offender: UUID,
  val createdAt: Instant,
)
