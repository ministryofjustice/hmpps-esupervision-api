package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.MatchingOffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.OffenderIdVerifier
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService

/**
 * Provides stub implementations of the beans required identity verification. Images are uploaded
 * to a localstack s3 bucket, and a stub implementation of the verification process is used which
 * always returns a match
 */
@Profile("stubrekog | test")
@Configuration
class StubRekogConfig(
  @Value("\${aws.endpoint-url}") val endpointUrl: String,
  @Value("\${rekognition.s3_bucket_name}") val bucketName: String,
) {
  @Bean(name = ["rekognitionS3"])
  fun s3UploadService(): S3UploadService {
    val credsProvider = StaticCredentialsProvider.create(AwsBasicCredentials.create("AKAIstub", "stub"))
    val s3Client = getS3Client(credsProvider)
    val s3Presigner = getS3Presigner(credsProvider)

    val service = S3UploadService(s3Client, s3Presigner, bucketName, bucketName)
    return service
  }

  @Bean
  fun rekognitionCompareFacesService(): OffenderIdVerifier {
    LOGGER.info("Using stub id verification service")
    return MatchingOffenderIdVerifier()
  }

  fun getS3Client(credentialsProvider: AwsCredentialsProvider): S3Client {
    val builder = S3Client.builder()
      .region(Region.of(REGION))
      .credentialsProvider(credentialsProvider)

    builder.endpointOverride(java.net.URI.create(endpointUrl))
    builder.forcePathStyle(true)

    return builder.build()
  }

  fun getS3Presigner(credentialsProvider: AwsCredentialsProvider): S3Presigner {
    val builder = S3Presigner.builder()
      .region(Region.of(REGION))
      .credentialsProvider(credentialsProvider)
      .serviceConfiguration(
        S3Configuration.builder()
          .pathStyleAccessEnabled(true).build(),
      )
      .endpointOverride(java.net.URI.create(endpointUrl))

    return builder.build()
  }

  companion object {
    val REGION = "eu-west-2"
    val LOGGER: Logger = LoggerFactory.getLogger(this::class.java)
  }
}
