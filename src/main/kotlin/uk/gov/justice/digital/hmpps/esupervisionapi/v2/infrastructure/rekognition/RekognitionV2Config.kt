package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.rekognition

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rekognition.RekognitionAsyncClient
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import java.time.Duration

/**
 * V2 Rekognition configuration - Production.
 * Creates the V2 OffenderIdVerifier (reuses existing eu-west-2 client) and
 * LivenessSessionService (separate eu-west-1 client, since Face Liveness is not available in eu-west-2).
 * Only active when NOT in test or stubrekog profiles.
 */
@Profile("!test & !stubrekog")
@Configuration
class RekognitionV2Config(
  @Value("\${rekognition.region}") private val facematchRegion: String,
  @Value("\${rekognition.liveness.region}") private val livenessRegion: String,
  @Value("\${rekognition.role_arn}") private val roleArn: String,
  @Value("\${rekognition.role_session_name}") private val roleSessionName: String,
  @Value("\${rekognition.max-concurrency}") private val maxConcurrency: Int,
  @Value("\${rekognition.read-timeout-seconds}") private val readTimeoutSeconds: Long,
) {

  @Bean
  fun rekognitionCredentialsProvider(): AwsCredentialsProvider {
    val stsClient = StsClient.builder().region(Region.of(facematchRegion))
      .credentialsProvider(DefaultCredentialsProvider.builder().build())
      .build()

    return StsAssumeRoleCredentialsProvider.builder()
      .stsClient(stsClient)
      .refreshRequest { assumeRoleRequestBuilder ->
        LOGGER.info("Assuming role {}", roleArn)
        assumeRoleRequestBuilder
          .roleArn(roleArn)
          .roleSessionName(roleSessionName)
      }.build()
  }

  /**
   * Rekognition client for face matching (CompareFaces).
   * CompareFaces reads its images from S3, and Rekognition can only read a bucket
   * in its own region, so `rekognition.region` MUST match the image bucket's region.
   * Kept distinct from the liveness client below, which runs in its own region.
   */
  @Bean(name = ["facematchRekognitionAsyncClient"])
  fun facematchRekognitionAsyncClient(rekognitionCredentialsProvider: AwsCredentialsProvider): RekognitionAsyncClient {
    LOGGER.info("Creating Rekognition async client for face matching in region {}", facematchRegion)
    return RekognitionAsyncClient.builder()
      .region(Region.of(facematchRegion))
      .credentialsProvider(rekognitionCredentialsProvider)
      .httpClientBuilder {
        NettyNioAsyncHttpClient.builder()
          .maxConcurrency(maxConcurrency)
          .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
          .build()
      }
      .build()
  }

  @Bean
  fun offenderIdVerifierV2(
    @Qualifier("facematchRekognitionAsyncClient") facematchRekognitionAsyncClient: RekognitionAsyncClient,
  ): OffenderIdVerifier {
    LOGGER.info("Creating V2 RekognitionCompareFacesService with real AWS Rekognition (async)")
    return RekognitionCompareFacesService(facematchRekognitionAsyncClient)
  }

  /** Separate Rekognition client for Face Liveness in eu-west-1 */
  @Bean(name = ["livenessRekognitionAsyncClient"])
  fun livenessRekognitionAsyncClient(rekognitionCredentialsProvider: AwsCredentialsProvider): RekognitionAsyncClient {
    LOGGER.info("Creating Rekognition async client for liveness in region {}", livenessRegion)
    return RekognitionAsyncClient.builder()
      .region(Region.of(livenessRegion))
      .credentialsProvider(rekognitionCredentialsProvider)
      .httpClientBuilder {
        NettyNioAsyncHttpClient.builder()
          .maxConcurrency(maxConcurrency)
          .readTimeout(Duration.ofSeconds(readTimeoutSeconds))
          .build()
      }
      .build()
  }

  @Bean
  fun livenessSessionServiceV2(
    @Qualifier("livenessRekognitionAsyncClient") livenessClient: RekognitionAsyncClient,
  ): LivenessSessionService {
    LOGGER.info("Creating V2 RekognitionLivenessService in region {}", livenessRegion)
    return RekognitionLivenessService(livenessClient)
  }

  @Bean
  fun livenessCredentialsService(): LivenessCredentialsProvider {
    LOGGER.info("Creating LivenessCredentialsService with STS in region {}", livenessRegion)
    val stsClient = StsClient.builder()
      .region(Region.of(livenessRegion))
      .credentialsProvider(DefaultCredentialsProvider.builder().build())
      .build()
    return LivenessCredentialsService(stsClient, roleArn, roleSessionName, livenessRegion)
  }

  companion object {
    private val LOGGER = LoggerFactory.getLogger(RekognitionV2Config::class.java)
  }
}
