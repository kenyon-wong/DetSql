# Changelog

## Unreleased
- Change: Vulnerability count now uses unique key per finding instead of per-payload increments.
  - Uniqueness key = METHOD | host:port + path | paramName
  - Default ports normalized (http=80, https=443)
  - Aggregation centralized in Statistics.recordFromEntries(url, method, entries)
  - MyHttpHandler delegates to the statistics layer (no business logic leakage)
- Fix: UI vulns counter now reads from Statistics.getVulnerabilitiesFound() to avoid payload-based inflation.
- Fix: UI Tested counter now reads from Statistics.getRequestsProcessed() instead of log index to avoid overcount in Repeater.
- Tests: Added StatisticsVulnerabilityCountTest covering uniqueness, methods, host/port distinction, and default-port normalization.
