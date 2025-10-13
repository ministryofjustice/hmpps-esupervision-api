package uk.gov.justice.digital.hmpps.esupervisionapi.utils

fun <T> Collection<T>.powerSet(): Set<Set<T>> = when {
  isEmpty() -> setOf(emptySet())
  else -> {
    // remove first item from this collection
    // powerset is formed by the union of including and not including
    // the first item in each subset of the powerset of the resulting collection
    val x = first()
    val ps = drop(1).powerSet()

    ps + ps.map { it + x }.toSet()
  }
}
