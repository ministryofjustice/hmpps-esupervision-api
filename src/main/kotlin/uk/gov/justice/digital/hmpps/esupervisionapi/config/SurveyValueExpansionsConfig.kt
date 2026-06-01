package uk.gov.justice.digital.hmpps.esupervisionapi.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration options influencing how the offender survey values are displayed.
 */
@ConfigurationProperties(prefix = "app.offender-survey")
class SurveyValueExpansionsConfig(
  /**
   * Maps values coming from a web form into a human-friendly string, which
   * will be used in an NDelius note.
   *
   * Example: SUPPORT_SYSTEM -> Relationships (family, friends, partner)
   */
  val expansions: Map<String, String> = mapOf(),

  /**
   * Maps values coming from a web form into a human-friendly string, which
   * will be used as a label in an NDelius note.
   *
   * Example: mentalHealthSupport -> "What they want us to know about mental health"
   */
  val customLabels: Map<String, String> = mapOf(),
)
