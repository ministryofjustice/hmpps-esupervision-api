package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import java.lang.IllegalArgumentException

class BadArgumentException(message: String) : IllegalArgumentException(message)

@Deprecated("Use uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions.ResourceNotFoundException instead")
class ResourceNotFoundException(message: String) : RuntimeException(message)
