package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class NomisEligibilityDataProviderTest {

  private val provider = NomisEligibilityDataProvider()

  @Test
  fun `sourceKey is NOMIS`() {
    assertEquals("NOMIS", provider.sourceKey)
  }

  @Test
  fun `fetch returns a null RECALL_STATUS placeholder`() {
    val result = provider.fetch("X123456").join()

    assertEquals(mapOf("RECALL_STATUS" to null), result)
  }
}
