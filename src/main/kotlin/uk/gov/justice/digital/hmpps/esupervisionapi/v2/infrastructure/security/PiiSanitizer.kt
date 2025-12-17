package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.security

import java.util.UUID

/**
 * Sanitizes PII data from exception messages and logs
 * Removes: forename, surname, mobile, email, location, dateOfBirth
 * Preserves: CRN, UUIDs for tracking
 */
object PiiSanitizer {
  /**
   * Sanitize exception for logging
   */
  fun sanitizeException(exception: Throwable, crn: String? = null, uuid: UUID? = null): String {
    val message = exception.message ?: exception.javaClass.simpleName
    return sanitizeMessage(message, crn, uuid)
  }

  /**
   * Sanitize message by removing PII fields
   */
  fun sanitizeMessage(message: String, crn: String? = null, uuid: UUID? = null): String {
    var sanitized = message

    // Remove JSON fields: "field":"value" or "field": "value"
    sanitized = Regex(""""forename"\s*:\s*"[^"]*"""").replace(sanitized, "")
    sanitized = Regex(""""surname"\s*:\s*"[^"]*"""").replace(sanitized, "")
    sanitized = Regex(""""mobile"\s*:\s*"[^"]*"""").replace(sanitized, "")
    sanitized = Regex(""""email"\s*:\s*"[^"]*"""").replace(sanitized, "")
    sanitized = Regex(""""location"\s*:\s*"[^"]*"""").replace(sanitized, "")
    sanitized = Regex(""""dateOfBirth"\s*:\s*"[^"]*"""").replace(sanitized, "")

    // Remove Kotlin object fields: field=value
    sanitized = Regex("""forename=[^,\s)]+""").replace(sanitized, "")
    sanitized = Regex("""surname=[^,\s)]+""").replace(sanitized, "")
    sanitized = Regex("""mobile=[^,\s)]+""").replace(sanitized, "")
    sanitized = Regex("""email=[^,\s)]+""").replace(sanitized, "")
    sanitized = Regex("""location=[^,\s)]+""").replace(sanitized, "")
    sanitized = Regex("""dateOfBirth=[^,\s)]+""").replace(sanitized, "")

    // Clean up double commas and trailing commas
    sanitized = sanitized.replace(Regex(""",\s*,"""), ",")
    sanitized = sanitized.replace(Regex(""",\s*\}"""), "}")
    sanitized = sanitized.replace(Regex("""\{\s*,"""), "{")

    // Add context if provided
    val context = buildContext(crn, uuid)
    return if (context.isNotEmpty()) "$sanitized $context" else sanitized
  }

  /**
   * Build context string
   */
  private fun buildContext(crn: String?, uuid: UUID?): String {
    val parts = mutableListOf<String>()
    crn?.let { parts.add("crn=$it") }
    uuid?.let { parts.add("uuid=$it") }
    return if (parts.isNotEmpty()) "[${parts.joinToString(", ")}]" else ""
  }

  /**
   * Sanitize for circuit breaker fallback
   */
  fun sanitizeForFallback(exception: Throwable, context: String): String {
    val errorType = exception.javaClass.simpleName
    val sanitized = sanitizeException(exception)
    return "errorType=$errorType, message=$sanitized, context=$context"
  }
}
