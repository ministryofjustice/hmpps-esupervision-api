package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService

@TestConfiguration
class MockS3Config {
  @Bean
  @Primary
  fun mockS3UploadService(): S3UploadService = mock(S3UploadService::class.java)
}
