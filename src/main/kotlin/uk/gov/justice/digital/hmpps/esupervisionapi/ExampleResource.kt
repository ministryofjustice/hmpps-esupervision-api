package uk.gov.justice.digital.hmpps.esupervisionapi

import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDateTime

@RestController
@PreAuthorize("hasRole('ESUP_PRACTITIONER')")
@RequestMapping("/example", produces = ["application/json"])
class ExampleResource {
  @GetMapping("/time")
  fun getTime(): LocalDateTime = LocalDateTime.now()
}
