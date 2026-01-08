package uk.gov.justice.digital.hmpps.esupervisionapi.integration.mpop

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.GeneratingStubDataProvider

class DataProviderTest {
  @Test
  fun `multi sample data provider`() {
    val provider = GeneratingStubDataProvider()
    val case1 = provider.provideCase("X001101")
    val case2 = provider.provideCase("X001122")

    Assertions.assertEquals(case1.practitioner, case2.practitioner)
    Assertions.assertNotEquals(case1.name, case2.name)

    val case3 = provider.provideCase("X002201")
    Assertions.assertEquals(case2.practitioner?.name, case3.practitioner?.name)
    Assertions.assertNotEquals(case2.practitioner?.localAdminUnit, case3.practitioner?.localAdminUnit)
  }
}
