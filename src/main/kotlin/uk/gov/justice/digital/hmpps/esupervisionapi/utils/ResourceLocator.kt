package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import java.net.URL

interface ResourceLocator {
  fun getOffenderPhoto(offender: Offender): URL?
  fun getCheckinVideo(checkin: OffenderCheckin, force: Boolean = false): URL?
  fun getCheckinSnapshot(checkin: OffenderCheckin, force: Boolean = false): URL?
}
