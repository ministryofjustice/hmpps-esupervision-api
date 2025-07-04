package uk.gov.justice.digital.hmpps.esupervisionapi.config
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import uk.gov.service.notify.NotificationClient
import uk.gov.service.notify.NotificationClientApi

@Configuration
@EnableConfigurationProperties(MessageTemplateConfig::class)
class NotifyConfig(
  @Value("\${notify.apiKey}")private val apiKey: String,
) {
  @Bean
  fun notificationClient(): NotificationClientApi = NotificationClient(apiKey)
}
