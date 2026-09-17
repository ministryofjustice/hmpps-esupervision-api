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

  @Test
  fun `supervision package phase is chosen by the last CRN digit`() {
    val provider = GeneratingStubDataProvider()

    Assertions.assertEquals("INIT", provider.provideSupervisionPackageDetails("X000001").phase?.code)
    Assertions.assertEquals("FTHRD", provider.provideSupervisionPackageDetails("X000002").phase?.code)
    val recalled = provider.provideSupervisionPackageDetails("X000003")
    Assertions.assertEquals("SENT", recalled.phase?.code)
    Assertions.assertNotNull(recalled.recallStatus)
    Assertions.assertNotNull(recalled.custody.single().latestRecallDate)
    val noPackage = provider.provideSupervisionPackageDetails("X000004")
    Assertions.assertNull(noPackage.supervisionPackage)
    Assertions.assertNull(noPackage.phase)
    Assertions.assertEquals("STD", provider.provideSupervisionPackageDetails("X000005").phase?.code)
  }
}
