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
import org.springframework.test.web.reactive.server.WebTestClient
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinSubmission
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupDto
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupService
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinReviewRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.UploadLocationResponse
import java.net.URI
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

@Import(MockS3Config::class)
class OffenderCheckinTest : IntegrationTestBase() {

  private lateinit var practitionerRoleAuthHeaders: (HttpHeaders) -> Unit

  @Autowired private lateinit var offenderSetupService: OffenderSetupService

  @Autowired lateinit var s3UploadService: S3UploadService

  @Autowired lateinit var offenderCheckinRepository: OffenderCheckinRepository

  /**
   * Used to setup an offender for tests
   */
  val offenderInfo = OffenderInfo(
    setupUuid = UUID.randomUUID(),
    firstName = "Jim",
    lastName = "Smith",
    dateOfBirth = LocalDate.of(1980, 1, 1),
    email = "jim@example.com",
    practitionerId = "alice",
  )
  var offender: OffenderDto? = null

  @BeforeEach
  fun setup() {
    practitionerRoleAuthHeaders = setAuthorisation(roles = listOf("ESUPERVISION__ESUPERVISION_UI"))
    practitionerService.createPractitioner(Practitioner.create("Alice"))
    practitionerService.createPractitioner(Practitioner.create("Bob"))

    reset(s3UploadService)

    val setup = offenderSetupService.startOffenderSetup(offenderInfo)
    mockSetupPhotoUpload(setup)
    offender = offenderSetupService.completeOffenderSetup(setup.uuid)
  }

  @AfterEach
  fun tearDown() {
    offenderSetupRepository.deleteAll()
    offenderCheckinRepository.deleteAll()
    offenderRepository.deleteAll()
    practitionerRepository.deleteAll()
  }

  @Test
  fun `successful checkin flow`() {
    val (offender, checkinRequest) = checkinRequestDto()
    val createCheckin = createCheckinRequest(checkinRequest)
      .exchange()
      .expectStatus().isOk
      .expectBody(OffenderCheckinDto::class.java)
      .returnResult().responseBody!!

    Assertions.assertEquals(CheckinStatus.CREATED, createCheckin.status)

    mockCheckinVideoUploadUrl(createCheckin)
    val numSnapshots = 2
    mockCheckinSnapshotUrls(createCheckin, numSnapshots)

    // request video URL upload
    webTestClient.post()
      .uri("/offender_checkins/${createCheckin.uuid}/upload_location?content-type=video/mpg4")
      .headers(practitionerRoleAuthHeaders)
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk

    // request rekognition reference & snapshot upload locations
    val imageUploadLocations = webTestClient.post()
      .uri("/offender_checkins/${createCheckin.uuid}/upload_location?content-type=image/jpeg&num-snapshots=$numSnapshots")
      .headers(practitionerRoleAuthHeaders)
      .accept(MediaType.APPLICATION_JSON)
      .exchange()
      .expectStatus().isOk
      .expectBody(UploadLocationResponse::class.java)
      .returnResult().responseBody!!

    Assertions.assertNotNull(imageUploadLocations.locations)
    Assertions.assertEquals(2, imageUploadLocations.locations?.size, "1 Reference upload URL + 1 snapshot upload URL")

    mockCheckinVideoUpload(createCheckin, UploadStatus.UPLOADED)

    val submission = OffenderCheckinSubmission(
      offender = offender.uuid,
      answers = "Yes, it is",
    )
    val submitCheckin = submitCheckinRequest(createCheckin, submission)
      .exchange()
      .expectStatus().isOk
      .expectBody(OffenderCheckinDto::class.java)
      .returnResult()

    val submittedCheckin = offenderCheckinRepository.findByUuid(submitCheckin.responseBody!!.uuid).get()
    Assertions.assertNotNull(submittedCheckin.status == submitCheckin.responseBody!!.status)
    Assertions.assertEquals(CheckinStatus.SUBMITTED, submitCheckin.responseBody!!.status)
    Assertions.assertNull(submitCheckin.responseBody!!.autoIdCheck)

    val autoIdCheck = webTestClient.post()
      .uri("/offender_checkins/${createCheckin.uuid}/auto_id_check?result=MATCH")
      .headers(practitionerRoleAuthHeaders)
      .exchange()
      .expectStatus().isOk

    val reviewCheckin = webTestClient.post()
      .uri("/offender_checkins/${createCheckin.uuid}/review")
      .contentType(MediaType.APPLICATION_JSON)
      .headers(practitionerRoleAuthHeaders)
      .bodyValue(
        CheckinReviewRequest(
          practitioner = "alice",
          manualIdCheck = ManualIdVerificationResult.MATCH,
        ),
      )
      .exchange()
      .expectStatus().isOk
      .expectBody(OffenderCheckinDto::class.java)
      .returnResult()

    val reviewedCheckin = offenderCheckinRepository.findByUuid(reviewCheckin.responseBody!!.uuid).get()
    Assertions.assertNotNull(reviewedCheckin.status == reviewCheckin.responseBody!!.status)
    Assertions.assertEquals(CheckinStatus.REVIEWED, reviewedCheckin.status)
    Assertions.assertEquals(AutomatedIdVerificationResult.MATCH, reviewedCheckin.autoIdCheck)
    Assertions.assertEquals(ManualIdVerificationResult.MATCH, reviewedCheckin.manualIdCheck)
  }

  @Test
  fun `a checkin can be submitted only once`() {
    val (offender, checkinRequest) = checkinRequestDto()
    val checkinDto = createCheckinRequest(checkinRequest)
      .exchange()
      .expectStatus().isOk
      .expectBody(OffenderCheckinDto::class.java)
      .returnResult().responseBody!!

    val submission = OffenderCheckinSubmission(
      offender = offender.uuid,
      answers = "Yes, it is",
    )

    // we should get a 4xx error because the video was not uploaded
    submitCheckinRequest(checkinDto, submission)
      .exchange()
      .expectStatus().is4xxClientError

    // video uploaded, submission goes through
    mockCheckinVideoUpload(checkinDto, UploadStatus.UPLOADED)
    submitCheckinRequest(checkinDto, submission)
      .exchange()
      .expectStatus().isOk

    // another submission for the checkin disallowed
    submitCheckinRequest(checkinDto, submission)
      .exchange()
      .expectStatus().is4xxClientError
  }

  private fun checkinRequestDto(): Pair<OffenderDto, CreateCheckinRequest> {
    val offender = offender!!
    val checkinRequest = CreateCheckinRequest(
      "alice",
      offender = offender.uuid,
      "Is everything ok?",
      dueDate = LocalDate.now().plusDays(2),
    )
    return Pair(offender, checkinRequest)
  }

  private fun submitCheckinRequest(
    checkin: OffenderCheckinDto,
    submission: OffenderCheckinSubmission,
  ): WebTestClient.RequestHeadersSpec<*> = webTestClient.post()
    .uri("/offender_checkins/${checkin.uuid}/submit")
    .contentType(MediaType.APPLICATION_JSON)
    .headers(practitionerRoleAuthHeaders)
    .bodyValue(submission)

  private fun createCheckinRequest(checkinRequest: CreateCheckinRequest): WebTestClient.RequestHeadersSpec<*> = webTestClient.post()
    .uri("/offender_checkins")
    .contentType(MediaType.APPLICATION_JSON)
    .headers(practitionerRoleAuthHeaders)
    .bodyValue(checkinRequest)

  /**
   * What should the return value of  the mocked `is*Uploaded` method be
   */
  internal enum class UploadStatus(val value: Boolean) {
    UPLOADED(true),
    NOT_UPLOADED(false),
  }

  internal fun mockCheckinVideoUpload(checkin: OffenderCheckinDto, uploadStatus: UploadStatus) {
    val checkinEntity = offenderCheckinRepository.findByUuid(checkin.uuid).get()
    whenever(s3UploadService.isCheckinVideoUploaded(checkinEntity))
      .thenReturn(uploadStatus.value)
    whenever(s3UploadService.getCheckinVideo(checkinEntity))
      .thenReturn(
        when (uploadStatus) {
          UploadStatus.UPLOADED -> null
          UploadStatus.NOT_UPLOADED -> URI("https://the-bucket/checkin-${checkin.uuid}").toURL()
        },
      )
  }

  fun mockSetupPhotoUpload(setup: OffenderSetupDto) {
    val setupEntity = offenderSetupRepository.findByUuid(setup.uuid).get()
    val offender = offenderRepository.findByUuid(setupEntity.offender.uuid).get()
    whenever(s3UploadService.isSetupPhotoUploaded(setupEntity))
      .thenReturn(true)
    whenever(s3UploadService.getOffenderPhoto(offender))
      .thenReturn(URI("https://the-bucket/offender-1").toURL())
  }

  fun mockCheckinVideoUploadUrl(checkin: OffenderCheckinDto) {
    val checkinEntity = offenderCheckinRepository.findByUuid(checkin.uuid).get()
    whenever(s3UploadService.generatePresignedUploadUrl(checkinEntity, "video/mpg4", Duration.ofMinutes(10)))
      .thenReturn(URI("https://the-bucket/video-1").toURL())
  }

  fun mockCheckinSnapshotUrls(checkin: OffenderCheckinDto, numSnapshots: Int) {
    assert(numSnapshots > 0)
    val checkinEntity = offenderCheckinRepository.findByUuid(checkin.uuid).get()
    for (index in 0..<numSnapshots) {
      whenever(
        s3UploadService
          .generatePresignedUploadUrl(checkinEntity, "image/jpeg", index, Duration.ofMinutes(10)),
      )
        .thenReturn(URI("https://the-bucket/pic/$index").toURL())
    }
  }
}
