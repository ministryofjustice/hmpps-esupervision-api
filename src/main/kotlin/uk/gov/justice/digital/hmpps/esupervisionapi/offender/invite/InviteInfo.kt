package uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInviteStatus
import java.time.LocalDate
import java.util.UUID

data class InviteInfo(
  val invitees: List<OffenderInfo> = emptyList(),
)

/**
 * Minimal information required to send invite to an offender
 */
data class OffenderInfo(
  val firstName: String,
  val lastName: String,
  val dateOfBirth: LocalDate,
  val email: String? = null,
  val phoneNumber: String? = null,
)

/**
 *
 */
data class OffenderInviteDto(
  val inviteUuid: UUID,
  val practitionerUuid: UUID,
  val status: OffenderInviteStatus,
  val photoKey: String? = null,
  val info: OffenderInfo,
)

/**
 * To be supplied by the offender to confirm the invite.
 */
data class OffenderInviteConfirmation(
  val inviteUuid: UUID,
  val photoContentType: String,
  val info: OffenderInfo,
)
