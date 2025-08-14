package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.springframework.stereotype.Service

@Service
class OffenderEventLogService(
  private val offenderEventLogRepository: OffenderEventLogRepository,
) {
  // fun eventsForOffender(uuid: UUID, pageable: Pageable): Page<IOffenderEventLogDto> = offenderEventLogRepository.findAllByOffenderUuid(uuid, pageable).map { it.dto() }
}
