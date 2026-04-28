package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@Configuration
@Profile("local-aws")
class LocalAwsConfig(
  @Value("\${aws.region-name}") private val region: String,
  @Value("\${aws.s3.profile}") private val s3Profile: String,
) {

  @Bean(name = ["MOJ"])
  fun s3Client(): S3Client {
    LOG.info("Creating local S3 client with profile={}", s3Profile)
    return S3Client.builder()
      .region(Region.of(region))
      .credentialsProvider(ProfileCredentialsProvider.create(s3Profile))
      .build()
  }

  @Bean
  fun s3Presigner(): S3Presigner = S3Presigner.builder()
    .region(Region.of(region))
    .credentialsProvider(ProfileCredentialsProvider.create(s3Profile))
    .build()

  companion object {
    private val LOG = LoggerFactory.getLogger(LocalAwsConfig::class.java)
  }
}
