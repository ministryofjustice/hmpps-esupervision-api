package uk.gov.justice.digital.hmpps.esupervisionapi.v2.infrastructure.exceptions

import java.lang.IllegalArgumentException
import java.util.UUID
import java.util.concurrent.CompletionException

class BadArgumentException(message: String) : IllegalArgumentException(message)

class ResourceNotFoundException(message: String) : RuntimeException(message)

/**
 * ESUP-2057: thrown when a checkin's media (video/snapshot) has been removed under the retention policy
 * (`imageDeletedAt` is set) but a caller still requests a proxy URL for it.
 */
class ImageRetentionExpiredException(checkinUuid: UUID) : RuntimeException("Media for checkin $checkinUuid is no longer available: removed under the retention policy")

/**
 * Traverses the causal chain of the provided throwable to find the last instance of a `CompletionException`
 * or the original throwable if none exist.
 *
 * @param throwable The root throwable to begin the search from.
 * @return The last `CompletionException` in the causal chain or the original throwable if no
 * `CompletionException` is found.
 */
fun findLastCompletionException(throwable: Throwable): Throwable = generateSequence(throwable) {
  if (it is CompletionException) it.cause else null
}.last()
