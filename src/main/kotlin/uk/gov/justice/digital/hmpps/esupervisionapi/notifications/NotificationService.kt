package uk.gov.justice.digital.hmpps.esupervisionapi.notifications

data class OffenderInviteMessage(
  val firstName: String,
  val lastName: String,
  val invitePath: String,
) {
  fun personalisationData(hostedAt: String): Map<String, String> = mapOf(
    "firstName" to firstName,
    "lastName" to lastName,
    "inviteUrl" to "$hostedAt$invitePath",
  )
}

interface NotificationService {
  fun notifyOffenderInvite(method: NotificationMethod, invite: OffenderInviteMessage)
}
