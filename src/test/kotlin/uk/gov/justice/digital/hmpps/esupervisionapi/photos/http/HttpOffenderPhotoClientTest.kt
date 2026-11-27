package uk.gov.justice.digital.hmpps.esupervisionapi.photos.http

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.delete
import com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.equalToJson
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.OffenderPhotoPurgeResult
import uk.gov.justice.digital.hmpps.esupervisionapi.photos.ReferencePhotoLocation
import java.time.Duration
import java.util.UUID

class HttpOffenderPhotoClientTest {
  private val upstream = WireMockServer(options().dynamicPort()).apply { start() }
  private val client = HttpOffenderPhotoClient(WebClient.builder().baseUrl(upstream.baseUrl()).build())
  private val uuid = UUID.randomUUID()
  private val basePath = "/offenders/by-uuid/$uuid/photo"
  private val photoUrl = "https://image-bucket.example/setup-$uuid"

  @AfterEach
  fun stopUpstream() = upstream.stop()

  @Test
  fun `upload sends content type and base64 checksum and preserves required headers`() {
    val path = "$basePath/reference/upload-location"
    val checksum = "a+b/c=="
    upstream.stubFor(
      post(urlPathEqualTo(path)).willReturn(
        aResponse().withHeader("Content-Type", "application/json")
          .withBody("""{"url":"$photoUrl","contentType":"image/png","duration":"PT5M","requiredHeaders":{"x-amz-checksum-sha256":"$checksum","Content-Type":"image/png"}}"""),
      ),
    )

    val result = client.presignUpload(uuid, "image/png", Duration.ofMinutes(10), checksum)

    assertThat(result.url.toString()).isEqualTo(photoUrl)
    assertThat(result.requiredHeaders).containsExactlyInAnyOrderEntriesOf(mapOf("x-amz-checksum-sha256" to checksum, "Content-Type" to "image/png"))
    upstream.verify(
      postRequestedFor(urlPathEqualTo(path))
        .withQueryParam("content-type", equalTo("image/png"))
        .withHeader("Content-Type", equalTo("application/json"))
        .withRequestBody(equalToJson("""{"sha256":"$checksum"}""")),
    )
  }

  @Test
  fun `reads photo URL and coordinates from their reference endpoints`() {
    upstream.stubFor(get(urlEqualTo("$basePath/reference/url")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"url":"$photoUrl"}""")))
    upstream.stubFor(get(urlEqualTo("$basePath/reference/coords")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"bucket":"image-uploads","key":"setup-$uuid"}""")))

    assertThat(client.presignRead(uuid).toString()).isEqualTo(photoUrl)
    assertThat(client.referenceLocation(uuid)).isEqualTo(ReferencePhotoLocation("image-uploads", "setup-$uuid"))
    upstream.verify(getRequestedFor(urlEqualTo("$basePath/reference/url")))
    upstream.verify(getRequestedFor(urlEqualTo("$basePath/reference/coords")))
  }

  @ParameterizedTest
  @ValueSource(booleans = [true, false])
  fun `status maps both uploaded and absent photos`(uploaded: Boolean) {
    val path = "$basePath/reference/status"
    upstream.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"uploaded":$uploaded}""")))

    assertThat(client.isUploaded(uuid)).isEqualTo(uploaded)
    upstream.verify(getRequestedFor(urlEqualTo(path)))
  }

  @Test
  fun `purge deletes the base photo endpoint and preserves partial failure counts`() {
    upstream.stubFor(delete(urlEqualTo(basePath)).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""{"keysDeleted":3,"keysFailed":1}""")))

    assertThat(client.purge(uuid)).isEqualTo(OffenderPhotoPurgeResult(3, 1))
    upstream.verify(deleteRequestedFor(urlEqualTo(basePath)))
  }

  @ParameterizedTest
  @ValueSource(ints = [401, 403, 404, 500])
  fun `HTTP failures surface rather than becoming an absent photo`(status: Int) {
    upstream.stubFor(get(urlEqualTo("$basePath/reference/status")).willReturn(aResponse().withStatus(status)))

    assertThat(assertThrows<WebClientResponseException> { client.isUploaded(uuid) }.statusCode.value()).isEqualTo(status)
  }

  @Test
  fun `an empty successful response is not treated as an absent photo`() {
    upstream.stubFor(get(urlEqualTo("$basePath/reference/status")).willReturn(aResponse().withStatus(204)))

    assertThat(assertThrows<IllegalStateException> { client.isUploaded(uuid) }).hasMessage("Photo API returned an empty status")
  }
}
