package uk.gov.justice.digital.hmpps.esupervisionapi.utils

import jakarta.persistence.Column
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.MappedSuperclass

@MappedSuperclass
abstract class AEntity {
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  @Column(name = "id")
  open protected val id: Long = 0

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other == null) return false
    if (other::class != this::class) return false
    if (id != (other as AEntity).id) return false
    return true
  }

  override fun hashCode(): Int = id.hashCode()

  override fun toString(): String = this::class.simpleName + "(id=$id)"
}