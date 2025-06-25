package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.presigner.S3Presigner

@Configuration
@Profile("!test & !local")
class AwsConfig {

  @Value("\${aws.region-name}")
  lateinit var region: String

  @Bean(name = ["MOJ"])
  fun s3Client(): S3Client = S3Client.builder()
    .region(Region.of(region))
    .build()

  @Bean
  fun s3Presigner(): S3Presigner = S3Presigner.builder()
    .region(Region.of(region))
    .build()
}
