package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.microsoft.applicationinsights.TelemetryClient
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service
import org.springframework.web.util.UriUtils
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.logger
import java.nio.charset.Charset

enum class TelemetryEvent(val label: String) {
  IMAGE_RETENTION_JOB_RESULTS("ImageRetentionJobResults"),
}

interface TelemetryService {
  fun trackEvent(event: Event)

  data class Event(
    val name: String,
    val properties: Map<String, String?>,
    val metrics: Map<String, Double?>,
  )
}

@Profile("!local & !test")
@Service
class TelemetryServiceImpl(
  private val telemetryClient: TelemetryClient = TelemetryClient(),
) : TelemetryService {
  companion object {
    private val log = LoggerFactory.getLogger(this::class.java)
  }

  @Async
  override fun trackEvent(event: TelemetryService.Event) {
    log.debug(
      "{} {} {}",
      UriUtils.encode(event.name, Charset.defaultCharset()),
      UriUtils.encode(event.properties.toString(), Charset.defaultCharset()),
      UriUtils.encode(event.metrics.toString(), Charset.defaultCharset()),
    )
    telemetryClient.trackEvent(
      event.name,
      event.properties.filterValues { it != null },
      event.metrics.filterValues { it != null },
    )
  }
}

@Profile("local | test")
@Service
class NoopTelemetryService : TelemetryService {
  @Async
  override fun trackEvent(event: TelemetryService.Event) {
    LOG.debug("Telemetry event: {}", event)
  }

  companion object {
    private val LOG = logger<NoopTelemetryService>()
  }
}
