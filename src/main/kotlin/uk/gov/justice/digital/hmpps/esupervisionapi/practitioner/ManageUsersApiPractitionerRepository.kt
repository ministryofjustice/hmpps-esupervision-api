package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import org.springframework.stereotype.Service

data class NewPractitionerInfo(
  val username: String,
  val userId: String,
  val email: String,
)

@Service
class ManageUsersApiPractitionerRepository(
  val manageUsersClient: RestManageUsersApiClient,
) {
  fun getByUsername(username: String): NewPractitionerInfo {
    val userInfo = manageUsersClient.getUserByUsername(username)!!;
    val email = manageUsersClient.getUserEmail(username);

    return NewPractitionerInfo(
      username = userInfo.username,
      userId = userInfo.userId,
      email = email,
    )
  }
}