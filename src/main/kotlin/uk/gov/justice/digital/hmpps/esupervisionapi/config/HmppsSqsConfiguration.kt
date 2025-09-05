package uk.gov.justice.digital.hmpps.esupervisionapi.config

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.hmpps.sqs.HmppsHealthContributorRegistry
import uk.gov.justice.hmpps.sqs.HmppsQueueFactory
import uk.gov.justice.hmpps.sqs.HmppsQueueService
import uk.gov.justice.hmpps.sqs.HmppsSqsProperties
import uk.gov.justice.hmpps.sqs.HmppsTopicFactory
import uk.gov.justice.hmpps.sqs.SnsClientFactory
import uk.gov.justice.hmpps.sqs.SqsClientFactory

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
