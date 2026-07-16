package uk.gov.justice.digital.hmpps.esupervisionapi.v2.stats

import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class MetricsDebugResource(private val meterRegistry: MeterRegistry) {

  enum class Metrics(val label: String) {
    NDELIUS_GET_CONTACT_DETAILS("ndelius.get-contact-details"),
    NDELIUS_GET_CONTACT_FOR_MULTIPLE("ndelius.get-contact-details-for-multiple"),
    NDELIUS_VALIDATE_DETAILS("ndelius.validate-details"),
    REKOG_LIVENESS_CREATE_SESSION("rekog.liveness.create-session"),
    REKOG_LIVENESS_RESULTS("rekog.liveness.results"),
    REKOG_COMPARE_FACES_VERIFY_CHECKIN_IMAGES("rekog.compare-faces.verify-chckin-images"),
  }

  @PreAuthorize("hasRole('ROLE_ESUPERVISION__ESUPERVISION_UI')")
  @GetMapping("/metrics")
  fun getMetrics(request: HttpServletRequest): Map<String, Any> = mapOf(
    "host" to request.serverName,
    "metrics" to Metrics.entries.map { meterRegistry.metricFor(it) },
  )
}

private fun MeterRegistry.metricFor(metric: MetricsDebugResource.Metrics): Map<String, Any> {
  val meter = this.find(metric.label).timer()
  val snapshot = meter?.takeSnapshot()
  fun format(value: Double): String = String.format("%.4f", value)

  return if (snapshot != null) {
    mapOf(
      "metric_name" to metric.label,
      "count" to snapshot.count(),
      "max_time_ms" to format(snapshot.max(java.util.concurrent.TimeUnit.MILLISECONDS)),
      "mean_time_ms" to format(snapshot.mean(java.util.concurrent.TimeUnit.MILLISECONDS)),
    )
  } else {
    mapOf("metric_name" to metric.label, "message" to "not recorded yet.")
  }
}
