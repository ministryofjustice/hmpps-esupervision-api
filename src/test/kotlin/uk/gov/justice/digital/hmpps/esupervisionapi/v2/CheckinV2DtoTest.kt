package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.AutomatedIdVerificationResult
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.domain.LivenessResult
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class CheckinV2DtoTest {

  private fun checkin(
    autoIdCheck: AutomatedIdVerificationResult? = null,
    livenessResult: LivenessResult? = null,
  ) = CheckinV2Dto(
    uuid = UUID.randomUUID(),
    crn = "X123456",
    status = CheckinV2Status.SUBMITTED,
    dueDate = LocalDate.now(),
    createdAt = Instant.now(),
    createdBy = "user-1",
    autoIdCheck = autoIdCheck,
    livenessResult = livenessResult,
    checkinLogs = CheckinLogsV2Dto(hint = CheckinLogsHintV2.ALL, logs = emptyList()),
  )

  @Test
  fun `idMatched is true when face match passes and liveness is null (historical record)`() {
    assertThat(checkin(autoIdCheck = AutomatedIdVerificationResult.MATCH).idMatched).isTrue()
  }

  @Test
  fun `idMatched is true when face match passes and liveness is LIVE`() {
    val dto = checkin(
      autoIdCheck = AutomatedIdVerificationResult.MATCH,
      livenessResult = LivenessResult.LIVE,
    )
    assertThat(dto.idMatched).isTrue()
  }

  @Test
  fun `idMatched is false when face match passes but liveness is NOT_LIVE`() {
    val dto = checkin(
      autoIdCheck = AutomatedIdVerificationResult.MATCH,
      livenessResult = LivenessResult.NOT_LIVE,
    )
    assertThat(dto.idMatched).isFalse()
  }

  @Test
  fun `idMatched is false when face match passes but liveness errored`() {
    val dto = checkin(
      autoIdCheck = AutomatedIdVerificationResult.MATCH,
      livenessResult = LivenessResult.ERROR,
    )
    assertThat(dto.idMatched).isFalse()
  }

  @Test
  fun `idMatched is false when liveness passes but face match did not`() {
    val dto = checkin(
      autoIdCheck = AutomatedIdVerificationResult.NO_MATCH,
      livenessResult = LivenessResult.LIVE,
    )
    assertThat(dto.idMatched).isFalse()
  }

  @Test
  fun `idMatched is false when face match is NO_MATCH and liveness is null`() {
    assertThat(checkin(autoIdCheck = AutomatedIdVerificationResult.NO_MATCH).idMatched).isFalse()
  }

  @Test
  fun `idMatched is false when face match is null`() {
    assertThat(checkin().idMatched).isFalse()
  }
}
