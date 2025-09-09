package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rekognition.RekognitionClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.sts.StsClient
import software.amazon.awssdk.services.sts.auth.StsAssumeRoleCredentialsProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.RekognitionCompareFacesService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService

@Configuration
class RekogConfig(
  @Value("\${rekognition.region}") val region: String,
  @Value("\${rekognition.s3_bucket_name}") val bucketName: String,
  @Value("\${rekognition.role_arn}") val roleArn: String,
  @Value("\${rekognition.role_session_name}") val roleSessionName: String,
) {

  @Bean
  @Profile("!test")
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

  @Bean(name = ["rekognitionS3Client"])
  fun s3Client(rekognitionCredentialsProvider: AwsCredentialsProvider): S3Client {
    val builder = S3Client.builder()
      .region(Region.of(region))
      .credentialsProvider(rekognitionCredentialsProvider)

    return builder.build()
  }

  @Bean(name = ["rekognitionS3PreSigner"])
  fun s3Presigner(rekognitionCredentialsProvider: AwsCredentialsProvider): S3Presigner {
    val builder = S3Presigner.builder()
      .region(Region.of(region))
      .credentialsProvider(rekognitionCredentialsProvider)

    return builder.build()
  }

  @Bean(name = ["rekognitionS3"])
  fun s3UploadService(
    @Qualifier("rekognitionS3Client") s3Client: S3Client,
    @Qualifier("rekognitionS3PreSigner") s3Presigner: S3Presigner,
  ): S3UploadService {
    val service = S3UploadService(s3Client, s3Presigner, bucketName, bucketName)
    return service
  }

  @Bean
  fun rekognitionCompareFacesService(rekognitionCredentialsProvider: AwsCredentialsProvider): RekognitionCompareFacesService {
    val client = RekognitionClient.builder()
      .region(Region.of(region))
      .credentialsProvider(rekognitionCredentialsProvider)
      .build()

    return RekognitionCompareFacesService(client)
  }

  companion object {
    val LOGGER = LoggerFactory.getLogger(this::class.java)
  }
}
