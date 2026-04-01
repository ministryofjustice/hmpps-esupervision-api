package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import org.springframework.stereotype.Repository

@Repository
class QuestionListItemsRepository(private val jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate) {

  fun findAllItems(): List<Map<String, Any>> = jdbcTemplate.queryForList("SELECT * FROM question_list_item")

  fun findById(id: Long): Map<String, Any>? {
    val sql = "SELECT * FROM question_list_item WHERE id = ?"
    return jdbcTemplate.queryForList(sql, id).firstOrNull()
  }

  fun deleteAllNonSystem() {
    jdbcTemplate.execute("delete from question_list where author != 'SYSTEM'")
  }
}
