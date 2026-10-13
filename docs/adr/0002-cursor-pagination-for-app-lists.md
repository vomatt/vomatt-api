# Cursor pagination for every app-facing list

Every list endpoint the app consumes (feed / explore / search, comments, my polls, user search, popular tags) uses cursor (keyset) pagination, replacing offset `PageResponse`. Infinite scroll with offsets duplicates items when new rows arrive at the head and slows at depth; one rule for all app lists means one response shape and one client hook. This was a deliberate breaking change, shipped together with the vomatt-web redesign rather than run side by side. Offset pagination is kept only for future admin screens that need page jumps or total counts.

## Consequences

- Lists sorted by a mutable value (popular tags by usage; Open Polls by end time, which an early Close changes) can skip or repeat an item while the user scrolls. Clients de-duplicate by id.
- Totals (e.g. tab badges) are not part of list responses and need their own field or endpoint.
