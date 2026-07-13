package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.module.kotlin.jacksonObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * Locks in the JSON contract for the NDelius `contactSuspended` flag - the trigger for the
 * "in reset" deactivation. If the field name drifts, contactSuspended would silently default to
 * false in production (feature no-op) while code that constructs the DTO directly still passes.
 */
class ContactDetailsDeserializationTest {

  private val mapper = jacksonObjectMapper()

  @Test
  fun `contactSuspended deserializes from the NDelius json field`() {
    val json = """{"crn":"X123456","name":{"forename":"John","surname":"Doe"},"contactSuspended":true}"""

    val details = mapper.readValue<ContactDetails>(json)

    assertTrue(details.contactSuspended)
  }

  @Test
  fun `contactSuspended defaults to false when absent from the json`() {
    val json = """{"crn":"X123456","name":{"forename":"John","surname":"Doe"}}"""

    val details = mapper.readValue<ContactDetails>(json)

    assertFalse(details.contactSuspended)
  }
}
