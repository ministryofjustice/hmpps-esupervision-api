package uk.gov.justice.digital.hmpps.esupervisionapi.v2

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEvent
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventPublisher
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.events.DomainEventType
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class DomainEventServiceTest {

  private val eventPublisher: DomainEventPublisher = mock()
  private val clock = Clock.fixed(Instant.parse("2025-06-15T10:00:00Z"), ZoneId.of("UTC"))
  private val apiBaseUrl = "https://esupervision-api.example.com"

  private lateinit var service: DomainEventService

  @BeforeEach
  fun setUp() {
    service = DomainEventService(eventPublisher, clock, apiBaseUrl)
  }

  @Test
  fun `publishDomainEvent - constructs correct detail URL for setup completed`() {
    val uuid = UUID.randomUUID()

    service.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      uuid = uuid,
      crn = "X123456",
      description = "Test description",
    )

    val captor = argumentCaptor<DomainEvent>()
    verify(eventPublisher).publish(captor.capture())

    val event = captor.firstValue
    assertThat(event.detailUrl).isEqualTo("$apiBaseUrl/v2/events/setup-completed/$uuid")
    assertThat(event.eventType).isEqualTo("esupervision.setup.completed")
  }

  @Test
  fun `publishDomainEvent - constructs correct detail URL for checkin submitted`() {
    val uuid = UUID.randomUUID()

    service.publishDomainEvent(
      eventType = DomainEventType.V2_CHECKIN_SUBMITTED,
      uuid = uuid,
      crn = "X123456",
      description = "Test description",
    )

    val captor = argumentCaptor<DomainEvent>()
    verify(eventPublisher).publish(captor.capture())

    val event = captor.firstValue
    assertThat(event.detailUrl).isEqualTo("$apiBaseUrl/v2/events/checkin-submitted/$uuid")
    assertThat(event.eventType).isEqualTo("esupervision.check-in.received")
  }

  @Test
  fun `publishDomainEvent - includes CRN in person reference`() {
    val uuid = UUID.randomUUID()

    service.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      uuid = uuid,
      crn = "X123456",
      description = "Test description",
    )

    val captor = argumentCaptor<DomainEvent>()
    verify(eventPublisher).publish(captor.capture())

    val event = captor.firstValue
    assertThat(event.personReference?.identifiers).hasSize(1)
    assertThat(event.personReference?.identifiers?.first()?.type).isEqualTo("CRN")
    assertThat(event.personReference?.identifiers?.first()?.value).isEqualTo("X123456")
  }

  @Test
  fun `publishDomainEvent - sets occurredAt from clock`() {
    val uuid = UUID.randomUUID()

    service.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      uuid = uuid,
      crn = "X123456",
      description = "Test description",
    )

    val captor = argumentCaptor<DomainEvent>()
    verify(eventPublisher).publish(captor.capture())

    val event = captor.firstValue
    assertThat(event.occurredAt.toInstant()).isEqualTo(Instant.parse("2025-06-15T10:00:00Z"))
  }

  @Test
  fun `publishDomainEvent - includes description`() {
    val uuid = UUID.randomUUID()

    service.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      uuid = uuid,
      crn = "X123456",
      description = "Practitioner completed setup for offender X123456",
    )

    val captor = argumentCaptor<DomainEvent>()
    verify(eventPublisher).publish(captor.capture())

    val event = captor.firstValue
    assertThat(event.description).isEqualTo("Practitioner completed setup for offender X123456")
  }

  @Test
  fun `publishDomainEvent - handles publisher exception gracefully`() {
    val uuid = UUID.randomUUID()
    doThrow(RuntimeException("SNS error")).`when`(eventPublisher).publish(org.mockito.kotlin.any())

    service.publishDomainEvent(
      eventType = DomainEventType.V2_SETUP_COMPLETED,
      uuid = uuid,
      crn = "X123456",
      description = "Test description",
    )

    verify(eventPublisher).publish(org.mockito.kotlin.any())
  }
}
