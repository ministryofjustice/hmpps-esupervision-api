package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import uk.gov.justice.digital.hmpps.esupervisionapi.offender.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckin
import java.net.URL

/**
 * Null object pattern implementation of resource locator
 */
class NullResourceLocator : ResourceLocator {
  override fun getOffenderPhoto(offender: Offender): URL? = null
  override fun getCheckinVideo(checkin: OffenderCheckin): URL? = null
  override fun getCheckinSnapshot(checkin: OffenderCheckin): URL? = null
}
