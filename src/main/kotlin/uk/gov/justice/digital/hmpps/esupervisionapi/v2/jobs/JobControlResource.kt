package uk.gov.justice.digital.hmpps.esupervisionapi.v2.jobs

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/v2/jobs")
@Profile("local")
@Tag(name = "Job Control", description = "Endpoints to manually trigger jobs (Local only)")
class JobControlResource(
  private val checkinCreationJob: CheckinCreationJob,
  private val checkinExpiryJob: CheckinExpiryJob,
  private val customQuestionsReminderJob: CustomQuestionsReminderJob,
  private val checkinNoteResendJob: CheckinNoteResendJob,
) {

  @PostMapping("/checkin-creation")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Trigger Checkin Creation Job")
  fun triggerCheckinCreation() {
    checkinCreationJob.process()
  }

  @PostMapping("/checkin-expiry")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Trigger Checkin Expiry Job")
  fun triggerCheckinExpiry() {
    checkinExpiryJob.process()
  }

  @PostMapping("/question-reminders")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Trigger Custom Question Reminders Job")
  fun triggerQuestionReminders() {
    customQuestionsReminderJob.process()
  }

  @PostMapping("/checkin-note-resend")
  @PreAuthorize("permitAll()")
  @Operation(summary = "Trigger Checkin Note Resend Job")
  fun triggerCheckinNoteResend() {
    checkinNoteResendJob.process()
  }
}
