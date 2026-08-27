package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import uk.gov.justice.hmpps.kotlin.auth.dsl.ResourceServerConfigurationCustomizer

/**
 * The default HMPPS resource server filter chain requires authentication for any path not on its
 * built-in allow-list (health/info/swagger/etc). That HTTP-layer check runs before Spring resolves
 * a controller method, so `@PreAuthorize("permitAll()")` alone can't open a path up - it only
 * relaxes authorization once a request has already passed the filter chain.
 */
@Configuration
@Profile("local")
class LocalSecurityConfig {

  @Bean
  fun resourceServerConfigurationCustomizer() = ResourceServerConfigurationCustomizer {
    unauthorizedRequestPaths {
      addPaths = setOf("/v2/eligibility/rules/view")
    }
  }
}
