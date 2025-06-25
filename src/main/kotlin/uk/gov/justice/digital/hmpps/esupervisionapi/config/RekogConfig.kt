package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.core.env.Environment
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import java.net.URI

@Configuration
@Profile("local")
class RekogConfig(
  @Value("\${rekognition.region}") val region: String,
  @Value("\${rekognition.access_key_id}") val accessKeyId: String,
  @Value("\${rekognition.secret_access_key}") val accessKeySecret: String,
  @Value("\${rekognition.s3_bucket_name}") val bucketName: String,
  @Value("\${aws.endpoint-url}") val awsEndpointUrl: String,
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
    if (profiles.contains("local")) {
      builder.endpointOverride(URI.create(awsEndpointUrl))
      builder.forcePathStyle(true)
    }

    return builder.build()
  }
}
