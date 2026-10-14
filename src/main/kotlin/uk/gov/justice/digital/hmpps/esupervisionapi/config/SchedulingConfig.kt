package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

@Configuration
@ConditionalOnProperty(
  name = ["app.batch.enabled"],
  havingValue = "false",
  matchIfMissing = true,
)
@EnableScheduling
class SchedulingConfig
