package uk.gov.justice.digital.hmpps.esupervisionapi.integration.v2

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.util.ReflectionTestUtils
import uk.gov.justice.digital.hmpps.esupervisionapi.integration.IntegrationTestBase
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.INdiliusApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.NdeliusEligibilityDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.SupervisionPackagesDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.TierEligibilityDataProvider
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.OffenderService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.ISupervisionPackagesApiClient
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages.SupervisionPackageService
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.tier.ITierApiClient

@ActiveProfiles(
  profiles = ["test", "local", "stubndilius", "stubtier", "stubsupervisionpackages"],
  inheritProfiles = false,
)
class StubApiClientWiringIntegrationTest : IntegrationTestBase() {
  @Autowired
  private lateinit var tierEligibilityDataProvider: TierEligibilityDataProvider

  @Autowired
  @Qualifier("tierApiClient")
  private lateinit var standardTierApiClient: ITierApiClient

  @Autowired
  @Qualifier("tierEligibilityApiClient")
  private lateinit var eligibilityTierApiClient: ITierApiClient

  @Autowired
  private lateinit var ndeliusEligibilityDataProvider: NdeliusEligibilityDataProvider

  @Autowired
  @Qualifier("ndiliusApiClient")
  private lateinit var standardNdiliusApiClient: INdiliusApiClient

  @Autowired
  @Qualifier("ndeliusEligibilityApiClient")
  private lateinit var eligibilityNdiliusApiClient: INdiliusApiClient

  @Autowired
  private lateinit var supervisionPackagesDataProvider: SupervisionPackagesDataProvider

  @Autowired
  @Qualifier("supervisionPackagesApiClient")
  private lateinit var standardSupervisionPackagesApiClient: ISupervisionPackagesApiClient

  @Autowired
  @Qualifier("supervisionPackagesEligibilityApiClient")
  private lateinit var eligibilitySupervisionPackagesApiClient: ISupervisionPackagesApiClient

  @Autowired
  private lateinit var supervisionPackageService: SupervisionPackageService

  @Autowired
  private lateinit var offenderService: OffenderService

  @Test
  fun `documented multi-event CRN is available through the NDelius stub`() {
    val details = requireNotNull(standardNdiliusApiClient.getContactDetails("X000010"))

    assertEquals(2, details.events.size)
    assertTrue(details.events.all { it.sentence?.expectedEndDate != null })
    assertEquals(2, details.events.map { it.sentence?.expectedEndDate }.toSet().size)
    assertEquals(2, details.events.map { it.sentence?.length }.toSet().size)
  }

  @Test
  fun `stub clients resolve for both standard and eligibility consumers`() {
    assertSame(standardTierApiClient, eligibilityTierApiClient)
    assertSame(eligibilityTierApiClient, ReflectionTestUtils.getField(tierEligibilityDataProvider, "tierApiClient"))
    assertSame(standardTierApiClient, ReflectionTestUtils.getField(offenderService, "tierApiClient"))

    assertSame(standardNdiliusApiClient, eligibilityNdiliusApiClient)
    assertSame(
      eligibilityNdiliusApiClient,
      ReflectionTestUtils.getField(ndeliusEligibilityDataProvider, "ndiliusApiClient"),
    )
    assertSame(standardNdiliusApiClient, ReflectionTestUtils.getField(offenderService, "ndiliusApiClient"))

    assertSame(standardSupervisionPackagesApiClient, eligibilitySupervisionPackagesApiClient)
    assertSame(
      eligibilitySupervisionPackagesApiClient,
      ReflectionTestUtils.getField(supervisionPackagesDataProvider, "supervisionPackagesApi"),
    )
    assertSame(
      standardSupervisionPackagesApiClient,
      ReflectionTestUtils.getField(supervisionPackageService, "supervisionPackagesApiClient"),
    )
  }
}
