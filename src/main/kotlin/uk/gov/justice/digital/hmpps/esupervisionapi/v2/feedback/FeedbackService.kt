package uk.gov.justice.digital.hmpps.esupervisionapi.v2.feedback

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.Feedback
import uk.gov.justice.digital.hmpps.esupervisionapi.v2.FeedbackRepository
import java.time.Instant

@Service
class FeedbackService(private val repository: FeedbackRepository) {

  fun createFeedback(feedbackData: Map<String, Any>): Feedback {
    val feedback = Feedback(feedback = feedbackData, createdAt = Instant.now())
    return repository.save(feedback)
  }

  fun getAllFeedback(pageable: Pageable): Page<Feedback> = repository.findAll(pageable)
}
