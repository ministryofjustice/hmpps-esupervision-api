package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CodedDescription
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.ResourceNotFoundException
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.CustodyDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.ISupervisionPackagesApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackageDetails
import java.util.concurrent.CompletionException
import java.util.concurrent.Executors

class SupervisionPackagesDataProviderTest {
  private val supervisionPackagesApiClient: ISupervisionPackagesApiClient = mock()
  private val executor = Executors.newSingleThreadExecutor()
  private val provider = SupervisionPackagesDataProvider(supervisionPackagesApiClient, executor)

  @AfterEach
  fun tearDown() {
    executor.shutdownNow()
  }

  @Test
  fun `sourceKey is SUP-PACK`() {
    assertEquals("SUP-PACK", provider.sourceKey)
  }

  @Test
  fun `fetch maps recall and phase eligibility data`() {
    whenever(supervisionPackagesApiClient.getSupervisionPackageDetails("X123456")).thenReturn(
      SupervisionPackageDetails(
        supervisionPackage = CodedDescription("SPA", "Supervision package"),
        phase = CodedDescription("FTHRD", "Final third"),
        recallStatus = null,
        custody = listOf(
          CustodyDetails(
            eventNumber = "1",
            status = CodedDescription("C", "Recalled"),
            location = null,
            latestReleaseDate = null,
            latestRecallDate = null,
          ),
        ),
      ),
    )

    assertEquals(
      mapOf("RECALLED" to true, "FINAL_THIRD" to true, "EARLY_ENGAGEMENT" to false),
      provider.fetch("X123456").join(),
    )
  }

  @Test
  fun `null from Supervision Packages is surfaced as a not found source failure`() {
    whenever(supervisionPackagesApiClient.getSupervisionPackageDetails("X123456")).thenReturn(null)

    val thrown = assertThrows(CompletionException::class.java) { provider.fetch("X123456").join() }

    assertEquals(ResourceNotFoundException::class.java, thrown.cause?.javaClass)
  }
}
