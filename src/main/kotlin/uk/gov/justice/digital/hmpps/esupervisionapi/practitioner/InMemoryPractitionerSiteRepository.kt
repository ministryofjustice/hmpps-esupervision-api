package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

class InMemoryPractitionerSiteRepository(
  val siteAssignments: Map<ExternalUserId, String>,
) : PractitionerSiteRepository {
  override fun findLocation(practitionerId: ExternalUserId): PractitionerSite? {
    val siteName = siteAssignments[practitionerId]
    return if (siteName != null) {
      PractitionerSite(name = siteName)
    } else {
      null
    }
  }

  companion object {
    val LOGGER = LoggerFactory.getLogger(this::class.java)

    fun fromConfig(siteAssignmentsConfig: String): InMemoryPractitionerSiteRepository {
      LOGGER.info("Site assignments: {}", siteAssignmentsConfig)

      val mapping: Map<ExternalUserId, String> = if (siteAssignmentsConfig.isNotBlank()) {
        val objectMapper = ObjectMapper()
        objectMapper.readValue(siteAssignmentsConfig, object : TypeReference<Map<String, String>>() {})
      } else {
        emptyMap()
      }

      return InMemoryPractitionerSiteRepository(mapping)
    }
  }
}
