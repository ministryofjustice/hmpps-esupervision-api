package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.env.Environment
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.rekognition.RekognitionClient
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.RekognitionCompareFacesService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.net.URI

private const val LOCAL_AWS = "http://localhost:4566"

@Configuration
class RekogConfig(
  @Value("\${rekognition.region}") val region: String,
  @Value("\${rekognition.access_key_id}") val accessKeyId: String,
  @Value("\${rekognition.secret_access_key}") val accessKeySecret: String,
  @Value("\${rekognition.s3_bucket_name}") val bucketName: String,
) {

  @Autowired
  lateinit var environment: Environment

  @Bean(name = ["rekognitionS3Client"])
  fun s3Client(): S3Client {
    val credentials = AwsBasicCredentials.create(accessKeyId, accessKeySecret)
    val builder = S3Client.builder()
      .region(Region.of(region))
      .credentialsProvider(StaticCredentialsProvider.create(credentials))

    val profiles = environment.activeProfiles
    if (profiles.contains("dev") && !profiles.contains("rekog")) {
      builder.endpointOverride(URI.create(LOCAL_AWS))
      builder.forcePathStyle(true)
    }

    return builder.build()
  }

  @Bean(name = ["rekognitionS3PreSigner"])
  fun s3Presigner(): S3Presigner {
    val credentials = AwsBasicCredentials.create(accessKeyId, accessKeySecret)
    val builder = S3Presigner.builder()
      .region(Region.of(region))
      .credentialsProvider(StaticCredentialsProvider.create(credentials))

    val profiles = environment.activeProfiles
    if (profiles.contains("dev") && !profiles.contains("rekog")) {
      builder
        .serviceConfiguration(
          S3Configuration.builder()
            .pathStyleAccessEnabled(true).build(),
        )
        .endpointOverride(URI.create(LOCAL_AWS))
        .region(Region.of(region))
    }

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
  fun rekognitionCompareFacesService(): RekognitionCompareFacesService {
    val credentials = AwsBasicCredentials.create(accessKeyId, accessKeySecret)

    val client = RekognitionClient.builder()
      .region(Region.of(region))
      .credentialsProvider(StaticCredentialsProvider.create(credentials))
      .build()

    return RekognitionCompareFacesService(client)
  }
}
