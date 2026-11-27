package uk.gov.justice.digital.hmpps.esupervisionapi.photos.http

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.MediaType
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoClient
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoPurgeResult
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoUpload
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.ReferencePhotoLocation
import java.net.URL
import java.time.Duration
import java.util.UUID

@Service
@ConditionalOnProperty(name = ["app.features.esup-2349"], havingValue = "true")
internal class HttpOffenderPhotoClient(
  @Qualifier("offenderPhotoApiWebClient") private val webClient: WebClient,
) : OffenderPhotoClient {
  init {
    LoggerFactory.getLogger(javaClass).info("Using HttpOffenderPhotoClient")
  }

  /** Photo API fixes the upload TTL at five minutes; the requested [duration] cannot be forwarded. */
  override fun presignUpload(offenderUuid: UUID, contentType: String, duration: Duration, checksumSha256Base64: String): OffenderPhotoUpload {
    val response = webClient.post()
      .uri { it.path("$BASE_PATH/reference/upload-location").queryParam("content-type", contentType).build(offenderUuid) }
      .contentType(MediaType.APPLICATION_JSON)
      .bodyValue(UploadHashRequest(checksumSha256Base64))
      .retrieve()
      .bodyToMono(PhotoUploadLocation::class.java)
      .block() ?: error("Photo API returned an empty upload location")
    return OffenderPhotoUpload(response.url, response.requiredHeaders)
  }

  override fun presignRead(offenderUuid: UUID): URL = webClient.get()
    .uri("$BASE_PATH/reference/url", offenderUuid)
    .retrieve()
    .bodyToMono(PhotoReadLocation::class.java)
    .block()?.url ?: error("Photo API returned an empty read location")

  override fun isUploaded(offenderUuid: UUID): Boolean = webClient.get()
    .uri("$BASE_PATH/reference/status", offenderUuid)
    .retrieve()
    .bodyToMono(PhotoStatus::class.java)
    .block()?.uploaded ?: error("Photo API returned an empty status")

  override fun referenceLocation(offenderUuid: UUID): ReferencePhotoLocation = webClient.get()
    .uri("$BASE_PATH/reference/coords", offenderUuid)
    .retrieve()
    .bodyToMono(ReferencePhotoLocation::class.java)
    .block() ?: error("Photo API returned empty reference coordinates")

  override fun purge(offenderUuid: UUID): OffenderPhotoPurgeResult = webClient.delete()
    .uri(BASE_PATH, offenderUuid)
    .retrieve()
    .bodyToMono(OffenderPhotoPurgeResult::class.java)
    .block() ?: error("Photo API returned an empty purge result")

  companion object {
    private const val BASE_PATH = "/v1/offenders/by-uuid/{uuid}/photo"
  }
}

internal data class UploadHashRequest(val sha256: String)
internal data class PhotoUploadLocation(val url: URL, val contentType: String, val duration: String, val requiredHeaders: Map<String, String>)
internal data class PhotoReadLocation(val url: URL)
internal data class PhotoStatus(val uploaded: Boolean)
