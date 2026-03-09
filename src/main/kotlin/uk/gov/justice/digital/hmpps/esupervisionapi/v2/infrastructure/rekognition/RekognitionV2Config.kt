package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rekognition.RekognitionAsyncClient
import software.amazon.awssdk.services.sts.StsClient

/**
 * V2 Rekognition configuration - Production.
 * Creates the V2 OffenderIdVerifier and LivenessSessionService beans using real AWS Rekognition.
 * Reuses the RekognitionAsyncClient bean defined in RekogConfig.
 * Only active when NOT in test or stubrekog profiles.
 */
@Profile("!test & !stubrekog")
@Configuration
class RekognitionV2Config(
  @Value("\${rekognition.region}") private val region: String,
  @Value("\${rekognition.role_arn}") private val roleArn: String,
  @Value("\${rekognition.role_session_name}") private val roleSessionName: String,
) {

  @Bean
  fun offenderIdVerifierV2(rekognitionAsyncClient: RekognitionAsyncClient): OffenderIdVerifier {
    LOGGER.info("Creating V2 RekognitionCompareFacesService with real AWS Rekognition (async)")
    return RekognitionCompareFacesService(rekognitionAsyncClient)
  }

  @Bean
  fun livenessSessionServiceV2(rekognitionAsyncClient: RekognitionAsyncClient): LivenessSessionService {
    LOGGER.info("Creating V2 RekognitionLivenessService with real AWS Rekognition")
    return RekognitionLivenessService(rekognitionAsyncClient)
  }

  @Bean
  fun livenessCredentialsService(rekognitionCredentialsProvider: AwsCredentialsProvider): LivenessCredentialsProvider {
    LOGGER.info("Creating LivenessCredentialsService with STS role assumption")
    val stsClient = StsClient.builder()
      .region(Region.of(region))
      .credentialsProvider(rekognitionCredentialsProvider)
      .build()
    return LivenessCredentialsService(stsClient, roleArn, roleSessionName, region)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(RekognitionV2Config::class.java)
  }
}
