package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.core.env.MapPropertySource
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor

class SchedulingConfigTest {

  @Test
  fun `scheduling is disabled in batch mode`() {
    contextWithBatchEnabled(true).use { context ->
      assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isEmpty()
    }
  }

  @Test
  fun `scheduling is enabled in web mode`() {
    contextWithBatchEnabled(false).use { context ->
      assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isNotEmpty()
    }
  }

  @Test
  fun `scheduling is enabled when batch mode is not configured`() {
    AnnotationConfigApplicationContext().use { context ->
      context.register(SchedulingConfig::class.java)
      context.refresh()

      assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor::class.java)).isNotEmpty()
    }
  }

  private fun contextWithBatchEnabled(enabled: Boolean): AnnotationConfigApplicationContext = AnnotationConfigApplicationContext().also { context ->
    context.environment.propertySources.addFirst(
      MapPropertySource("test", mapOf("app.batch.enabled" to enabled)),
    )
    context.register(SchedulingConfig::class.java)
    context.refresh()
  }
}
