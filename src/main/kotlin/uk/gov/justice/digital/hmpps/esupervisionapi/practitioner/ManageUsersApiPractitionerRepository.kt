package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import org.springframework.stereotype.Service

@Service
class ManageUsersApiPractitionerRepository(
  val manageUsersClient: RestManageUsersApiClient,
) : NewPractitionerRepository {
  override fun findById(id: ExternalUserId): NewPractitioner? {
    // TODO: handle missing
    val userInfo = manageUsersClient.getUserByUsername(id);
    if (userInfo == null) {
      return null
    }

    val email = manageUsersClient.getUserEmail(id);

    return NewPractitioner(
      username = userInfo.username,
      userId = userInfo.userId,
      email = email,
    )
  }
}