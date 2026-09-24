package uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription

class SupervisionPackageServiceTest {

  private val client: ISupervisionPackagesApiClient = mock()
  private val service = SupervisionPackageService(client)

  private val crn = "X000001"
  private val standard = CodedDescription("STD", "Standard supervision")
  private val finalThird = CodedDescription("FTHRD", "Final third")
  private val earlyEngagement = CodedDescription("INIT", "Early engagement")

  private fun onPackage(code: String) = SupervisionPackageDetails(CodedDescription(code, code), standard, recallStatus = null)

  @ParameterizedTest
  @ValueSource(strings = ["SPA", "SPB", "SPC", "SPD", "SPE", "SPF", "SPG"])
  fun `packages A to G are on a supervision package`(code: String) {
    whenever(client.getSupervisionPackageDetails(crn)).thenReturn(onPackage(code))

    val status = service.getSupervisionPackageDetails(crn)

    assertEquals(true, status!!.onSupervisionPackage)
    assertEquals(false, status.inFinalThird)
    assertEquals(false, status.inEarlyEngagement)
  }

  @ParameterizedTest
  @ValueSource(strings = ["SPNA", "SPNK", "SPX"])
  fun `not applicable, not yet known and supervised on another sentence are not`(code: String) {
    whenever(client.getSupervisionPackageDetails(crn)).thenReturn(onPackage(code))

    val status = service.getSupervisionPackageDetails(crn)

    assertEquals(false, status!!.onSupervisionPackage)
    assertEquals(false, status.inFinalThird)
    assertEquals(false, status.inEarlyEngagement)
  }

  @Test
  fun `phase is in final third`() {
    whenever(client.getSupervisionPackageDetails(crn)).thenReturn(
      SupervisionPackageDetails(
        supervisionPackage = CodedDescription("SPX", "Supervised on another sentence"),
        phase = finalThird,
        recallStatus = null,
        sentencePackages = listOf(CodedDescription("SPX", "Supervised on another sentence"), CodedDescription("SPX", "X")),
      ),
    )

    val status = service.getSupervisionPackageDetails(crn)

    assertEquals(false, status!!.onSupervisionPackage)
    assertEquals(true, status.inFinalThird)
    assertEquals(false, status.inEarlyEngagement)
  }

  @Test
  fun `phase is in early engagement`() {
    whenever(client.getSupervisionPackageDetails(crn)).thenReturn(
      SupervisionPackageDetails(
        supervisionPackage = CodedDescription("SPX", "Supervised on another sentence"),
        phase = earlyEngagement,
        recallStatus = null,
        sentencePackages = listOf(CodedDescription("SPX", "Supervised on another sentence"), CodedDescription("SPB", "B")),
      ),
    )

    val status = service.getSupervisionPackageDetails(crn)

    assertEquals(true, status!!.onSupervisionPackage)
    assertEquals(false, status.inFinalThird)
    assertEquals(true, status.inEarlyEngagement)
  }

  @Test
  fun `no current package is not on a supervision package`() {
    whenever(client.getSupervisionPackageDetails(crn)).thenReturn(SupervisionPackageDetails(null, null, recallStatus = null))

    val status = service.getSupervisionPackageDetails(crn)

    assertEquals(false, status!!.onSupervisionPackage)
  }

  @Test
  fun `a package on a sentence other than the current phase's counts`() {
    whenever(client.getSupervisionPackageDetails(crn)).thenReturn(
      SupervisionPackageDetails(
        supervisionPackage = CodedDescription("SPX", "Supervised on another sentence"),
        phase = standard,
        recallStatus = null,
        sentencePackages = listOf(CodedDescription("SPX", "Supervised on another sentence"), CodedDescription("SPB", "B")),
      ),
    )

    val status = service.getSupervisionPackageDetails(crn)

    assertEquals(true, status!!.onSupervisionPackage)
  }

  @Test
  fun `a package on a sentence counts with no current phase`() {
    whenever(client.getSupervisionPackageDetails(crn)).thenReturn(
      SupervisionPackageDetails(null, null, recallStatus = null, sentencePackages = listOf(CodedDescription("SPD", "D"))),
    )

    val status = service.getSupervisionPackageDetails(crn)

    assertEquals(true, status!!.onSupervisionPackage)
  }

  @Test
  fun `a CRN Supervision Packages does not know is null, not false`() {
    whenever(client.getSupervisionPackageDetails(crn)).thenReturn(null)

    assertNull(service.getSupervisionPackageDetails(crn))
  }

  @Test
  fun `an outage propagates rather than reading as not on a package`() {
    val outage = SupervisionPackagesFetchException(crn, "down", RuntimeException("503"))
    whenever(client.getSupervisionPackageDetails(crn)).thenThrow(outage)

    assertEquals(outage, assertThrows<SupervisionPackagesFetchException> { service.getSupervisionPackageDetails(crn) })
  }
}
