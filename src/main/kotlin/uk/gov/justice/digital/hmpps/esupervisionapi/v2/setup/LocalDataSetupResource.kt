package uk.gov.justice.digital.hmpps.esupervisionapi.v2.setup

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.data.domain.PageRequest
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.SingleNotificationContext
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinCreationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.CheckinInterval
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.DeactivateOffenderCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.ManualIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinService
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderCheckinSubmission
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderService
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.OffenderSetupService
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.UploadLocationTypes
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CheckinReviewRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CreateCheckinRequest
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocationInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.today
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs.MigrationEventReplayJob
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Paths
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@RestController
@RequestMapping("/v2/local-data")
@Profile("local")
@Tag(name = "Job Control", description = "Endpoints to manually trigger jobs (Local only)")
class LocalDataSetupResource(
  private val offenderSetupService: OffenderSetupService,
  private val offenderService: OffenderService,
  private val offenderCheckinService: OffenderCheckinService,
  private val migrationEventReplayJob: MigrationEventReplayJob,
  private val clock: Clock,
) {

  @PostMapping("/offender/setup")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Crate a few offenders")
  fun triggerCheckinCreation() {
    val crns = listOf("Q110001", "Q110002", "Q110003")

    var count = 0
    for (crn in crns) {
      ++count
      val info = offenderInfoTemplate.copy(
        setupUuid = UUID.randomUUID(),
        crn = crn,
        firstName = "Person$count",
        lastName = "Surname$count",
        email = "$crn@example.com",
        dateOfBirth = LocalDate.of(1990, 1, count),
      )
      val setup = offenderSetupService.startOffenderSetup(info)
      val locationInfo = offenderService.photoUploadLocation(setup.offender, "image/png")
      uploadMedia(locationInfo, "image/png")
      val offender = offenderSetupService.completeOffenderSetup(setup.uuid)
      LOGGER.info("Created offender: {}", offender.crn)
    }
  }

  @PostMapping("/offender/terminate")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Terminate offender")
  fun triggerOffenderTermination(@RequestParam uuid: UUID) {
    offenderService.cancelCheckins(uuid, DeactivateOffenderCheckinRequest("BOB", "no more checkins"))
  }

  @PostMapping("/checkin/create")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Trigger V2 Checkin Creation Job")
  fun triggerCheckinCreationV2() {
    val offenders = offenderService.getOffenders("BOB", null, null, PageRequest.of(0, 100))
    val checkins = mutableListOf<CheckinCreationInfo>()
    for (offender in offenders.content) {
      val checkin = offenderCheckinService.createCheckin(
        CreateCheckinRequest("BOB", offender.uuid, clock.today()),
        SingleNotificationContext.forCheckin(clock.today()),
      )
      checkins.add(checkin)
      LOGGER.info("Created checkin for {}: {}", offender.crn, checkin.checkin.uuid)
    }

    for (checkin in checkins) {
      val uploadLocations = offenderCheckinService.generateUploadLocations(
        checkin.checkin.uuid,
        UploadLocationTypes(reference = "image/png", video = "video/mp4", snapshots = listOf("image/png")),
        Duration.ofMinutes(2),
      )

      uploadMedia(uploadLocations.references!![0], uploadLocations.references[0].contentType)
      uploadMedia(uploadLocations.video!!, uploadLocations.video.contentType)
      uploadMedia(uploadLocations.snapshots!![0], uploadLocations.snapshots[0].contentType)

      val submitted = offenderCheckinService.submitCheckin(
        checkin.checkin.uuid,
        OffenderCheckinSubmission(
          offender = checkin.checkin.offender.uuid,
          survey = mapOf("version" to ("2025-07-10@pilot" as Object)),
        ),
      )
      LOGGER.debug("Submitted checkin {} for offender {}", submitted.uuid, submitted.offender)
      val reviewed = offenderCheckinService.reviewCheckin(
        checkin.checkin.uuid,
        CheckinReviewRequest("BOB", ManualIdVerificationResult.MATCH, null),
      )

      LOGGER.debug("Reviewed checkin {} for offender {}", reviewed.uuid, reviewed.offender)
    }
    LOGGER.info("Created, submitted and reviewed {} checkins", checkins.size)
  }

  @PostMapping("/event/replay")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Trigger NDelius event replay")
  fun triggerEventReplay() {
    migrationEventReplayJob.process()
  }

  private fun uploadMedia(locationInfo: LocationInfo, contentType: String) {
    val file = Paths.get("/Users/roland.sadowski/Desktop/correct.png").toFile()
    if (!file.exists()) throw IllegalStateException("Local file to upload not found: ${file.absolutePath}")

    val uri = locationInfo.url.toURI()
    val httpClient = HttpClient.newBuilder().build()
    val request = HttpRequest.newBuilder(uri)
      .PUT(HttpRequest.BodyPublishers.ofFile(file.toPath()))
      .header("Content-Type", contentType)
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
      throw IllegalStateException("File upload failed with status ${response.statusCode()}: ${response.body()}")
    }
  }

  companion object {
    private val LOGGER = org.slf4j.LoggerFactory.getLogger(LocalDataSetupResource::class.java)
  }
}

val offenderInfoTemplate = OffenderInfo(
  UUID.randomUUID(),
  "BOB", "Person1", "Surname1", "XXX",
  LocalDate.of(1990, 1, 1),
  "XXX@example.com", null, LocalDate.of(2026, 1, 28),
  CheckinInterval.WEEKLY, Instant.now(),
)
