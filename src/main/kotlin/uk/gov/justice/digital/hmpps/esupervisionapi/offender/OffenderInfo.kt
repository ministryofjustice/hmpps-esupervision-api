package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.ExternalUserId
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.LocalDateDeserializer
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

enum class CheckinInterval(val duration: Duration) : Comparable<CheckinInterval> {
  WEEKLY(Duration.ofDays(7)),
  TWO_WEEKS(Duration.ofDays(14)),
  FOUR_WEEKS(Duration.ofDays(28)),
  EIGHT_WEEKS(Duration.ofDays(56)),
  ;

  val days: Long = duration.toDays()

  companion object {
    fun fromDuration(duration: Duration): CheckinInterval = when (duration.toDays()) {
      7L -> WEEKLY
      14L -> TWO_WEEKS
      28L -> FOUR_WEEKS
      56L -> EIGHT_WEEKS
      else -> throw IllegalArgumentException("Invalid checkin interval duration: $duration")
    }
  }
}

/**
 * Minimal information required to start offender onboarding.
 */
data class OffenderInfo(
  val setupUuid: UUID,

  @field:NotBlank
  val practitionerId: ExternalUserId,

  @field:Size(min = 2)
  val firstName: String,

  @field:Size(min = 2)
  val lastName: String,

  @JsonDeserialize(using = LocalDateDeserializer::class) val dateOfBirth: LocalDate,
  @field:Email
  val email: String? = null,

  val phoneNumber: String? = null,

  @JsonDeserialize(using = LocalDateDeserializer::class)
  val firstCheckinDate: LocalDate,

  val checkinInterval: CheckinInterval,
)
