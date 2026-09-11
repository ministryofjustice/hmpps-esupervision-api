package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility.EligibilityEvaluationEngine.Companion.DEFAULT_RULE_SET

/**
 * Renders the offender eligibility rule set as a plain HTML page, so a non-developer
 * (e.g. the business analyst who owns the decision table) can eyeball the rows currently
 * in the database without needing DB access or reading JSON. Local-only: this is a
 * debugging/verification aid, not a product feature.
 */
@RestController
@RequestMapping("/v2/eligibility")
@Profile("local")
@Tag(name = "Eligibility Rules View", description = "HTML view of eligibility rules (Local only)")
class EligibilityRulesViewResource(
  private val ruleRepository: EligibilityRuleRepository,
) {

  @PreAuthorize("permitAll()")
  @Operation(summary = "View eligibility rules as an HTML page")
  @GetMapping("/rules/view", produces = [MediaType.TEXT_HTML_VALUE])
  fun viewRules(@RequestParam(name = "ruleset", defaultValue = DEFAULT_RULE_SET) ruleSet: String): ResponseEntity<String> {
    val rules = ruleRepository.findByRuleSetAndEnabledTrueOrderByRuleOrderAsc(ruleSet).sortedBy { it.ruleOrder }
    return ResponseEntity.ok(renderRulesPage(rules))
  }
}

private object EligibilityRuleTemplates {
  val page: String by lazy { load("templates/eligibility/page.html") }
  val ruleSet: String by lazy { load("templates/eligibility/rule-set.html") }
  val rule: String by lazy { load("templates/eligibility/rule.html") }

  private fun load(path: String): String = ClassPathResource(path).inputStream.bufferedReader().use { it.readText() }
}

/** Naive `{{key}}` placeholder substitution - no templating engine dependency */
private fun render(template: String, values: Map<String, String>): String = values.entries.fold(template) { acc, (key, value) -> acc.replace("{{$key}}", value) }

private fun renderRulesPage(rules: List<OffenderEligibilityRule>): String {
  val ruleSets = rules.groupBy { it.ruleSet }
  val body = if (rules.isEmpty()) {
    "<p class=\"empty\">No eligibility rules found.</p>"
  } else {
    ruleSets.entries.joinToString(separator = "\n") { (ruleSet, rulesInSet) ->
      renderRuleSet(ruleSet, rulesInSet.sortedBy { it.ruleOrder })
    }
  }

  return render(EligibilityRuleTemplates.page, mapOf("body" to body))
}

private fun renderRuleSet(ruleSet: String, rules: List<OffenderEligibilityRule>): String = render(
  EligibilityRuleTemplates.ruleSet,
  mapOf(
    "ruleSet" to escapeHtml(ruleSet),
    "rules" to rules.joinToString(separator = "\n") { renderRule(it) },
  ),
)

private fun renderRule(rule: OffenderEligibilityRule): String {
  val statusClass = if (rule.enabled) "" else " disabled"
  val badgeClass = if (rule.enabled) "enabled" else "disabled"
  val badgeText = if (rule.enabled) "Enabled" else "Disabled"

  return render(
    EligibilityRuleTemplates.rule,
    mapOf(
      "statusClass" to statusClass,
      "code" to escapeHtml(rule.code),
      "ruleOrder" to rule.ruleOrder.toString(),
      "badgeClass" to badgeClass,
      "badgeText" to badgeText,
      "question" to escapeHtml(rule.question),
      "source" to escapeHtml(rule.source),
      "dataPoint" to escapeHtml(rule.dataPoint),
      "operator" to escapeHtml(rule.operator.name),
      "comparisonValue" to renderNullable(rule.comparisonValue),
      "outcomeOnMatchClass" to rule.outcomeOnMatch.name.lowercase(),
      "outcomeOnMatch" to rule.outcomeOnMatch.name,
      "messageOnMatch" to renderMessage(rule.messageOnMatch),
      "outcomeOnNoMatchClass" to rule.outcomeOnNoMatch.name.lowercase(),
      "outcomeOnNoMatch" to rule.outcomeOnNoMatch.name,
      "messageOnNoMatch" to renderMessage(rule.messageOnNoMatch),
      "comment" to renderComment(rule.comment),
    ),
  )
}

private fun renderMessage(message: String?): String = if (message.isNullOrBlank()) "" else " &mdash; ${escapeHtml(message)}"

private fun renderComment(comment: String?): String = if (comment.isNullOrBlank()) "" else """<p class="rule-comment">${escapeHtml(comment)}</p>"""

private fun renderNullable(value: String?): String = if (value.isNullOrBlank()) "<em>(none)</em>" else escapeHtml(value)

private fun escapeHtml(value: String): String = value
  .replace("&", "&amp;")
  .replace("<", "&lt;")
  .replace(">", "&gt;")
  .replace("\"", "&quot;")
  .replace("'", "&#39;")
