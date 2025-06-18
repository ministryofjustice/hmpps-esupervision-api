package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.S3Configuration
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import java.net.URI

@Configuration
@Profile("dev")
class LocalAwsConfig {
  @Value("\${aws.region-name}")
  lateinit var region: String

  @Value("\${aws.endpoint-url}")
  lateinit var endpointUrl: String

  @Bean
  fun s3Client(): S3Client {
    LOG.info("Creating S3 client endpoint={}", endpointUrl)
    return S3Client.builder()
      .endpointOverride(URI.create(endpointUrl))
      .region(Region.of(region))
      .forcePathStyle(true)
      .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
      .build()
  }

  @Bean
  fun s3Presigner(): S3Presigner = S3Presigner.builder()
    .serviceConfiguration(
      S3Configuration.builder()
        .pathStyleAccessEnabled(true).build())
    .endpointOverride(URI.create(endpointUrl))
    .region(Region.of(region))
    .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("any", "any")))
    .build()

  companion object {
    val LOG = LoggerFactory.getLogger(this::class.java)
  }
}
