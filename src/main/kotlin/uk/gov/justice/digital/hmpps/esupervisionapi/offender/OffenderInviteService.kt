package uk.gov.justice.digital.hmpps.esupervisionapi.offender

import com.google.i18n.phonenumbers.NumberParseException
import com.google.i18n.phonenumbers.PhoneNumberUtil
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.data.domain.Pageable
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.InviteInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInfo
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInviteConfirmation
import uk.gov.justice.digital.hmpps.esupervisionapi.offender.invite.OffenderInviteDto
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.Practitioner
import uk.gov.justice.digital.hmpps.esupervisionapi.practitioner.PractitionerRepository
import uk.gov.justice.digital.hmpps.esupervisionapi.utils.S3UploadService
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Optional
import java.util.UUID

fun OffenderInfo.asInviteInfo(practitioner: Practitioner, now: Instant, expiryDate: Instant) = OffenderInvite(
  uuid = UUID.randomUUID(),
  firstName = firstName,
  lastName = lastName,
  dateOfBirth = dateOfBirth,
  createdAt = now,
  updatedAt = now,
  expiresOn = expiryDate,
  email = email,
  phoneNumber = phoneNumber,
  practitioner = practitioner,
)

data class CreateInviteResult(
  val invite: OffenderInvite?,
  /**
   * Set only when an invite could not be created
   */
  val errorMessage: String?,
  /**
   * Set only when an invite could not be created.
   */
  val offenderInfo: OffenderInfo?,
)
data class AggregateCreateInviteResult(val results: List<CreateInviteResult>) {
  val importedRecords: Int
    get() = results.count { it.invite != null }
}

@Service
class OffenderInviteService(
  private val offenderInviteRepository: OffenderInviteRepository,
  private val offenderRepository: OffenderRepository,
  private val practitionerRepository: PractitionerRepository,
  private val s3UploadService: S3UploadService,
) {

  fun findByUUID(uuid: UUID): Optional<OffenderInvite> = offenderInviteRepository.findByUuid(uuid)

  /**
   * Takes a collection of data, where each item represents minimal data required to send an invite.
   *
   */
  fun createOffenderInvites(inviteInfo: InviteInfo): AggregateCreateInviteResult {
    val now = Instant.now()
    val expiryDate = now.plus(5, ChronoUnit.DAYS)
    val practitioner = practitionerRepository.findAll()[0] // TODO: fetch user properly when auth is in
    val phoneNumUtil = PhoneNumberUtil.getInstance()

    val invalidInvites: MutableList<Pair<OffenderInfo, String>> = mutableListOf()
    val acceptedInvitees: MutableList<OffenderInfo> = mutableListOf()

    // TODO: validate DOB
    // TODO: proper Spring'y validation
    for (invitee in inviteInfo.invitees) {
      if (invitee.firstName.length <= 2) {
        invalidInvites.add(Pair(invitee, "Invalid first name"))
      } else if (invitee.lastName.length <= 2) {
        invalidInvites.add(Pair(invitee, "Invalid last name"))
      } else if (invitee.phoneNumber != null) {
        try {
          val number = phoneNumUtil.parse(invitee.phoneNumber, "GB")
          if (phoneNumUtil.isValidNumber(number)) {
            acceptedInvitees.add(
              invitee.copy(
                phoneNumber = phoneNumUtil.format(
                  number,
                  PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL,
                ),
              ),
            )
          } else {
            invalidInvites.add(Pair(invitee, "Invalid phone number"))
          }
        } catch (e: NumberParseException) {
          invalidInvites.add(Pair(invitee, "Invalid phone number"))
        }
      } else if (invitee.email != null && invitee.email.contains("@")) {
        acceptedInvitees.add(invitee)
      }
    }

    val invites = acceptedInvitees.map { it.asInviteInfo(practitioner, now, expiryDate) }

    val saved = offenderInviteRepository.saveAll<OffenderInvite>(invites)
    val inviteEmails = mutableSetOf<String>()
    val invitePhoneNumbers = mutableSetOf<String>()
    extractInvitesContactInfo(saved, inviteEmails, invitePhoneNumbers)

    val actionableInvites = mutableListOf<OffenderInvite>()
    val result = mutableListOf<CreateInviteResult>()
    // TODO(rosado): we modify `actionableInvites` here. Need to make it clearer
    val duplicateInvites = findDuplicateContactInfo(saved, inviteEmails, invitePhoneNumbers, actionableInvites)
    offenderInviteRepository.deleteAll(duplicateInvites)
    if (duplicateInvites.isNotEmpty()) {
      for (invite in duplicateInvites) {
        result.add(
          CreateInviteResult(
            null,
            errorMessage = "Contact info already in use",
            OffenderInfo(
              firstName = invite.firstName,
              lastName = invite.lastName,
              dateOfBirth = invite.dateOfBirth,
              email = invite.email,
              phoneNumber = invite.phoneNumber,
            ),
          ),
        )
      }
    }

    actionableInvites.forEach {
      result.add(CreateInviteResult(it, errorMessage = null, offenderInfo = null))
    }
    invalidInvites.forEach {
      result.add(CreateInviteResult(invite = null, errorMessage = it.second, offenderInfo = it.first))
    }

    return AggregateCreateInviteResult(result)
  }

  private fun findDuplicateContactInfo(
    saved: List<OffenderInvite>,
    inviteEmails: Set<String>,
    invitePhoneNumbers: Set<String>,
    actionableInvites: MutableList<OffenderInvite>,
  ): List<OffenderInvite> {
    val duplicateContactOffenders =
      offenderRepository.findWithMatchingContactInfo(emails = inviteEmails, phoneNumbers = invitePhoneNumbers)
    val duplicateContactInvites = offenderInviteRepository.findWithMatchingContactInfo(
      emails = inviteEmails,
      phoneNumbers = invitePhoneNumbers,
      uuids = saved.map { it.uuid },
    )
    val duplicateInvites = mutableListOf<OffenderInvite>()

    if (duplicateContactInvites.size > 0 || duplicateContactOffenders.size > 0) {
      val duplicateEmails = mutableSetOf<String>()
      val duplicatePhoneNumbers = mutableSetOf<String>()
      extractInvitesContactInfo(duplicateContactInvites, duplicateEmails, duplicatePhoneNumbers)
      extractOffendersContactInfo(duplicateContactOffenders, duplicateEmails, duplicatePhoneNumbers)

      for (invite in saved) {
        var duplicate = false
        val email = invite.email
        if (email != null && duplicateEmails.contains(email)) {
          duplicateInvites.add(invite)
          duplicate = true
        }
        val phoneNumber = invite.phoneNumber
        if (phoneNumber != null && duplicatePhoneNumbers.contains(phoneNumber)) {
          duplicateInvites.add(invite)
          duplicate = true
        }
        if (!duplicate) {
          actionableInvites.add(invite)
        }
      }
      assert(saved.size == actionableInvites.size + duplicateInvites.size)
    } else {
      actionableInvites.addAll(saved)
    }
    return duplicateInvites
  }

  private fun extractInvitesContactInfo(
    invites: Iterable<OffenderInvite>,
    emails: MutableSet<String>,
    phoneNumbers: MutableSet<String>,
  ) = extractContactInfo(invites.map { Pair(it.email, it.phoneNumber) }, emails, phoneNumbers)

  private fun extractOffendersContactInfo(
    offenders: Iterable<Offender>,
    emails: MutableSet<String>,
    phoneNumbers: MutableSet<String>,
  ) = extractContactInfo(offenders.map { Pair(it.email, it.phoneNumber) }, emails, phoneNumbers)

  private fun extractContactInfo(
    contacts: Iterable<Pair<String?, String?>>,
    emails: MutableSet<String>,
    phoneNumbers: MutableSet<String>,
  ) {
    for (pair in contacts) {
      val email = pair.first
      val phoneNumber = pair.second
      if (email != null) {
        emails.add(email)
      }
      if (phoneNumber != null) {
        phoneNumbers.add(phoneNumber)
      }
    }
  }

  // TODO: select only invites for current user (Practitioner)
  fun getAllOffenderInvites(pageable: Pageable): List<OffenderInviteDto> {
    val page = offenderInviteRepository.findAll(pageable)
    return page.content.map { it.dto() }
  }

  /**
   * To be called when offender confirms the invitation.
   * Returns the invite only if it's status was updated.
   */
  @Transactional
  fun confirmOffenderInvite(
    inviteUuid: UUID,
    confirmation: OffenderInviteConfirmation,
  ): Optional<OffenderInvite> {
    val inviteFound = offenderInviteRepository.findByUuid(inviteUuid)
    if (inviteFound.isPresent) {
      val invite = inviteFound.get()
      // TODO: we should check for "SENT" here -
      if (confirmation.info == invite.toOffenderInfo() && (invite.status == OffenderInviteStatus.CREATED || invite.status == OffenderInviteStatus.RESPONDED)) {
        val now = Instant.now()
        invite.status = OffenderInviteStatus.RESPONDED
        invite.updatedAt = now
        offenderInviteRepository.save(invite)
      } else {
        return Optional.empty()
      }
    }

    return inviteFound
  }

  @Transactional
  fun approveOffenderInvite(invite: OffenderInvite): Offender {
    // NOTE: we verified during the "confirm" step that a photo was uploaded

    val now = Instant.now()
    invite.status = OffenderInviteStatus.APPROVED
    invite.updatedAt = now
    offenderInviteRepository.save(invite)

    val offender = Offender(
      uuid = UUID.randomUUID(),
      firstName = invite.firstName,
      lastName = invite.lastName,
      email = invite.email,
      phoneNumber = invite.phoneNumber,
      status = OffenderStatus.VERIFIED,
      dateOfBirth = invite.dateOfBirth,
      createdAt = now,
      updatedAt = now,
      practitioner = invite.practitioner,
    )
    // TODO: verify no active records with same contact info exist
    return offenderRepository.save(offender)
  }

  @Transactional
  fun rejectOffenderInvite(invite: OffenderInvite): OffenderInvite {
    val now = Instant.now()
    invite.status = OffenderInviteStatus.REJECTED
    invite.updatedAt = now
    return offenderInviteRepository.save(invite)
  }

  companion object {
    val LOG: Logger = LoggerFactory.getLogger(this::class.java)
  }
}

fun OffenderInvite.toOffenderInfo(): OffenderInfo = OffenderInfo(firstName, lastName, dateOfBirth, email, phoneNumber)
