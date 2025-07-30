package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class OffenderEventLogService(
  private val offenderEventLogRepository: OffenderEventLogRepository,
) {
  fun eventsForOffender(uuid: UUID, pageable: Pageable): Page<OffenderEventLogDto> = offenderEventLogRepository.findAllByOffenderUuid(uuid, pageable).map { it.dto() }
}
