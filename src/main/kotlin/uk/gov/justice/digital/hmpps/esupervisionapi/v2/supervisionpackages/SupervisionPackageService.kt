package uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN

@Service
class SupervisionPackageService(
  private val supervisionPackagesApiClient: ISupervisionPackagesApiClient,
) {
  /**
   * Whether the person is on a supervision package - see [SupervisionPackageDetails.isOnSupervisionPackage].
   * Null when Supervision Packages does not know the CRN.
   * @throws SupervisionPackagesFetchException when Supervision Packages could not be asked
   */
  fun isOnSupervisionPackage(crn: CRN): Boolean? = supervisionPackagesApiClient.getSupervisionPackageDetails(crn)?.isOnSupervisionPackage
}
