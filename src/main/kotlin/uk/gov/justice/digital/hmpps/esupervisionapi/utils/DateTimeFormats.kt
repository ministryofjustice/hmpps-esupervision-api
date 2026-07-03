package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val HUMAN_READABLE_DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' h:mma", Locale.UK)

/**
 * Formats an instant as e.g. "12 May 2026 at 3:45pm" (Europe/London), the style used in NDelius contact notes.
 */
fun formatHumanReadableDateTime(instant: Instant): String = instant
  .atZone(ZoneId.of("Europe/London"))
  .format(HUMAN_READABLE_DATE_TIME_FORMATTER)
  .replace("AM", "am")
  .replace("PM", "pm")
