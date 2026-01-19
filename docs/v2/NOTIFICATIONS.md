# V2 Notification System

The V2 notification system sends SMS and email notifications via GOV.UK Notify.

---

## Architecture

```mermaid
flowchart TB
    subgraph "Triggering Events"
        E1[Setup Completed]
        E2[Checkin Created]
        E3[Checkin Submitted]
        E4[Checkin Reviewed]
        E5[Checkin Expired]
    end

    subgraph "NotificationOrchestratorV2Service"
        direction TB
        BUILD[Build Notifications<br/>Determine Recipients]
        PERSIST[Persist to<br/>GenericNotificationV2]
        SEND[Send via<br/>NotifyGatewayService]
        STATUS[Update Status<br/>Success/Failure]
    end

    subgraph "Channels"
        SMS[SMS via Notify]
        EMAIL[Email via Notify]
    end

    subgraph "Recipients"
        OFF[Offender]
        PRAC[Practitioner]
    end

    E1 --> BUILD
    E2 --> BUILD
    E3 --> BUILD
    E4 --> BUILD
    E5 --> BUILD

    BUILD --> PERSIST
    PERSIST --> SEND
    SEND --> SMS
    SEND --> EMAIL
    SEND --> STATUS

    SMS --> OFF
    EMAIL --> OFF
    EMAIL --> PRAC
```

---

## Notification Matrix

| Event | Offender SMS | Offender Email | Practitioner Email |
|-------|-------------|----------------|-------------------|
| Setup Completed | Yes | Yes | No |
| Checkin Created | Yes | Yes | No |
| Checkin Submitted | Yes | Yes | Yes |
| Checkin Reviewed | No | No | No |
| Checkin Expired | No | No | Yes |

---

## Notification Flow

```mermaid
sequenceDiagram
    autonumber
    participant Trigger as Event Trigger
    participant Orch as NotificationOrchestratorV2Service
    participant Persist as NotificationPersistenceService
    participant Gateway as NotifyGatewayService
    participant Notify as GOV.UK Notify API
    participant DB as Database

    Trigger->>Orch: sendCheckinSubmittedNotifications(checkin)

    rect rgb(230, 245, 255)
        note over Orch,DB: Build Notifications
        Orch->>Persist: buildOffenderNotifications(offender, details, type)
        Persist-->>Orch: List<NotificationWithRecipient>
        Orch->>Persist: buildPractitionerNotifications(offender, details, checkin, type)
        Persist-->>Orch: List<NotificationWithRecipient>
    end

    rect rgb(255, 245, 230)
        note over Orch,DB: Persist Notifications
        Orch->>Persist: saveNotifications(notifications)
        Persist->>DB: INSERT INTO generic_notification_v2
        Persist-->>Orch: Saved notifications with IDs
    end

    rect rgb(230, 255, 230)
        note over Orch,Notify: Send Each Notification
        loop For each notification
            Orch->>Gateway: send(channel, templateId, recipient, personalisation)
            Gateway->>Notify: POST /v2/notifications/{email|sms}
            Notify-->>Gateway: notifyId
            Gateway-->>Orch: notifyId
            Orch->>Persist: updateSingleNotificationStatus(notification, success, notifyId)
            Persist->>DB: UPDATE status, sent_at, notify_id
        end
    end
```

---

## Service Components

```mermaid
classDiagram
    class NotificationV2Service {
        +sendSetupCompletedNotifications(offender, contactDetails)
        +sendCheckinCreatedNotifications(checkin, contactDetails)
        +sendCheckinSubmittedNotifications(checkin, contactDetails)
        +sendCheckinReviewedNotifications(checkin, contactDetails)
        +sendCheckinExpiredNotifications(checkin, contactDetails)
    }

    class NotificationOrchestratorV2Service {
        -notificationPersistence
        -notifyGateway
        -domainEventService
        -eventAuditService
        +sendSetupCompletedNotifications()
        +sendCheckinCreatedNotifications()
        +sendCheckinSubmittedNotifications()
        +sendCheckinReviewedNotifications()
        +sendCheckinExpiredNotifications()
        -processAndSendNotifications()
    }

    class NotificationPersistenceService {
        +buildOffenderNotifications()
        +buildPractitionerNotifications()
        +saveNotifications()
        +updateSingleNotificationStatus()
    }

    class NotifyGatewayService {
        +send(channel, templateId, recipient, personalisation, reference)
    }

    NotificationV2Service --> NotificationOrchestratorV2Service
    NotificationOrchestratorV2Service --> NotificationPersistenceService
    NotificationOrchestratorV2Service --> NotifyGatewayService
```

---

## GOV.UK Notify Templates

### Template Personalisation

Each notification type has specific personalisation fields:

**Setup Completed (Registration Confirmation):**
```
name: "John Smith"
date: "Monday 15 January 2025"
frequency: "week" | "two weeks" | "four weeks" | "eight weeks"
```

**Checkin Created (Checkin Invite):**
```
firstName: "John"
lastName: "Smith"
date: "Wednesday 17 January 2025"  // Final day to submit
url: "https://checkin.example.com/v2/checkin/{uuid}"
```

**Checkin Submitted (Confirmation):**
```
name: "John Smith"
practitionerName: "practitioner123"
number: "2"  // Number of flags
contactRequestFlag: "yes" // Yes or no values to determine the optional content in notify template
dashboardSubmissionUrl: "https://mpop.example.com/review/{uuid}"

```

**Checkin Expired (Practitioner Alert):**
```
practitionerName: "practitioner123"
name: "John Smith"
popDashboardUrl: "https://mpop.example.com/review/{uuid}"
```

---

## Database Schema

```sql
CREATE TABLE generic_notification_v2 (
    id BIGINT PRIMARY KEY,
    notification_id UUID UNIQUE NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    recipient_type VARCHAR(50) NOT NULL,  -- 'OFFENDER' or 'PRACTITIONER'
    channel VARCHAR(50) NOT NULL,         -- 'SMS' or 'EMAIL'
    offender_id BIGINT REFERENCES offender_v2(id),
    practitioner_id VARCHAR(255),
    status VARCHAR(50),
    reference VARCHAR(255) NOT NULL,
    template_id VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP,
    updated_at TIMESTAMP,
    error_message VARCHAR(1000)
);
```

---

## Configuration

```yaml
notify:
  api-key: ${NOTIFY_API_KEY}
  templates:
    registration-confirmation:
      sms: "35274201-ecef-493b-b4e1-86e60d071142"
      email: "7e63c01c-05df-4b2c-a77c-57d2412793a1"
    checkin-invite:
      sms: "41f78d9f-95d1-45c4-bad2-ec53fcddc017"
      email: "3806414e-083e-410e-825a-76e2c72bbd8b"
    checkin-submitted-offender:
      sms: "80695bae-3917-436a-824b-c61ce04ca4a3"
      email: "8558a963-0d76-4ad5-82d3-3eb536c76563"
    checkin-submitted-practitioner:
      email: "${PRACTITIONER_CHECKIN_SUBMITTED_TEMPLATE_ID}"
    checkin-expired-practitioner:
      email: "${PRACTITIONER_CHECKIN_EXPIRED_TEMPLATE_ID}"
```

---

## Contact Preference

V2 supports offender contact preference stored in `offender_v2.contact_preference`:

| Preference | Behaviour |
|------------|-----------|
| `PHONE` | Primary: SMS, Fallback: Email |
| `EMAIL` | Primary: Email, Fallback: SMS |

If contact details are missing, notification is skipped with warning log.
