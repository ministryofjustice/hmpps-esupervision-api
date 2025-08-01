package uk.gov.justice.digital.hmpps.esupervisionapi.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import javax.sql.DataSource

@Configuration
@Profile("!test")
@EnableSchedulerLock(
  defaultLockAtLeastFor = "PT1M",
  defaultLockAtMostFor = "PT10M",
)
class CheckinNotifierConfiguration {
  @Bean
  fun lockProvider(ds: DataSource): LockProvider = JdbcTemplateLockProvider(ds)
}
