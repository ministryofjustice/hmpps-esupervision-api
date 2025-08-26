package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

class ManageUsersApiPractitionerRepository(
  val manageUsersClient: RestManageUsersApiClient,
) : PractitionerRepository {
  override fun findById(id: ExternalUserId): Practitioner? {
    // TODO: handle missing
    val userInfo = manageUsersClient.getUserByUsername(id)
    if (userInfo == null) {
      return null
    }

    val email = manageUsersClient.getUserEmail(id)

    return Practitioner(
      username = userInfo.username,
      name = userInfo.name,
      email = email,
    )
  }
}
