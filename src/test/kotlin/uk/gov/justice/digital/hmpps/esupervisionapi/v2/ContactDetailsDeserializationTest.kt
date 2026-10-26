package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.LocalDate

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

  @Test
  fun `events deserialize multiple sentences and their details`() {
    val json = """{"crn":"X123456","name":{"forename":"John","surname":"Doe"},"dateOfBirth":"1980-01-01","events":[{"number":2,"mainOffence":{"code":"04300","description":"Abstracting electricity - 04300"},"sentence":{"date":"2026-10-26","description":"Adult Custody < 12m","expectedEndDate":"2027-09-25","length":11,"lengthUnit":"Months"}},{"number":1,"mainOffence":{"code":"11800","description":"Night poaching - 11800"},"sentence":{"date":"2026-06-27","description":"SA2020 Community Order","expectedEndDate":"2026-12-26","length":6,"lengthUnit":"Months"}}]}"""

    val details = mapper.readValue<ContactDetails>(json)

    assertEquals(2, details.events.size)
    assertEquals(listOf(2L, 1L), details.events.map { it.number })
    assertEquals(LocalDate.of(2027, 9, 25), details.events[0].sentence?.expectedEndDate)
    assertEquals(11, details.events[0].sentence?.length)
    assertEquals("Months", details.events[0].sentence?.lengthUnit)
  }
}
