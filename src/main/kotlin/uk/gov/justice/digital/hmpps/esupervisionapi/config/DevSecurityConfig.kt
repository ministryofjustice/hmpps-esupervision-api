package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.justice.hmpps.kotlin.auth.dsl.ResourceServerConfigurationCustomizer

/**
 * Temporary security configuration that allows V2 endpoints without authentication.
 *
 * TODO: Remove this class and restore @PreAuthorize annotations before merging to main.
 */
@Configuration
class DevSecurityConfig {

  @Bean
  fun resourceServerConfigurationCustomizer() = ResourceServerConfigurationCustomizer {
    unauthorizedRequestPaths {
      addPaths = setOf("/v2/**")
    }
  }
}
