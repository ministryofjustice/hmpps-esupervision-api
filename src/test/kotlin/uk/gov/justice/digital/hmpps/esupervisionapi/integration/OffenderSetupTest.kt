package uk.gov.justice.digital.hmpps.esupervisionapi.integration

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.inOrder
import org.mockito.kotlin.reset
import org.mockito.kotlin.times
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.reactive.server.EntityExchangeResult
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationService
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.OffenderCheckinInviteMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.RegistrationConfirmationMessage
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.net.URI
import java.time.LocalDate
import java.util.UUID

@Import(MockS3Config::class)
class OffenderSetupTest : IntegrationTestBase() {

  @Autowired private lateinit var offenderSetupService: OffenderSetupService
  private lateinit var practitionerRoleAuthHeaders: (HttpHeaders) -> Unit

  @Autowired lateinit var s3UploadService: S3UploadService

  @Qualifier("mockNotificationService")
  @Autowired
  private lateinit var notificationService: NotificationService

  @BeforeEach
  internal fun setUp() {
    practitionerRoleAuthHeaders = setAuthorisation(roles = listOf("ESUPERVISION__ESUPERVISION_UI"))

    reset(notificationService)
    whenever(notificationService.sendMessage(any(), any(), any())).thenReturn(notifResults())

    reset(s3UploadService)
  }

  @AfterEach
  internal fun tearDown() {
    checkinRepository.deleteAll()
    offenderSetupRepository.deleteAll()
    offenderRepository.deleteAll()
  }

  /**
   * We attempt to add an offender to the system without (the happy path).
   */
  @Test
  fun `successfully add an offender to the system, first checkin today`() {
    val notifInOrder = inOrder(notificationService)

    `successful offender setup`(0)

    notifInOrder.verify(notificationService, times(1))
      .sendMessage(any<RegistrationConfirmationMessage>(), any(), any())
    notifInOrder.verify(notificationService, times(1))
      .sendMessage(any<OffenderCheckinInviteMessage>(), any(), any())
  }

  @Test
  fun `successfully add an offender to the system, first checkin in thet future`() {
    val notifInOrder = inOrder(notificationService)

    `successful offender setup`(1)

    notifInOrder.verify(notificationService, times(1))
      .sendMessage(any<RegistrationConfirmationMessage>(), any(), any())
    notifInOrder.verify(notificationService, times(0))
      .sendMessage(any<OffenderCheckinInviteMessage>(), any(), any())
  }

  fun `successful offender setup`(firstCheckinDaysOffset: Long = 0) {
    val offenderInfo = createOffenderInfo(
      firstCheckinDate = LocalDate.now().plusDays(firstCheckinDaysOffset),
      checkinInterval = CheckinInterval.WEEKLY,
    )
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
    val offenderInfo = createOffenderInfo(
      firstCheckinDate = LocalDate.now(),
      checkinInterval = CheckinInterval.WEEKLY,
    )
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

    val updatedOffenderInfo = offenderInfo.copy(setupUuid = UUID.randomUUID())
    setupAgain = setupStartRequest(updatedOffenderInfo)
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
        crn = UUID.randomUUID().toString().substring(0, 7),
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
    val offenderInfo = createOffenderInfo(
      firstCheckinDate = LocalDate.now(),
      checkinInterval = CheckinInterval.WEEKLY,
    )
    val setupOK = setupStartRequest(offenderInfo)
      .exchange()
      .expectStatus().isOk

    val setupTermination = webTestClient.post()
      .uri("/offender_setup/${offenderInfo.setupUuid}/terminate")
      .headers(practitionerRoleAuthHeaders)
      .exchange()
      .expectStatus().isOk

    val setupAgain = setupStartRequest(offenderInfo.copy(setupUuid = UUID.randomUUID(), crn = UUID.randomUUID().toString().substring(0, 7)))
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
