# nDelius Integration

This document contains implementation notes about E-Supervision integration with nDelis.

## nDelius and E-Supervision interactions

There are two main ways we interact with nDelius:
- The nDelius integrations api:
  - see `api.base.url.ndilius-api` config api
- SQS message queue
  - see `DomainEventPublisher` using `hmppseventtopic` topic
  - depending on the message, nDelius might call us back to get 
    details of the event using our `EventResource`, exposing resources
    under `/v2/events`

## Tracking message delivery status

E-supervision tracks whether our attempt at sending a message to nDelius succeeded or failed via the `outbox_items` table. The table records message type, and entity id (Long). A constraint on the (type, entity_id) tuple makes sure we don't attempt to send the same message twice. Which entity's ID is actually used depends on the event. For example, most check-in events use the check-in ID. But it's possible for a check-in to have multiple annotations; the mentioned constraint wouldn't allow us to insert more than one outbox item, so the annotation ID (which is `OffenderEventLog` ID) is used instead (it also makes more sense for the outbox item to the reference the specific annotation).

For offender related events, we need to allow for potentially multiple deactivation/reactivation events for the same offender. As in the check-in annotation example, to not violate the outbox constraint, we pick another entity for this purpose: `OffenderSetup` (`offender_setup_v2` table). In our current onboarding code (see `OffenderSetupService`), we ensure there is one `OffenderSetup` record per activation. In other words, a re-activation will create a new `OffenderSetup` record.
 