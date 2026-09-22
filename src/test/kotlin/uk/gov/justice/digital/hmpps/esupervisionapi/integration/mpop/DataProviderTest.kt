package uk.gov.justice.digital.hmpps.esupervisionapi.integration.mpop

import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.GeneratingStubDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.TierApiVersion

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
    Assertions.assertNull(recalled.recallStatus, "a decided recall has no open request")
    Assertions.assertNotNull(recalled.custody.single().latestRecallDate)
    val noPackage = provider.provideSupervisionPackageDetails("X000004")
    Assertions.assertNull(noPackage.supervisionPackage)
    Assertions.assertNull(noPackage.phase)
    Assertions.assertEquals("STD", provider.provideSupervisionPackageDetails("X000005").phase?.code)
    val recallRequested = provider.provideSupervisionPackageDetails("X000006")
    Assertions.assertEquals("REC01", recallRequested.recallStatus?.code)
    Assertions.assertTrue(recallRequested.custody.isEmpty(), "an undecided request has no recall recorded")
  }

  @Test
  fun `v3 tier score is chosen by the last CRN digit`() {
    val provider = GeneratingStubDataProvider()
    val scores = (0..9).map { provider.provideTierDetails("X00000$it", TierApiVersion.V3).tierScore }

    Assertions.assertEquals(listOf("A", "B", "C", "D", "E", "F", "G", "NOT_SUPERVISED", "MISSING", "D"), scores)
  }

  @Test
  fun `v3 tier is provisional only for the CRN ending in 0`() {
    val provider = GeneratingStubDataProvider()
    val provisional = (0..9).map { provider.provideTierDetails("X00000$it", TierApiVersion.V3).provisional }

    Assertions.assertEquals(listOf(true, false, false, false, false, false, false, false, false, false), provisional)
  }

  @Test
  fun `v2 tier is never marked provisional`() {
    val provider = GeneratingStubDataProvider()

    Assertions.assertNull(provider.provideTierDetails("X000000", TierApiVersion.V2).provisional)
  }
}
