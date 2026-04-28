package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckinV2
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderV2
import java.net.URI

@Component
class ProxyLinkCreator(
  val appConfig: AppConfig,
) {

  fun checkinSnapshot(checkin: OffenderCheckinV2, index: Int = 0): URI = URI.create("${appConfig.mediaProxyUrl()}/checkin/${checkin.uuid}/snapshot?index=$index")

  fun offenderReferencePhoto(offender: OffenderV2): URI = URI.create("${appConfig.mediaProxyUrl()}/offender/${offender.uuid}/photo")
}
