package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.context.annotation.Configuration

@Configuration
class HmppsSqsConfiguration {

//  @Bean
//  @ConditionalOnMissingBean
//  fun hmppsTopicFactory(applicationContext: ConfigurableApplicationContext, healthContributorRegistry: HmppsHealthContributorRegistry) = HmppsTopicFactory(applicationContext, healthContributorRegistry, SnsClientFactory())
//
//  @Bean
//  @ConditionalOnMissingBean
//  fun hmppsQueueFactory(applicationContext: ConfigurableApplicationContext, healthContributorRegistry: HmppsHealthContributorRegistry, objectMapper: ObjectMapper) = HmppsQueueFactory(applicationContext, healthContributorRegistry, SqsClientFactory(), objectMapper)
//
//  @Bean
//  fun hmppsQueueService(hmppsTopicFactory: HmppsTopicFactory, hmppsQueueFactory: HmppsQueueFactory, hmppsSqsProperties: HmppsSqsProperties): HmppsQueueService =
//    HmppsQueueService(telemetryClient = null, hmppsSqsProperties = hmppsSqsProperties, hmppsQueueFactory = hmppsQueueFactory, hmppsTopicFactory = hmppsTopicFactory)
}
