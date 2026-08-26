# MONITORING/ALERTING NOTES

## Summary

We can emit custom events from our application code (via a `TelemetryClient`), then write a KQL query, which HMPPS infrastructure will run periodically and turn results into a Slack message.

## Querying data in Azure

After obtaining [Azure AD][azure-access] access, you should be able to access to [Application Insights][azure] and query the events we emit.

For example the following query shold return number of images deleted by our cron job in in the last 24h:

```
customEvents
| where timestamp > ago(1d)
| where cloud_RoleName == "hmpps-esupervision-api"
| where name == "ImageRetentionJobResults"
| extend DELETIONS = toint(customMeasurements.deleted)
| summarize arg_max(timestamp, DELETIONS)
| project DELETIONS
```

Unfortunately the query that we need to use for slack alerts uses sligthly different column names, so can't be used as is, but needs to be tweaked a bit.

See [Custom Alerts README][custom] for more information about how to set them up, and look at our [API custom alerts][api-custom].

## Slack integration

To receive these alerts in the Slack alerts channel, the bot needs access to it:
```
/invite @hmpps-sre-relay-bot
```

To debug problems with slack alerts, the channel `#hmpps-app-insights-alerts-dev` can be useful (e.g. it shows whether
the custom alert fired and if it was routed correctly).

## Our custom events

You can find the events we emit by looking up the `TelemetryEvent` enum and searching for usage of `TelemetryService.track()`.

## Useful bits of information

### Application Insights Severity Levels

Severity levels are [defined][severity] lik this: `Alert severity (0=Critical, 1=Error, 2=Warning, 3=Informational, 4=Verbose)`


[azure-access]: https://dsdmoj.atlassian.net/wiki/spaces/DSTT/pages/3897131056/DSO+Self-service+-+create+HMPPS+nomsdigitech+Azure+tenant+account
[custom]: https://github.com/ministryofjustice/hmpps-application-insights-alerts/blob/main/custom_alerts/README.md
[log query]:https://learn.microsoft.com/en-us/azure/azure-monitor/logs/log-query-overview
[azure]: https://portal.azure.com/#view/Microsoft_OperationsManagementSuite_Workspace/Logs.ReactView/resourceId/%2Fsubscriptions%2Fc27cfedb-f5e9-45e6-9642-0fad1a5c94e7%2FresourceGroups%2Fnomisapi-t3-rg%2Fproviders%2Fmicrosoft.insights%2Fcomponents%2Fnomisapi-t3/source/LogsBlade.AnalyticsShareLinkToQuery/q/H4sIAAAAAAAAA0XLPQ6AIAxA4d1TEBcmvIGbrg5ewDTQaBMLhAIuHt6%252Fwfm9zxbJgceKPktzqmPDhMruobhlDjtOwKj6Xm8coxgojrKBSPpfXz8QoxcKXjrBVMk%252BSLWfYvCwoil3kfYC23BX63AAAAA%253D/timespan/P1D
[api-custom]: https://github.com/ministryofjustice/hmpps-application-insights-alerts/blob/main/custom_alerts/dev/hmpps-esupervision-api.tf
[severity]:https://github.com/ministryofjustice/hmpps-application-insights-alerts/blob/main/modules/generic_alert/variables.tf#L61