package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.context.annotation.Profile
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.NdeliusEligibilityApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackagesApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierApiClient

@Configuration
class ApiClientConfiguration {
  @Bean
  @Profile("!stubtier")
  @Primary
  fun tierApiClient(@Qualifier("tierApiWebClient") webClient: WebClient): TierApiClient = TierApiClient(webClient)

  @Bean
  @Profile("!stubtier")
  @Qualifier("tierEligibilityApiClient")
  fun tierEligibilityApiClient(@Qualifier("tierEligibilityWebClient") webClient: WebClient): TierApiClient = TierApiClient(webClient)

  @Bean
  @Profile("!stubndilius")
  @Qualifier("ndeliusEligibilityApiClient")
  fun ndeliusEligibilityApiClient(@Qualifier("ndeliusEligibilityWebClient") webClient: WebClient): INdiliusApiClient = NdeliusEligibilityApiClient(webClient)

  @Bean
  @Profile("!stubsupervisionpackages")
  @Primary
  fun supervisionPackagesApiClient(
    @Qualifier("supervisionPackagesApiWebClient") webClient: WebClient,
  ): SupervisionPackagesApiClient = SupervisionPackagesApiClient(webClient)

  @Bean
  @Profile("!stubsupervisionpackages")
  @Qualifier("supervisionPackagesEligibilityApiClient")
  fun supervisionPackagesEligibilityApiClient(
    @Qualifier("supervisionPackagesEligibilityWebClient") webClient: WebClient,
  ): SupervisionPackagesApiClient = SupervisionPackagesApiClient(webClient)
}
