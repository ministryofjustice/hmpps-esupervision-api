package uk.gov.justice.digital.hmpps.esupervisionapi.v2.notifications

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doAnswer
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.transaction.support.TransactionCallback
import org.springframework.transaction.support.TransactionTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.config.AppConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.MessageTemplateConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.MessageTypeTemplateConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.config.NotificationChannelsConfig
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.offenderTemplate
import uk.gov.justice.digital.hmpps.esupervisionapi.datagen.toEntity
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.Email
import uk.gov.justice.digital.hmpps.esupervisionapi.notifications.NotificationType
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.CheckinStatus
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.ContactDetails
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotification
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.GenericNotificationRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.OffenderCheckin
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.PractitionerDetails
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

class PractitionerNotificationTest {
  private val clock = Clock.fixed(Instant.parse("2026-11-26T12:00:00Z"), ZoneOffset.UTC)
  private val templates: MessageTypeTemplateConfig = mock()
  private val config: MessageTemplateConfig = mock()
  private val repository: GenericNotificationRepository = mock()
  private val transactions: TransactionTemplate = mock {
    on { execute<Any?>(any()) } doAnswer {
      (it.getArgument(0) as TransactionCallback<Any?>).doInTransaction(mock())
    }
  }
  private val persistence = NotificationPersistenceService(config, repository, transactions, clock, "test")
  private val gateway: NotifyGatewayService = mock()
  private val appConfig: AppConfig = mock()
  private val orchestrator = NotificationOrchestratorService(
    persistence, gateway, mock(), mock(), mock(), mock(), appConfig, clock, Duration.ofDays(3), mock(),
  )

  @Test
  fun `expiry email uses the new practitioner recipient name and attribution`() {
    val offender = offenderTemplate.copy(practitionerId = "NEW").toEntity()
    val checkin = OffenderCheckin(
      uuid = UUID.randomUUID(),
      offender = offender,
      status = CheckinStatus.EXPIRED,
      dueDate = LocalDate.now(clock).minusDays(4),
      createdAt = clock.instant(),
      createdBy = "SYSTEM",
    )
    val details = ContactDetails(
      crn = offender.crn,
      name = Name("Case", "Person"),
      dateOfBirth = LocalDate.of(1980, 1, 1),
      practitioner = PractitionerDetails(name = Name("New", "Practitioner"), username = "NEW", email = "new@example.com"),
    )
    whenever(config.channels).thenReturn(NotificationChannelsConfig(false, false, false, true))
    whenever(config.templatesFor(any<Email>())).thenReturn(templates)
    whenever(templates.getTemplate(NotificationType.PractitionerCheckinMissed)).thenReturn("template")
    whenever(appConfig.checkinReviewUrlV2(any(), any())).thenReturn(URI("https://example.com/review"))
    whenever(repository.saveAll(any<List<GenericNotification>>())).thenAnswer { it.getArgument<List<GenericNotification>>(0) }
    whenever(gateway.send(any(), any(), any(), any(), any())).thenReturn(UUID.randomUUID())

    orchestrator.sendCheckinExpiredNotifications(checkin, details)

    val records = argumentCaptor<List<GenericNotification>>()
    verify(repository).saveAll(records.capture())
    assertEquals("NEW", records.firstValue.single().practitionerId)
    val personalisation = argumentCaptor<Map<String, String>>()
    verify(gateway).send(eq("EMAIL"), eq("template"), eq("new@example.com"), personalisation.capture(), any())
    assertEquals("New", personalisation.firstValue["practitionerName"])
    verify(gateway, never()).send(any(), any(), eq("old@example.com"), any(), any())
  }

  @Test
  fun `unallocated placeholder is undeliverable even if it has an email`() {
    whenever(config.channels).thenReturn(NotificationChannelsConfig(false, false, false, true))
    val logger = LoggerFactory.getLogger(NotificationPersistenceService::class.java) as Logger
    val logs = ListAppender<ILoggingEvent>().apply { start() }
    logger.addAppender(logs)
    try {
      val notifications = persistence.buildPractitionerNotifications(
        offenderId = 1L,
        crn = "X123456",
        contactDetails = PractitionerDetails(name = Name("Unallocated", "Case"), username = "PLACEHOLDER", email = "old@example.com", unallocated = true),
        checkin = null,
        notificationType = NotificationType.PractitionerCheckinMissed,
        practitionerId = "OLD",
      )

      assertTrue(notifications.isEmpty())
      assertTrue(logs.list.any { it.level == Level.WARN && it.formattedMessage.contains("NOTIFICATION_UNDELIVERABLE") && it.formattedMessage.contains("unallocated") })
      verify(repository, never()).saveAll(any<List<GenericNotification>>())
    } finally {
      logger.detachAppender(logs)
      logs.stop()
    }
  }
}
