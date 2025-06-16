package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.Pagination
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.toPagination
import java.util.UUID

data class OffendersDto(
  val pagination: Pagination,
  val content: List<OffenderDto>,
)

@Service
class OffenderService(private val offenderRepository: OffenderRepository) {

  fun getOffenders(pageable: Pageable): OffendersDto {
    val page = offenderRepository.findAll(pageable)
    val offenders = page.content.map { it.dto() }
    return OffendersDto(pagination = page.pageable.toPagination(), content = offenders)
  }

//  fun updateOffender(id: Long, updatedOffender: Offender): Offender? {
//    return offenderRepository.findById(id).map { existingOffender ->
//      val offenderToUpdate = existingOffender.copy(
//        firstName = updatedOffender.firstName,
//        lastName = updatedOffender.lastName,
//      )
//      offenderRepository.save(offenderToUpdate)
//    }.orElse(null)
//  }

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
