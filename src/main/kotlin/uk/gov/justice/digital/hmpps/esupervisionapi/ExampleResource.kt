package uk.gov.justice.digital.hmpps.esupervisionapi

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

// TODO This controller exists to support the HMPPS Typescript template and should be removed by the bootstrap process
@RestController
// @PreAuthorize("hasRole('ROLE_TEMPLATE_KOTLIN__UI')")
@RequestMapping("/example", produces = ["application/json"])
class ExampleResource {
  @GetMapping("/time")
  fun getTime(): LocalDateTime = LocalDateTime.now()
}
