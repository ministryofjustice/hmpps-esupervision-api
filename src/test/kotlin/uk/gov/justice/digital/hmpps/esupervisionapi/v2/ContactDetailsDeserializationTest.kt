package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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
  fun `dateOfDeath deserializes from the NDelius json field`() {
    val json = """{"crn":"X123456","name":{"forename":"John","surname":"Doe"},"dateOfBirth":"1980-01-01","dateOfDeath":"2026-03-04"}"""

    val details = mapper.readValue<ContactDetails>(json)

    assertEquals(LocalDate.of(2026, 3, 4), details.dateOfDeath)
  }

  @Test
  fun `dateOfDeath is null when NDelius sends null or leaves it out`() {
    val withNull = """{"crn":"X123456","name":{"forename":"John","surname":"Doe"},"dateOfBirth":"1980-01-01","dateOfDeath":null}"""
    val without = """{"crn":"X123456","name":{"forename":"John","surname":"Doe"},"dateOfBirth":"1980-01-01"}"""

    assertNull(mapper.readValue<ContactDetails>(withNull).dateOfDeath)
    assertNull(mapper.readValue<ContactDetails>(without).dateOfDeath)
  }
}
