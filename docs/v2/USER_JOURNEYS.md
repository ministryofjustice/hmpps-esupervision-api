# V2 User Journeys

Detailed flow diagrams for all user-facing journeys in the V2 eSupervision system.

---

## 1. Offender Setup Flow

Initiated by a **Practitioner** via MPOP dashboard to register an offender for remote check-ins.

```mermaid
sequenceDiagram
    autonumber
    participant P as Practitioner
    participant MPOP as MPOP Dashboard
    participant API as eSupervision API V2
    participant S3 as AWS S3
    participant Ndilius as Ndilius
    participant Notify as GOV.UK Notify
    participant SQS as AWS SQS

    rect rgb(230, 245, 255)
        note over P,API: Step 1: Start Setup
        P->>MPOP: Open Add Offender page
        P->>MPOP: Fill CRN, schedule details
        MPOP->>API: POST /v2/offender_setup
        API->>API: Create OffenderV2 (status=INITIAL)
        API->>API: Create OffenderSetupV2
        API-->>MPOP: Return setup UUID
    end

    rect rgb(255, 245, 230)
        note over P,S3: Step 2: Upload Photo
        MPOP->>API: POST /v2/offender_setup/{uuid}/upload_location
        API->>S3: Generate presigned URL
        API-->>MPOP: Return presigned URL (5min TTL)
        P->>MPOP: Take/select photo
        MPOP->>S3: PUT photo to presigned URL
    end

    rect rgb(230, 255, 230)
        note over P,SQS: Step 3: Complete Setup
        P->>MPOP: Confirm setup
        MPOP->>API: POST /v2/offender_setup/{uuid}/complete
        API->>S3: Verify photo exists
        API->>Ndilius: GET /contact-details/{crn}
        API->>API: Update status to VERIFIED

        alt First checkin due today
            API->>API: Create first OffenderCheckinV2
        end

        API->>SQS: Publish V2_SETUP_COMPLETED event
        API->>Notify: Send registration confirmation

        alt First checkin created
            API->>SQS: Publish V2_CHECKIN_CREATED event
            API->>Notify: Send checkin invite to offender
        end

        API-->>MPOP: Return OffenderV2Dto
        MPOP-->>P: Display confirmation
    end
```

### Setup Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v2/offender_setup` | Start offender setup |
| `POST` | `/v2/offender_setup/{uuid}/upload_location` | Get photo upload URL |
| `POST` | `/v2/offender_setup/{uuid}/complete` | Complete setup |
| `POST` | `/v2/offender_setup/{uuid}/terminate` | Cancel setup |

---

## 2. Checkin Journey Flow

Initiated by an **Offender** clicking the check-in link in their SMS/email notification.

```mermaid
sequenceDiagram
    autonumber
    participant O as Offender
    participant UI as Check-in UI
    participant API as eSupervision API V2
    participant Ndilius as Ndilius
    participant S3 as AWS S3
    participant Rekog as AWS Rekognition
    participant Notify as GOV.UK Notify
    participant SQS as AWS SQS

    rect rgb(230, 245, 255)
        note over O,Ndilius: Step 1: Identity Verification
        O->>UI: Click check-in link
        UI->>API: GET /v2/offender_checkins/{uuid}
        API-->>UI: Return checkin details
        UI-->>O: Display identity verification form
        O->>UI: Enter name, DOB, last 3 CRN digits
        UI->>API: POST /v2/offender_checkins/{uuid}/identity-verify
        API->>Ndilius: Validate personal details
        Ndilius-->>API: Validation result
        API->>API: Mark checkinStartedAt
        API-->>UI: verified: true
    end

    rect rgb(255, 245, 230)
        note over O,S3: Step 2: Get Upload URLs
        UI->>API: POST /v2/offender_checkins/{uuid}/upload_location
        API->>S3: Generate presigned URLs
        API-->>UI: Return video + snapshot URLs (10min TTL)
    end

    rect rgb(255, 235, 245)
        note over O,S3: Step 3: Record & Upload Video
        UI-->>O: Display video recording page
        O->>UI: Record video
        UI->>S3: PUT video to presigned URL
        UI->>S3: PUT snapshot(s) to presigned URL
    end

    rect rgb(245, 235, 255)
        note over O,Rekog: Step 4: Facial Verification
        UI->>API: POST /v2/offender_checkins/{uuid}/video-verify
        API->>S3: Get setup photo location
        API->>S3: Get snapshot location
        API->>Rekog: Compare faces
        Rekog-->>API: Similarity score
        API->>API: Store autoIdCheck result
        API-->>UI: MATCH / NO_MATCH / NO_FACE_DETECTED

        alt NO_MATCH
            UI-->>O: Option to re-record or continue
        end
    end

    rect rgb(230, 255, 230)
        note over O,SQS: Step 5: Complete Survey & Submit
        UI-->>O: Display survey questions
        O->>UI: Answer mental health, assistance, callback questions
        O->>UI: Review and submit
        UI->>API: POST /v2/offender_checkins/{uuid}/submit
        API->>S3: Verify video uploaded
        API->>API: Save survey, update status to SUBMITTED
        API->>SQS: Publish V2_CHECKIN_SUBMITTED event
        API->>Notify: Send confirmation to offender
        API->>Notify: Send alert to practitioner
        API-->>UI: Return updated checkin
        UI-->>O: Display confirmation page
    end
```

### Checkin Status Flow

```mermaid
stateDiagram-v2
    [*] --> CREATED: Checkin Created<br/>(by job or setup)

    CREATED --> SUBMITTED: Offender submits<br/>checkin
    CREATED --> EXPIRED: Grace period<br/>exceeded

    SUBMITTED --> REVIEWED: Practitioner<br/>completes review

    EXPIRED --> REVIEWED: Practitioner reviews<br/>missed checkin

    REVIEWED --> [*]: Final state
```

---

## 3. Practitioner Review Flow

Initiated by a **Practitioner** reviewing a submitted check-in in MPOP.

```mermaid
sequenceDiagram
    autonumber
    participant P as Practitioner
    participant MPOP as MPOP Dashboard
    participant API as eSupervision API V2
    participant S3 as AWS S3
    participant Ndilius as Ndilius
    participant SQS as AWS SQS

    rect rgb(230, 245, 255)
        note over P,Ndilius: Step 1: View Checkin List
        P->>MPOP: Open dashboard
        MPOP->>API: GET /v2/offender_checkins?useCase=NEEDS_ATTENTION
        API-->>MPOP: Return checkins needing review
        MPOP-->>P: Display checkin list with flags
    end

    rect rgb(255, 245, 230)
        note over P,S3: Step 2: Start Review
        P->>MPOP: Select checkin to review
        MPOP->>API: POST /v2/offender_checkins/{uuid}/review-started
        API->>API: Record reviewStartedAt, reviewStartedBy
        MPOP->>API: GET /v2/offender_checkins/{uuid}?include-personal-details=true
        API->>Ndilius: GET /contact-details/{crn}
        API->>S3: Generate video read URL
        API->>S3: Generate snapshot read URL
        API-->>MPOP: Return full checkin details
        MPOP-->>P: Display review page with video
    end

    rect rgb(245, 235, 255)
        note over P,P: Step 3: Review Video & Survey
        P->>MPOP: Watch video recording
        P->>MPOP: Review survey responses
        P->>MPOP: Verify identity manually
        P->>MPOP: Assess risk flags
    end

    rect rgb(230, 255, 230)
        note over P,SQS: Step 4: Complete Review
        P->>MPOP: Submit review decision
        MPOP->>API: POST /v2/offender_checkins/{uuid}/review
        Note right of MPOP: Include: manualIdCheck,<br/>riskManagementFeedback, notes
        API->>API: Update status to REVIEWED
        API->>API: Create OffenderEventLogV2 entry
        API->>SQS: Publish V2_CHECKIN_REVIEWED event
        API-->>MPOP: Return updated checkin
        MPOP-->>P: Display confirmation
    end

    rect rgb(255, 235, 245)
        note over P,API: Optional: Annotate Later
        P->>MPOP: Add follow-up notes
        MPOP->>API: POST /v2/offender_checkins/{uuid}/annotate
        API->>API: Create additional log entry
        API-->>MPOP: Confirmation
    end
```

### Review List Use Cases

| Use Case | Description | Query Parameter |
|----------|-------------|-----------------|
| Needs Attention | Submitted or unreviewed expired | `useCase=NEEDS_ATTENTION` |
| Reviewed | Completed reviews | `useCase=REVIEWED` |
| Awaiting Checkin | Created, waiting for offender | `useCase=AWAITING_CHECKIN` |

---

## 4. Checkin Endpoints Summary

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/v2/offender_checkins` | List checkins (paginated) |
| `GET` | `/v2/offender_checkins/{uuid}` | Get checkin details |
| `POST` | `/v2/offender_checkins/{uuid}/identity-verify` | Verify offender identity |
| `POST` | `/v2/offender_checkins/{uuid}/upload_location` | Get video/snapshot upload URLs |
| `POST` | `/v2/offender_checkins/{uuid}/video-verify` | Facial recognition verification |
| `POST` | `/v2/offender_checkins/{uuid}/submit` | Submit checkin |
| `POST` | `/v2/offender_checkins/{uuid}/review-started` | Mark review started |
| `POST` | `/v2/offender_checkins/{uuid}/review` | Complete review |
| `POST` | `/v2/offender_checkins/{uuid}/annotate` | Add annotation |
| `GET` | `/v2/offender_checkins/{uuid}/proxy/video` | Get video URL |
| `GET` | `/v2/offender_checkins/{uuid}/proxy/snapshot` | Get snapshot URL |
