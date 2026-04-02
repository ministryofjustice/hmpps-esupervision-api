package uk.gov.justice.digital.hmpps.esupervisionapi.v2.question

import org.springframework.stereotype.Repository

data class QuestionListItem(
  val questionList: Long,
  val question: Long,
  val position: Int,
  val params: Map<String, Any>,
)

@Repository
class QuestionListItemsRepository(
  private val jdbcTemplate: org.springframework.jdbc.core.JdbcTemplate,
  private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
) {

  fun findAllItems(): List<QuestionListItem> = jdbcTemplate.query("SELECT * FROM question_list_item", rowMapper)

  fun findById(id: Long): QuestionListItem? {
    val sql = "SELECT * FROM question_list_item WHERE question_list_id = ?"
    return jdbcTemplate.query(sql, rowMapper, id).firstOrNull()
  }

  private val rowMapper = org.springframework.jdbc.core.RowMapper { rs, _ ->
    QuestionListItem(
      questionList = rs.getLong("question_list_id"),
      question = rs.getLong("question_id"),
      position = rs.getInt("position"),
      params = objectMapper.readValue(rs.getString("params"), object : com.fasterxml.jackson.core.type.TypeReference<Map<String, Any>>() {}),
    )
  }

  fun deleteAllNonSystem() {
    jdbcTemplate.execute("delete from question_list where author != 'SYSTEM'")
  }
}
