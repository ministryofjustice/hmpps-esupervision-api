package uk.gov.justice.digital.hmpps.esupervisionapi.mpop

typealias CRN = String

/**
 * Represents API for getting offender details from MPOP.
 */
interface MpopService {
  fun case(crn: CRN): CaseDto
  fun cases(crns: List<CRN>): List<CaseDto>
}
