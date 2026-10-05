package uk.gov.justice.digital.hmpps.esupervisionapi.v2.eligibility

import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Name
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.fullName
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.question.replacePlaceholder

fun applyTemplate(template: String, name: Name): String = template.replacePlaceholder("offender", name.fullName())
