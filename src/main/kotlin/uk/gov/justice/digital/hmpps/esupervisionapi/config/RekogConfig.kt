package uk.gov.justice.digital.hmpps.esupervisionapi.config

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
import software.amazon.awssdk.services.rekognition.RekognitionClient
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.RekognitionCompareFacesService
import java.time.Duration

/***
 * Defines the beans required for id verification (using AWS rekognition in deployments). This is the default
 * configuration which is applied unless either the 'test' or 'stubrekog' profiles are active. In that case
 * the @see StubRekogConfig is used.
 */
@Profile("!test & !stubrekog & !local-aws")
@Configuration
class RekogConfig(
  @Value("\${rekognition.region}") val region: String,
  @Value("\${rekognition.role_arn}") val roleArn: String,
  @Value("\${rekognition.role_session_name}") val roleSessionName: String,
  @Value("\${rekognition.max-concurrency}") val rekognitionMaxConcurrency: Int,
  @Value("\${rekognition.read-timeout-seconds}") val rekognitionReadTimeoutSeconds: Long,
) {

  @Bean
  fun rekognitionCredentialsProvider(): AwsCredentialsProvider {
    val stsClient = StsClient.builder().region(Region.of(region))
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

  @Bean
  fun rekognitionCompareFacesService(rekognitionCredentialsProvider: AwsCredentialsProvider, @Qualifier("rekognitionAsyncClient") asyncClient: RekognitionAsyncClient): OffenderIdVerifier {
    val client = RekognitionClient.builder()
      .region(Region.of(region))
      .credentialsProvider(rekognitionCredentialsProvider)
      .build()

    return RekognitionCompareFacesService(client, asyncClient)
  }

  @Bean
  fun rekognitionAsyncClient(rekognitionCredentialsProvider: AwsCredentialsProvider): RekognitionAsyncClient = RekognitionAsyncClient.builder()
    .region(Region.of(region))
    .credentialsProvider(rekognitionCredentialsProvider)
    .httpClientBuilder {
      NettyNioAsyncHttpClient.builder()
        .maxConcurrency(rekognitionMaxConcurrency)
        .readTimeout(Duration.ofSeconds(rekognitionReadTimeoutSeconds))
        .build()
    }
    .build()

  companion object {
    val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}
