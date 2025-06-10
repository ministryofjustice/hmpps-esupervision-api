package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import java.util.UUID

@Service
class OffenderService(private val offenderRepository: OffenderRepository) {

  fun createOffender(offender: Offender): Offender {
    return offenderRepository.save(offender)
  }

  fun getAllOffenders(pageable: Pageable): Page<Offender> {
    return offenderRepository.findAll(pageable)
  }

  fun getOffenderById(id: Long): Offender? {
    return offenderRepository.findById(id).orElse(null)
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
    val offender = offenderRepository.findByUuid(uuid)
    var result = DeleteResult.NO_RECORD
    if (offender.isPresent) {
      offenderRepository.delete(offender.get())
      result = DeleteResult.DELETED
    }
    return result
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

enum class DeleteResult {
  DELETED, NO_RECORD
}