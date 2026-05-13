package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.MatchingOffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.OffenderIdVerifier

/**
 * Provides a stub implementation of the id verification service which always returns a match.
 */
@Profile("stubrekog | test")
@Configuration
class StubRekogConfig {
  @Bean
  fun rekognitionCompareFacesService(): OffenderIdVerifier {
    LOGGER.info("Using stub id verification service")
    return MatchingOffenderIdVerifier()
  }

  companion object {
    val LOGGER: Logger = LoggerFactory.getLogger(this::class.java)
  }
}
