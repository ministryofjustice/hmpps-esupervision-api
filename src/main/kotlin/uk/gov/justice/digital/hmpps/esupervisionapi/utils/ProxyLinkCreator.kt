package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import org.springframework.stereotype.Component
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import java.net.URI

@Component
class ProxyLinkCreator(
  val appConfig: AppConfig,
) {

  fun checkinSnapshot(checkin: OffenderCheckin, index: Int = 0): URI = URI.create("${appConfig.mediaProxyUrl()}/checkin/${checkin.uuid}/snapshot?index=$index")

  fun offenderReferencePhoto(offender: Offender): URI = URI.create("${appConfig.mediaProxyUrl()}/offender/${offender.uuid}/photo")
}
