package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions

import java.lang.IllegalArgumentException
import java.util.UUID

class BadArgumentException(message: String) : IllegalArgumentException(message)

class ResourceNotFoundException(message: String) : RuntimeException(message)

/**
 * ESUP-2057: thrown when a checkin's media (video/snapshot) has been removed under the retention policy
 * (`imageDeletedAt` is set) but a caller still requests a proxy URL for it.
 */
class ImageRetentionExpiredException(checkinUuid: UUID) : RuntimeException("Media for checkin $checkinUuid is no longer available: removed under the retention policy")
