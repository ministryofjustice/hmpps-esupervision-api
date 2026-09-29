package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class EligibilityDataProviderRegistryTest {

  @Test
  fun `resolves a provider by its sourceKey`() {
    val ndelius: EligibilityDataProvider = mock()
    whenever(ndelius.sourceKey).thenReturn("NDELIUS")
    val nomis: EligibilityDataProvider = mock()
    whenever(nomis.sourceKey).thenReturn("NOMIS")
    val registry = EligibilityDataProviderRegistry(listOf(ndelius, nomis))

    assertEquals(ndelius, registry.get("NDELIUS"))
    assertEquals(nomis, registry.get("NOMIS"))
  }

  @Test
  fun `throws a clear error for an unknown source`() {
    val registry = EligibilityDataProviderRegistry(emptyList())

    val exception = assertThrows(IllegalStateException::class.java) { registry.get("UNKNOWN") }
    assertEquals(true, exception.message?.contains("UNKNOWN"))
  }
}
