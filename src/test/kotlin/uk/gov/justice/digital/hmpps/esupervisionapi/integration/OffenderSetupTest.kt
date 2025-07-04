package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.reset
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupService
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.net.URI
import java.time.LocalDate
import java.util.UUID

@Import(MockS3Config::class)
class OffenderSetupTest : IntegrationTestBase() {

  @Autowired private lateinit var offenderSetupService: OffenderSetupService
  private lateinit var practitionerRoleAuthHeaders: (HttpHeaders) -> Unit

  @Autowired lateinit var s3UploadService: S3UploadService

  @BeforeEach
  internal fun setUp() {
    practitionerRoleAuthHeaders = setAuthorisation(roles = listOf("ESUPERVISION__ESUPERVISION_UI"))
    practitionerService.createPractitioner(Practitioner.create("Alice"))
    practitionerService.createPractitioner(Practitioner.create("Bob"))

    reset(s3UploadService)
  }

  @AfterEach
  internal fun tearDown() {
    offenderSetupRepository.deleteAll()
    offenderRepository.deleteAll()
    practitionerRepository.deleteAll()
  }

  /**
   * We attempt to add an offender to the system without (the happy path).
   */
  @Test
  fun `successfully add an offender to the system`() {
    val offenderInfo = createOffenderInfo()
    val setup = setupStartRequest(offenderInfo)
      .exchange()
      .expectStatus().isOk
      .expectBody(OffenderSetupDto::class.java)
      .returnResult()

    mockPhotoUpload(setup.responseBody!!)

    val setupCompletion = setupCompleteRequest(setup)
      .exchange()
      .expectStatus().isOk
      .expectBody(OffenderDto::class.java)
      .returnResult()

    Assertions.assertEquals(
      URI("https://the-bucket/offender-1").toURL(),
      setupCompletion.responseBody!!.photoUrl,
    )
  }

  @Test
  fun `adding an offender fails in various ways`() {
    val offenderInfo = createOffenderInfo()
    val setupOK = setupStartRequest(offenderInfo)
      .exchange()
      .expectStatus().isOk

    var setupAgain = setupStartRequest(offenderInfo)
      .exchange()
      .expectStatus().is4xxClientError

    var offenders = offenderRepository.findAll().toList()
    Assertions.assertEquals(
      1,
      offenders.size,
      "Offender records should not be added when /offender_setup/start fails",
    )

    val newSetup = offenderInfo.copy(setupUuid = UUID.randomUUID())
    setupAgain = setupStartRequest(newSetup)
      .exchange()
      .expectStatus().is4xxClientError

    offenders = offenderRepository.findAll().toList()
    Assertions.assertEquals(
      1,
      offenders.size,
      "Offender records should not be added when /offender_setup/start fails",
    )

    setupAgain = setupStartRequest(
      offenderInfo.copy(
        setupUuid = UUID.randomUUID(),
        email = "bob.x@example.com",
      ),
    )
      .exchange()
      .expectStatus().isOk

    offenders = offenderRepository.findAll().toList()
    Assertions.assertEquals(2, offenders.size)
  }

  /**
   * - We start an offender setup process then terminate it
   * - Then we start setup process again with same information (except setup uuid)
   * - Then we complete the second setup attempt
   */
  @Test
  fun `terminating an offender setup`() {
    val offenderInfo = createOffenderInfo()
    val setupOK = setupStartRequest(offenderInfo)
      .exchange()
      .expectStatus().isOk

    val setupTermination = webTestClient.post()
      .uri("/offender_setup/${offenderInfo.setupUuid}/terminate")
      .headers(practitionerRoleAuthHeaders)
      .exchange()
      .expectStatus().isOk

    val setupAgain = setupStartRequest(offenderInfo.copy(setupUuid = UUID.randomUUID()))
      .exchange()
      .expectStatus().isOk
      .expectBody(OffenderSetupDto::class.java)
      .returnResult()

    mockPhotoUpload(setupAgain.responseBody!!)

    setupCompleteRequest(setupAgain)
      .exchange()
      .expectStatus().isOk
  }

  private fun setupCompleteRequest(setup: EntityExchangeResult<OffenderSetupDto>): WebTestClient.RequestBodySpec = webTestClient.post()
    .uri("/offender_setup/${setup.responseBody!!.uuid}/complete")
    .headers(practitionerRoleAuthHeaders)

  private fun setupStartRequest(offenderInfo: OffenderInfo): WebTestClient.RequestHeadersSpec<*> = webTestClient.post()
    .uri("/offender_setup")
    .contentType(MediaType.APPLICATION_JSON)
    .headers(practitionerRoleAuthHeaders)
    .bodyValue(offenderInfo)

  fun mockPhotoUpload(setup: OffenderSetupDto) {
    val setupEntity = offenderSetupRepository.findByUuid(setup.uuid).get()
    val offender = offenderRepository.findByUuid(setupEntity.offender.uuid).get()
    whenever(s3UploadService.isSetupPhotoUploaded(setupEntity))
      .thenReturn(true)
    whenever(s3UploadService.getOffenderPhoto(offender))
      .thenReturn(URI("https://the-bucket/offender-1").toURL())
  }
}

fun createOffenderInfo() = OffenderInfo(
  UUID.randomUUID(),
  "alice",
  "Bob",
  "Offerman",
  LocalDate.of(1970, 1, 1),
  "bob@example.com",
)

/**
 * Creates an example practitioner instance. `name` should be unique.
 */
fun Practitioner.Companion.create(name: String): Practitioner = Practitioner(
  name.lowercase(),
  name,
  "Practitioner",
  "${name.lowercase()}@example.com",
  roles = listOf(),
)
