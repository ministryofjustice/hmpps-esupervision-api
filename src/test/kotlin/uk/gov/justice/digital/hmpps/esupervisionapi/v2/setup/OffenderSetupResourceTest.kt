package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.kotlin.mock
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoClient
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoUpload
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Offender
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderSetup
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.OffenderStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.dto.UploadHashRequest
import java.net.URI
import java.time.Duration
import java.util.Optional
import java.util.UUID

class OffenderSetupResourceTest {
  private val setupService: OffenderSetupService = mock()
  private val photoClient: OffenderPhotoClient = mock()
  private val resource = OffenderSetupResource(setupService, photoClient)
  private val setupUuid = UUID.randomUUID()
  private val offenderUuid = UUID.randomUUID()
  private val offender: Offender = mock {
    on { uuid }.thenReturn(offenderUuid)
    on { status }.thenReturn(OffenderStatus.INITIAL)
  }
  private val setup: OffenderSetup = mock {
    on { offender }.thenReturn(this@OffenderSetupResourceTest.offender)
  }

  @Test
  fun `setup upload uses the offender UUID and returns the signed checksum header`() {
    val url = URI.create("https://s3.example/photo").toURL()
    val headers = mapOf("x-amz-checksum-sha256" to "hash")
    whenever(setupService.findSetupByUuid(setupUuid)).thenReturn(Optional.of(setup))
    whenever(photoClient.presignUpload(offenderUuid, "image/jpeg", Duration.ofMinutes(5), "hash"))
      .thenReturn(OffenderPhotoUpload(url, headers))

    val response = resource.setupPhotoLocation(setupUuid, "image/jpeg", UploadHashRequest(sha256 = " hash "))

    assertThat(response.statusCode).isEqualTo(HttpStatus.OK)
    val location = requireNotNull(response.body?.locationInfo)
    assertThat(location.url).isEqualTo(url)
    assertThat(location.duration).isEqualTo("PT5M")
    assertThat(location.requiredHeaders).isEqualTo(headers)
  }

  @Test
  fun `setup upload rejects a missing hash before calling the photo client`() {
    whenever(setupService.findSetupByUuid(setupUuid)).thenReturn(Optional.of(setup))

    val error = assertThrows<ResponseStatusException> { resource.setupPhotoLocation(setupUuid, "image/jpeg", null) }

    assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
    verifyNoInteractions(photoClient)
  }
}
