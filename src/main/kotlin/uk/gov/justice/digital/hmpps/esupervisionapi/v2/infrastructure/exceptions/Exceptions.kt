package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions

import java.lang.IllegalArgumentException

class BadArgumentException(message: String) : IllegalArgumentException(message)

class ResourceNotFoundException(message: String) : RuntimeException(message)
