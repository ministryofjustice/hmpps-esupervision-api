package uk.gov.justice.digital.hmpps.esupervisionapi.practitioner

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class PractitionerService(private val practitionerRepository: PractitionerRepository) {

  @Transactional
  fun createPractitioner(practitioner: Practitioner): PractitionerDto = practitionerRepository.save(practitioner).dto()

  @Transactional(readOnly = true)
  fun getAllPractitioners(): List<Practitioner> = practitionerRepository.findAll()

  @Transactional(readOnly = true)
  fun getPractitionerById(id: Long): Practitioner? = practitionerRepository.findById(id).orElse(null)

  @Transactional
  fun getPractitionerByUuid(uuid: String): Practitioner? {
    var found: Practitioner? = null
    try {
      val practitioner = practitionerRepository.findByUuid(uuid)
      if (practitioner.isPresent) {
        found = practitioner.get()
      }
    } catch (ex: RuntimeException) {
      System.out.printf(ex.message ?: "not found")
    }
    return found
  }

//  @Transactional
//  fun updatePractitioner(id: Long, updatedPractitioner: Practitioner): Practitioner? {
//    return practitionerRepository.findById(id).map { existingPractitioner ->
//      val practitionerToUpdate = existingPractitioner.copy(
//        firstName = updatedPractitioner.firstName,
//        lastName = updatedPractitioner.lastName,
//      )
//      practitionerRepository.save(practitionerToUpdate)
//    }.orElse(null)
//  }

  @Transactional
  fun deletePractitioner(id: Long) {
    practitionerRepository.deleteById(id)
  }
}
