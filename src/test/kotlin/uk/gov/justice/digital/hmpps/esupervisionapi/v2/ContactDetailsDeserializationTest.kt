package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Locks in the JSON contract for the NDelius `contactSuspended` flag - the trigger for the
 * "in reset" deactivation. If the field name drifts, contactSuspended would silently default to
 * false in production (feature no-op) while code that constructs the DTO directly still passes.
 */
class ContactDetailsDeserializationTest {

  private val mapper = jacksonObjectMapper()

  @BeforeEach
  fun setup() {
    mapper.registerModule(JavaTimeModule())
  }

  @Test
  fun `contactSuspended deserializes from the NDelius json field`() {
    val json = """{"crn":"X123456","name":{"forename":"John","surname":"Doe"},"contactSuspended":true,"dateOfBirth":"1980-01-01"}"""

    val details = mapper.readValue<ContactDetails>(json)

    assertTrue(details.contactSuspended)
  }

  @Test
  fun `contactSuspended defaults to false when absent from the json`() {
    val json = """{"crn":"X123456","name":{"forename":"John","surname":"Doe"},"dateOfBirth":"1980-01-01"}"""

    val details = mapper.readValue<ContactDetails>(json)

    assertFalse(details.contactSuspended)
  }
}
