package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.time.Instant
import java.util.Optional
import java.util.UUID

@Service
class OffenderCheckinService(
  private val checkinRepository: OffenderCheckinRepository,
  private val offenderRepository: OffenderRepository,
  private val s3UploadService: S3UploadService,
) {

  fun getCheckin(uuid: UUID): Optional<OffenderCheckinDto> {
    val checkin = checkinRepository.findByUuid(uuid)
    return checkin.map { it.dto() }
  }

  fun submitCheckin(checkinInput: OffenderCheckinInput) {
    val offender = offenderRepository.findByUuid(checkinInput.offenderUuid)
    val checkin = checkinRepository.findByUuid(checkinInput.offenderUuid)

    // s3UploadService.is

    if (offender.isPresent && checkin.isPresent) {
      val checkin = checkin.get()
      checkin.submittedOn = Instant.now()
      checkin.answers = checkinInput.answers
      checkin.status = CheckinStatus.SUBMITTED
    }

    throw RuntimeException("Missing entities: Offender entity: ${offender.isEmpty}, Checkin entity: ${checkin.isEmpty}")
  }
}
