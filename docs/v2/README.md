# V2 Architecture Overview

V2 is a complete architectural refactoring of the eSupervision API with improved data handling, performance, and integration.

---

## Key Improvements

| Aspect | V1 | V2 |
|--------|----|----|
| **PII Storage** | Stored in database | Fetched on-demand from Ndilius |
| **Code Isolation** | Shared with other modules | Separate `v2` package |
| **Database Tables** | Shared tables | Dedicated `_v2` suffix tables |
| **Performance** | N+1 queries | Batch operations, streaming |
| **Event Integration** | Limited | Full domain events to SQS |

---

## Documentation Index

| Document | Description |
|----------|-------------|
| [User Journeys](USER_JOURNEYS.md) | Setup, checkin, and review flows with diagrams |
| [Domain Events](DOMAIN_EVENTS.md) | Event types, publishing, Ndilius callbacks |
| [Notifications](NOTIFICATIONS.md) | SMS/Email notification system |
| [Background Jobs](BACKGROUND_JOBS.md) | Scheduled checkin creation and expiry |
| [Data Model](DATA_MODEL.md) | Entity relationships and schema |
| [Implementation Notes](IMPLEMENTATION_NOTES.md) | UI changes, test coverage, GDS compliance, TODOs |
| [Migration Guide](../V1_TO_V2_MIGRATION.md) | Migrating V1 data to V2 |

---

## System Architecture

```mermaid
flowchart TB
    subgraph "Frontend Applications"
        PCUI[Probation Check-in UI<br/>Offender facing]
        MPOP[MPOP Dashboard<br/>Practitioner facing]
    end

    subgraph "eSupervision API V2"
        API[REST API Layer]
        SVC[Service Layer]
        REPO[Repository Layer]
        JOBS[Scheduled Jobs]
    end

    subgraph "External Services"
        NDILIUS[Ndilius<br/>PII Source of Truth]
        NOTIFY[GOV.UK Notify<br/>SMS/Email]
        S3[AWS S3<br/>Video/Image Storage]
        REKOG[AWS Rekognition<br/>Facial Recognition]
        SQS[AWS SQS<br/>Domain Events]
    end

    subgraph "Database"
        PG[(PostgreSQL<br/>V2 Tables)]
    end

    PCUI --> API
    MPOP --> API
    API --> SVC
    SVC --> REPO
    SVC --> NDILIUS
    SVC --> NOTIFY
    SVC --> S3
    SVC --> REKOG
    SVC --> SQS
    REPO --> PG
    JOBS --> SVC

    SQS -.->|Callback| NDILIUS
    NDILIUS -.->|Query Details| API
```

---

## Package Structure

```
uk.gov.justice.digital.hmpps.esupervisionapi.v2/
├── V2Entities.kt              # JPA entities
├── V2Dtos.kt                  # Data Transfer Objects
├── V2Repositories.kt          # Spring Data repositories
├── CheckinV2Service.kt        # Core checkin logic
├── NotificationV2Service.kt   # Notification facade
├── DomainEventService.kt      # Domain event publishing
│
├── checkin/                   # Checkin endpoints and services
├── setup/                     # Offender setup flow
├── offender/                  # Offender management
├── audit/                     # Event audit logging
├── jobs/                      # Background jobs
├── domain/                    # Domain value objects
└── infrastructure/            # Technical concerns (S3, Rekognition, etc.)
```

---

## API Endpoints Summary

### Setup
| Method | Endpoint | Description |
|--------|----------|-------------|
| `POST` | `/v2/offender_setup` | Start offender setup |
| `POST` | `/v2/offender_setup/{uuid}/complete` | Complete setup |

### Checkins
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/v2/offender_checkins` | List checkins |
| `GET` | `/v2/offender_checkins/{uuid}` | Get checkin |
| `POST` | `/v2/offender_checkins/{uuid}/submit` | Submit checkin |
| `POST` | `/v2/offender_checkins/{uuid}/review` | Complete review |

### Events (Ndilius Callbacks)
| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/v2/events/setup-completed/{uuid}` | Setup event details |
| `GET` | `/v2/events/checkin-submitted/{uuid}` | Submission details |
| `GET` | `/v2/events/checkin-reviewed/{uuid}` | Review details |

See [User Journeys](USER_JOURNEYS.md) for complete endpoint documentation.

---

## Data Flow Overview

```mermaid
flowchart LR
    subgraph "Setup Flow"
        A[Practitioner] -->|Register| B[offender_v2]
        B -->|Photo| C[S3]
    end

    subgraph "Checkin Flow"
        D[Offender] -->|Submit| E[offender_checkin_v2]
        E -->|Video| C
        E -->|Face Match| F[Rekognition]
    end

    subgraph "Review Flow"
        G[Practitioner] -->|Review| E
    end

    subgraph "Events"
        E -->|Publish| H[SQS]
        H -->|Callback| I[Ndilius]
        I -->|Details| J[/v2/events/*]
    end

    subgraph "Notifications"
        E -->|Trigger| K[GOV.UK Notify]
        K -->|SMS/Email| D
        K -->|Email| G
    end
```
