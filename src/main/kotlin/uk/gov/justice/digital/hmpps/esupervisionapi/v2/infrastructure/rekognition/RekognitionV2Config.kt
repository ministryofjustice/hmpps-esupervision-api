package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rekognition.RekognitionAsyncClient
import java.time.Duration

/**
 * V2 Rekognition configuration - Production.
 * Creates the V2 OffenderIdVerifier bean using real AWS Rekognition service.
 * Only active when NOT in test or stubrekog profiles.
 */
@Profile("!test & !stubrekog")
@Configuration
class RekognitionV2Config(
  @Value("\${rekognition.region}") private val region: String,
  @Value("\${rekognition.max-concurrency}") private val maxConcurrency: Int,
  @Value("\${rekognition.read-timeout-seconds}") private val readTimeoutSeconds: Long,
) {

  @Bean
  fun offenderIdVerifierV2(rekognitionCredentialsProvider: AwsCredentialsProvider): OffenderIdVerifier {
    LOGGER.info("Creating V2 RekognitionCompareFacesService with real AWS Rekognition (async)")

    val asyncClient = RekognitionAsyncClient.builder()
      .region(Region.of(region))
      .credentialsProvider(rekognitionCredentialsProvider)
      .httpClientBuilder {
        NettyNioAsyncHttpClient.builder()
          .maxConcurrency(maxConcurrency)
          .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
          .build()
      }
      .build()

    return RekognitionCompareFacesService(asyncClient)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(RekognitionV2Config::class.java)
  }
}
