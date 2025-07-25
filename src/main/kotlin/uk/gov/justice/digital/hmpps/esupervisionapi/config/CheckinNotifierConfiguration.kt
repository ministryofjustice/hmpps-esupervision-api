package uk.gov.justice.digital.hmpps.esupervisionapi.config

import net.javacrumbs.shedlock.core.LockProvider
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.time.Duration
import javax.sql.DataSource

@Configuration
@Profile("!test")
@EnableSchedulerLock(
  defaultLockAtLeastFor = "PT1M",
  defaultLockAtMostFor = "PT10M",
)
class CheckinNotifierConfiguration(
  /**
   * How long, starting from dueDate, do we accept checkin submissions.
   * Afterward the checkin's status is updated to EXPIRED.
   */
  @Value("\${app.scheduling.checkin-notification.window:72h}") val checkinWindow: Duration,
) {
  @Bean
  fun lockProvider(ds: DataSource): LockProvider = JdbcTemplateLockProvider(ds)
}
