package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import java.lang.IllegalArgumentException

class BadArgumentException(message: String) : IllegalArgumentException(message)

class ResourceNotFoundException(message: String) : RuntimeException(message)
