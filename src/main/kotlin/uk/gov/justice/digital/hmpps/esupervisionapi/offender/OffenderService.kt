package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.CollectionDto
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import java.util.UUID

@Service
class OffenderService(private val offenderRepository: OffenderRepository) {

  fun getOffenders(pageable: Pageable): CollectionDto<OffenderDto> {
    val page = offenderRepository.findAll(pageable)
    val offenders = page.content.map { it.dto() }
    return CollectionDto(page.pageable.toPagination(), offenders)
  }

  fun deleteOffender(uuid: UUID): DeleteResult {
    LOG.info("Attempting to delete offender: $uuid")
    val offenderFound = offenderRepository.findByUuid(uuid)
    var result = DeleteResult.NO_RECORD
    if (offenderFound.isPresent) {
      val offender = offenderFound.get()
      if (offender.status == OffenderStatus.VERIFIED) {
        return DeleteResult.RECORD_IN_USE
      }
      offenderRepository.delete(offender)
      result = DeleteResult.DELETED
    }
    return result
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

enum class DeleteResult {
  DELETED,
  NO_RECORD,
  RECORD_IN_USE,
}
