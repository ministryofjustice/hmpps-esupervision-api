package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider

@Configuration
class TestRekogConfig {

  @Bean
  fun rekognitionCredentialsProvider(): AwsCredentialsProvider = StaticCredentialsProvider
    .create(AwsBasicCredentials.create("AKAItesting", "testing"))
}
