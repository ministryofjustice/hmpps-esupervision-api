package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.rekognition.RekognitionCompareFacesService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.hmpps.sqs.HmppsQueueService

@TestConfiguration
class MockS3Config {
  @Bean
  @Primary
  fun mockS3UploadService(): S3UploadService = mock(S3UploadService::class.java)

  @Bean
  @Primary
  fun mockNotificationService(): NotificationService = mock(NotificationService::class.java)

  @Bean
  @Primary
  fun mockCompareFacesService(): RekognitionCompareFacesService = mock(RekognitionCompareFacesService::class.java)

  @Bean
  @Primary
  fun mockHmppsQueueService(): HmppsQueueService = mock(HmppsQueueService::class.java)

  @Bean
  @Primary
  fun mockDomainEventPublisher(): DomainEventPublisher = mock(DomainEventPublisher::class.java)
}
