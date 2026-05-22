package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.mockito.Mockito.mock
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import uk.gov.justice.digital.hmpps.esupervisionapi.events.DomainEventPublisher
import uk.gov.justice.hmpps.sqs.HmppsQueueService

@TestConfiguration
class MockConfig {

  @Bean("hmppsQueueService")
  @Primary
  fun mockHmppsQueueService(): HmppsQueueService = mock(HmppsQueueService::class.java)

  @Bean
  @Primary
  fun mockDomainEventPublisher(): DomainEventPublisher = mock(DomainEventPublisher::class.java)
}
