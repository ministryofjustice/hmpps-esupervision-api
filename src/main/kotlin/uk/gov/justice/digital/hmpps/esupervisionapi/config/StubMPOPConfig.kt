package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import uk.gov.justice.digital.hmpps.esupervisionapi.mpop.DefaultStubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.mpop.MpopService
import uk.gov.justice.digital.hmpps.esupervisionapi.mpop.StubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.mpop.StubMpopService

@Profile("stubmpop | test")
@Configuration
class StubMPOPConfig {

  @Bean
  fun stubDataProvider(): StubDataProvider = DefaultStubDataProvider()

  @Bean
  fun mpopService(dataProvider: StubDataProvider): MpopService = StubMpopService(dataProvider)
}
