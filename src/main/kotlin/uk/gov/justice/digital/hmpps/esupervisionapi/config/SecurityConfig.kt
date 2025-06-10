package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
class SecurityConfig {
  @Bean
  fun filterChain(http: HttpSecurity): SecurityFilterChain {
    http
      .headers { it.frameOptions { it.sameOrigin() } }
      .csrf { it.disable() }
      .authorizeHttpRequests { authz ->
        authz
          .requestMatchers(
            "/**",
//            "/practitioners/setup",
//            "/example/time",
//            "/h2-console/**",
//            "/health/**",
//            "/v3/api-docs/**",
//            "/swagger-ui/**",
//            "/swagger-ui.html",
//            "/offender_invites/**"
          )
          .permitAll()
          .anyRequest().authenticated()
      }
    return http.build()
  }
}
