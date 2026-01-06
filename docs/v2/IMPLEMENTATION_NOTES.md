# V2 Implementation Notes

Implementation details, UI changes, test coverage, and outstanding items.

---

## 1. UI Changes (probation-check-in-ui)

### 1.1 API Client Changes

**File:** `server/data/esupervisionApiClient.ts`

**Before (V1):**
```typescript
getCheckin(checkinId: string, includePersonalDetails?: boolean): Promise<OffenderCheckinResponse> {
  return this.get<OffenderCheckinResponse>({
    path: `/offender_checkins/${checkinId}`,
    query: { 'include-personal-details': includePersonalDetails },
  }, asSystem())
}
```

**After (V2 compatibility):**
```typescript
async getCheckin(checkinId: string): Promise<OffenderCheckinResponse> {
  const checkin = await this.get<Checkin>({
    path: `/offender_checkins/${checkinId}`,
  }, asSystem())
  // Wrap response to maintain compatibility with existing UI code
  return { checkin, checkinLogs: { hint: 'OMITTED', logs: [] } }
}
```

**Changes:**
1. Removed `includePersonalDetails` parameter (V2 fetches PII from Ndilius)
2. API returns `Checkin` directly instead of wrapped `{ checkin, checkinLogs }`
3. UI wraps response to maintain backward compatibility with templates

### 1.2 Model Changes

**File:** `server/data/models/checkin.ts`

| Field | Change | Reason |
|-------|--------|--------|
| `videoUrl` | `string` → `string?` | Only present after upload |
| `questions` | Retained | Type compatibility (not used by V2) |
| `flaggedResponses` | Retained | V2 computes in event notes |
| `reviewDueDate` | Retained | V1 specific, not in V2 |

### 1.3 Deleted Files (Candidates)

These model files are unused and candidates for removal:
- `server/data/models/offenderCheckinResponse.ts`
- `server/data/models/offenderCheckinLogs.ts`

### 1.4 Template Changes

**Files:**
- `server/views/pages/submission/video/view.njk`
- `server/views/pages/submission/check-answers.njk`

Templates updated to use `checkin` directly instead of `submission.checkin`.

---

## 2. Performance Improvements

### 2.1 N+1 Query Problem (Fixed)

**V1 Problem:**
```kotlin
val checkins = checkinRepository.findByPractitioner(practitionerId)
checkins.forEach { checkin ->
  // N+1: Each checkin triggers separate query for offender
  val offender = offenderRepository.findById(checkin.offenderId)
  // N+1: Each checkin triggers separate query for video URL
  val videoUrl = s3Service.getVideoUrl(checkin)
}
```

For 100 checkins → 200+ database queries.

**V2 Solution:**
```kotlin
@Query("SELECT c FROM OffenderCheckinV2 c JOIN FETCH c.offender WHERE c.createdBy = :practitionerId")
fun findAllByCreatedBy(practitionerId: String, pageable: Pageable): Page<OffenderCheckinV2>
```

Single query fetches checkins with offenders. Video URLs only fetched for detail view.

### 2.2 Batch Processing for Checkin Creation

**V1:**
```kotlin
offenders.forEach { offender ->
  createCheckin(offender)      // Individual save
  sendNotification(offender)   // Individual API call
}
```

**V2:**
```kotlin
val checkinsToCreate = offenders.map { offender ->
  OffenderCheckinV2(offender = offender, dueDate = calculateDueDate(offender), ...)
}
checkinRepository.saveAll(checkinsToCreate)  // Batch save
```

### 2.3 Lazy Loading for Video URLs

| Aspect | V1 | V2 |
|--------|----|----|
| List view | Generated URLs for all | No URLs |
| Detail view | Generated URL | Generated URL |
| `videoUrl` type | `String` | `URL?` (optional) |

---

## 3. Test Coverage

### 3.1 API Tests

| Test File | Coverage |
|-----------|----------|
| `CheckinV2ServiceTest.kt` | Checkin CRUD, submission, review flow |
| `OffenderSetupV2ServiceTest.kt` | Registration flow, validation |
| `NotificationOrchestratorV2ServiceTest.kt` | Notification delivery logic |

### 3.2 UI Tests

**Integration Tests:** `integration_tests/e2e/submission/checkin.cy.ts`

Covers the full offender journey:
1. Landing page
2. Identity verification
3. Survey questions (mental health, assistance, callback)
4. Video recording
5. Check answers
6. Submission confirmation

**Mock Updates:**
- `integration_tests/mockApis/esupervisionApi.ts` - Returns V2-compatible responses
- `stubGetCheckin` returns `Checkin` directly

### 3.3 Test Gaps

| Area | Status | Priority |
|------|--------|----------|
| Event callback tests (`/v2/events/*`) | Missing | High |
| Notification end-to-end with Notify mocks | Missing | Medium |
| Batch job tests (creation/expiry) | Missing | Medium |
| Error scenarios (Ndilius down, S3 failures) | Missing | High |

---

## 4. GDS Compliance Requirements

### 4.1 Accessibility (WCAG 2.1 AA)

UI uses GOV.UK Design System components (accessible by default).

**Areas to verify:**
- [ ] Video recording component (custom implementation)
- [ ] Error message announcements
- [ ] Focus management during form validation

### 4.2 Security

- [ ] No PII in logs (PiiSanitizer implemented - needs audit)
- [ ] Rate limiting on public endpoints (checkin submission)
- [ ] CSRF protection on form submissions
- [ ] Content Security Policy headers

### 4.3 Performance

- [ ] Page load times under 3 seconds
- [ ] Video upload progress indication
- [ ] Graceful degradation if video upload fails

### 4.4 Data Retention

- [ ] Checkin data retention policy
- [ ] Video/image retention in S3
- [ ] Audit log retention

---

## 5. Outstanding Items

### 5.1 API

- [ ] Add `flaggedResponses` to `CheckinV2Dto` for MPOP UI
- [ ] Implement checkin cancellation endpoint
- [ ] Add pagination metadata to list responses

### 5.2 UI

- [ ] Remove unused model files after confirming no references
- [ ] Update types to remove V1-specific fields (questions, reviewDueDate)
- [ ] Add proper error handling for V2 response format

### 5.3 Infrastructure

- [ ] Set up V2-specific CloudWatch dashboards
- [ ] Configure alerts for Ndilius API failures
- [ ] Document runbook for common issues

---

## 6. Logical Changes Summary

### 6.1 PII Flow

| Step | V1 | V2 |
|------|----|----|
| Setup | Store PII in DB | Store only CRN |
| Serve | Return PII from DB | Fetch from Ndilius on demand |
| Sync | Manual sync issues | Always consistent |

### 6.2 Flagged Responses

**V1:** Computed as property in `OffenderCheckinDto`
```kotlin
@get:JsonProperty("flaggedResponses")
val flaggedResponses: List<String>
  get() = computeFromSurvey(surveyResponse)
```

**V2:** Computed in `EventDetailV2Service` for Ndilius notes
```kotlin
private fun computeFlaggedResponses(survey: Map<String, Any>): List<String> {
  val version = survey["version"] as? String ?: return emptyList()
  return when (version) {
    "2025-07-10@pilot" -> computeFlaggedFor20250710pilot(survey)
    else -> emptyList()
  }
}
```

Flagged responses included in event notes string, not as separate field.
