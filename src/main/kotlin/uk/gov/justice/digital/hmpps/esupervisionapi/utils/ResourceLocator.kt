package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import java.net.URL
import java.util.UUID

interface ResourceLocator {
  fun getOffenderPhoto(offenderUuid: UUID): URL?
  fun getCheckinVideo(checkinUuid: UUID): URL?
}
