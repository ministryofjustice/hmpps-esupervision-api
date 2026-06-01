# V2 Domain Events

Domain events are published to AWS SQS for integration with NDelius and other downstream systems.

---

## Event Types

| Event Type | Path Segment | Description | Triggers Case Note |
|------------|--------------|-------------|-------------------|
| `V2_SETUP_COMPLETED` | `setup-completed` | Offender registration completed | Yes |
| `V2_CHECKIN_CREATED` | `checkin-created` | New checkin created | No |
| `V2_CHECKIN_SUBMITTED` | `checkin-submitted` | Offender submitted checkin | Yes |
| `V2_CHECKIN_REVIEWED` | `checkin-reviewed` | Practitioner reviewed checkin | Yes |
| `V2_CHECKIN_EXPIRED` | `checkin-expired` | Checkin marked as expired | Yes |
| `V2_CHECKIN_UPDATED` | `checkin-updated` | Checkin updated (annotated) | Yes |

---

## Event Publishing Flow

```mermaid
sequenceDiagram
    autonumber
    participant Service as Business Service
    participant DES as DomainEventService
    participant Pub as DomainEventPublisher
    participant SQS as AWS SQS
    participant NDelius as NDelius

    Service->>DES: publishDomainEvent(type, uuid, crn, description)

    DES->>DES: Construct detailUrl<br/>/v2/events/{type}/{uuid}

    DES->>DES: Build DomainEvent object
    Note right of DES: eventType, detailUrl,<br/>occurredAt, description,<br/>personReference (CRN)

    DES->>Pub: publish(event)
    Pub->>SQS: Send message to queue
    SQS-->>Pub: Message ID
    Pub-->>DES: Success

    Note over SQS,NDelius: Asynchronous Processing
    SQS-->>NDelius: Deliver event message
    NDelius->>NDelius: Process event
```

---

## Domain Event Structure

```kotlin
data class DomainEvent(
    val eventType: String,           // e.g., "probation-case.checkin.submitted"
    val detailUrl: String,           // Callback URL for event details
    val occurredAt: ZonedDateTime,   // When event occurred
    val description: String,         // Human-readable description
    val personReference: PersonReference,  // CRN identifier
)

data class PersonReference(
    val identifiers: List<PersonIdentifier>,
)

data class PersonIdentifier(
    val type: String,   // "CRN"
    val value: String,  // The actual CRN
)
```

---

## NDelius Callback Flow

When NDelius receives a domain event, it calls back to fetch formatted event details.

```mermaid
sequenceDiagram
    autonumber
    participant SQS as AWS SQS
    participant NDelius as NDelius
    participant API as eSupervision API V2
    participant EDS as EventDetailV2Service
    participant DB as Database

    SQS->>NDelius: Domain Event Message
    Note right of SQS: Contains detailUrl:<br/>/v2/events/checkin-submitted/{uuid}

    NDelius->>API: GET /v2/events/checkin-submitted/{uuid}

    API->>EDS: getEventDetail(detailUrl)

    EDS->>EDS: Parse event type from URL
    EDS->>DB: Query source table<br/>(offender_checkin_v2)
    DB-->>EDS: Checkin record

    EDS->>EDS: Format human-readable notes
    Note right of EDS: Include:<br/>- Timestamp<br/>- Auto ID check result<br/>- Survey responses (formatted)

    EDS-->>API: EventDetailResponse
    Note right of EDS: eventReferenceId,<br/>eventType, notes,<br/>crn, timestamps

    API-->>NDelius: 200 OK + EventDetailResponse

    NDelius->>NDelius: Store event in case notes
```

---

## Event Detail Response

```kotlin
data class EventDetailResponse(
    val eventReferenceId: String,  // e.g., "V2_CHECKIN_SUBMITTED-{uuid}"
    val eventType: String,         // e.g., "V2_CHECKIN_SUBMITTED"
    val notes: String,             // Human-readable formatted notes
    val crn: String,
    val offenderUuid: UUID,
    val checkinUuid: UUID?,
    val timestamp: Instant,
)
```

### Notes Formatting

The `notes` field contains human-readable text formatted for case notes:

**Setup Completed:**
```
Registration Completed
Offender UUID: {uuid}
CRN: {crn}
Practitioner: {practitionerId}
Status: VERIFIED
First check-in: {date}
Check-in interval: {interval}
```

**Checkin Submitted:**
```
Check in submitted: 15 January 2025 at 2:30pm
Automated ID check: Match

Survey response:
How they have been feeling: Good
Anything they need support with: Housing, Money
If they need us to contact them: Yes
What they want to talk about: Need help with rent
```

**Checkin Updated:**
```
Check in updated: 16 January 2025 at 10:00am
Updated by: U123456
Notes: Spoke to offender about housing issue. Referring to housing officer.
```

---

## Event Callback Endpoints

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/v2/events/setup-completed/{uuid}` | Setup completed event details |
| `GET` | `/v2/events/checkin-created/{uuid}` | Checkin created event details |
| `GET` | `/v2/events/checkin-submitted/{uuid}` | Checkin submitted event details |
| `GET` | `/v2/events/checkin-reviewed/{uuid}` | Checkin reviewed event details |
| `GET` | `/v2/events/checkin-expired/{uuid}` | Checkin expired event details |
| `GET` | `/v2/events/checkin-updated/{uuid}` | Checkin updated event details |

---

## Service Architecture

```mermaid
classDiagram
    class DomainEventService {
        +publishDomainEvent(eventType, uuid, crn, description)
    }

    class DomainEventPublisher {
        +publish(event: DomainEvent)
    }

    class EventDetailV2Service {
        +getEventDetail(detailUrl): EventDetailResponse?
        -getRegistrationCompletedDetail(uuid)
        -getCheckinEventDetail(uuid, eventType)
        -formatSetupCompletedNotes(offender)
        -formatCheckinNotes(checkin, eventType)
    }

    class DomainEventType {
        <<enumeration>>
        V2_SETUP_COMPLETED
        V2_CHECKIN_CREATED
        V2_CHECKIN_SUBMITTED
        V2_CHECKIN_REVIEWED
        V2_CHECKIN_EXPIRED
        V2_CHECKIN_UPDATED
        +eventTypeName: String
        +pathSegment: String
        +type: String
    }

    DomainEventService --> DomainEventPublisher
    DomainEventService --> DomainEventType
    EventDetailV2Service --> DomainEventType
```

---

## Configuration

```yaml
app:
  apiBaseUrl: https://esupervision-api.example.com  # Base URL for detailUrl construction

# SQS configuration
hmpps:
  sqs:
    queues:
      domainevents:
        queueName: esupervision-domain-events
```
