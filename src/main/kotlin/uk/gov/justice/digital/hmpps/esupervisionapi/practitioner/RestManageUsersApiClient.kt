package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono

data class User(
  val username: String,
  val active: Boolean,
  val name: String,
  val authSource: String,
  val userId: String,
  val uuid: String,
)

data class EmailResponse(
  val username: String,
  val email: String,
  val verified: Boolean,
)

@Service
class RestManageUsersApiClient(
  val manageUsersApiWebClient: WebClient,
)
{
  fun getUserByUsername(username: String): User? {
    LOGGER.info("Searching for username {}", username);

    return manageUsersApiWebClient.get()
      .uri("/users/{username}", username)
      .retrieve()
      .bodyToMono(User::class.java)
      .onErrorResume(WebClientResponseException.NotFound::class.java) {
        LOGGER.error("Unable to find user {}", username);
        Mono.empty()
      }
      .block()
  }

  fun getUserEmail(username: String): String {
    LOGGER.info("Searching for email for {}", username);

    val emailInfo = manageUsersApiWebClient.get()
      .uri("/users/{username}/email", username)
      .retrieve()
      .bodyToMono(EmailResponse::class.java)
      .block();

    return emailInfo!!.email
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}