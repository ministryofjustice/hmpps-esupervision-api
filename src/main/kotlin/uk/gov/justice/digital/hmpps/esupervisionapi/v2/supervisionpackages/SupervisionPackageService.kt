package uk.gov.justice.digital.hmpps.esupervisionapi.v2.supervisionpackages

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CRN
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.offender.SupervisionPackageStatus

@Service
class SupervisionPackageService(
  private val supervisionPackagesApiClient: ISupervisionPackagesApiClient,
) {
  /**
   * Whether the person is on a supervision package, in the final third, or in early engagement - see [SupervisionPackageDetails.isOnSupervisionPackage].
   * Null when Supervision Packages does not know the CRN.
   * @throws SupervisionPackagesFetchException when Supervision Packages could not be asked
   */
  fun getSupervisionPackageDetails(crn: CRN): SupervisionPackageStatus? {
    val packageDetails = supervisionPackagesApiClient.getSupervisionPackageDetails(crn) ?: return null
    return SupervisionPackageStatus(
      onSupervisionPackage = packageDetails.isOnSupervisionPackage,
      inFinalThird = packageDetails.isInFinalThird,
      inEarlyEngagement = packageDetails.isInEarlyEngagement,
    )
  }
}
