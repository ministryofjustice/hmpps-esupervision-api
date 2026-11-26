package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.springframework.stereotype.Service

@Service
class CheckinContactDetailsService(
  private val ndiliusApiClient: INdiliusApiClient,
  private val practitionerReconciliationService: PractitionerReconciliationService,
) {
  fun getContactDetails(offender: Offender): ContactDetails? = ndiliusApiClient.getContactDetails(offender.crn).also {
    practitionerReconciliationService.reconcile(offender, it)
  }

  fun getContactDetailsStrict(offender: Offender): ContactDetails? = ndiliusApiClient.getContactDetailsStrict(offender.crn).also {
    practitionerReconciliationService.reconcile(offender, it)
  }
}
