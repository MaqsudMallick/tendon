# Tendon — Design

Deep-dive companion to the [README](../README.md). This document covers the scanning algorithm,
the consistency model that keeps alerts trustworthy, the violation lifecycle, configuration, the
full API/metrics surface, security, and operational concerns.

## Contents

- [The problem](#the-problem)
- [Components](#components)
- [How verification works](#how-verification-works)
- [The hard part: transient orphans vs. real ones](#the-hard-part-transient-orphans-vs-real-ones)
- [Violation lifecycle](#violation-lifecycle)
- [Contract reference](#contract-reference)
- [Configuration](#configuration)
- [REST API](#rest-api)
- [Observability](#observability)
- [Security](#security)
- [Operational notes](#operational-notes)

## The problem

In a monolith, PostgreSQL refuses to insert `orders.customer_id = 999` if customer `999` doesn't
exist. After you split `orders` and `customers` into separate services with separate databases,
that check is gone — the two databases don't know about each other, and a foreign key cannot span
two Postgres instances.

Tendon restores the missing guardrail as **continuous verification** rather than a hard
constraint. It is intentionally an **observer**, not a participant in the write path: it cannot
*prevent* an orphan from being created, but it detects one after the fact, quickly and reliably.
That trade — no coupling, no latency on your services, no consistency risk — is the whole point.

## Components

```text
                 Cross-Service Integrity Platform
   ┌───────────────┬───────────────┬───────────────┬───────────────┐
   ▼               ▼               ▼               ▼               ▼
1. Contract     2. Scanner      3. Monitor      4. Violations   5. Repair
   Manager         Engine          & Metrics       & API           & Policy
   YAML rules      DB compare      scheduled       REST/dashboard  controlled
```

1. **Contract Manager** — parses and validates `integrity.yaml` into cross-service references
   (fails fast on unknown services, missing columns, or type-mismatched keys).
2. **Scanner Engine** — streams source keys, probes targets, and computes the orphan set.
3. **Monitor & Metrics** — schedules scans, ages candidates, evaluates SLOs, and exposes
   health/Prometheus metrics.
4. **Violations & API** — persists the violation lifecycle and serves it over a REST API.
5. **Repair & Policy** — proposal-only today; controlled options (investigate, mark expected,
   quarantine, manual repair). Never blindly deletes data.

## How verification works

The naïve version — "load every target ID into a `HashSet`, then check each source value" —
works for a demo and falls over on real data (a `customers` table won't fit in heap, and you'd
re-read both tables in full every cycle). A few decisions make it practical:

**You cannot join across two databases.** The source and target live in separate Postgres
instances, so there is no server-side `LEFT JOIN ... WHERE target.id IS NULL`. The engine brings
the two key sets together itself:

1. **Stream distinct source keys** using **keyset pagination**
   (`WHERE (id) > (:lastId) ORDER BY id LIMIT :batch`) — never `OFFSET`, which degrades to O(n²)
   over a large table.
2. **Probe the target in batches**, not per-row: `SELECT id FROM customers WHERE id = ANY(:ids)`
   for each batch of candidate keys. One round-trip per batch instead of one per order.
3. **Whatever the target returns is present; the difference is the orphan set** for that batch.
   An optional in-memory **bloom filter** of target keys can pre-filter obvious hits so most
   batches never touch the target DB — a false positive from the filter just means an extra
   confirming probe, never a missed orphan.

**Full vs incremental scans.** A `full` scan re-checks every source row and is the source of
truth; run it on a slow cadence (hourly/nightly). An `incremental` scan uses a **watermark** —
`WHERE id > :lastCheckpoint` (or an `updated_at` column) — to check only rows that appeared since
the last run, so the steady-state cost is proportional to new writes, not table size. The two
compose: cheap incremental passes for freshness, periodic full passes for correctness.

## The hard part: transient orphans vs. real ones

This is where a toy differs from something you can point at production. Because the tool scans two
databases at **different instants**, it will observe references that look broken but aren't:

```text
t0  scan reads customers  →  {1, 2, 3}
t1  order-service commits  order 500 → customer 4     (customer 4 already exists!)
t2  scan reads orders      →  sees 500 → 4
        └── "4 ∉ {1,2,3}"  → FALSE ORPHAN (the snapshot was just stale)
```

Replication lag, read-replica staleness, and normal create-order-before-scan-of-parent timing all
produce the same illusion. Reporting these as violations would make the tool cry wolf and get
muted. The engine treats a first sighting as a **candidate**, not a confirmed violation, and
filters it out through:

- **Aging / grace period.** A candidate must survive `max_orphan_age` before it is reported.
  Anything younger is assumed in-flight. Orphan *age* is a first-class field precisely because it
  separates "just written" from "genuinely dangling."
- **Re-verification against the live target.** Before confirming, the engine re-probes the target
  directly (bypassing any bloom filter / cached snapshot) so a merely-stale read is corrected
  instead of alerted.
- **Auto-resolution.** If a candidate disappears on a later scan (the parent showed up, or the
  child was deleted), it never escalates — and a *confirmed* violation that later resolves is
  closed automatically with an audit trail.

Net effect: what you get alerted on is the set of references that stayed broken longer than your
tolerance — not the constant churn of eventual consistency.

## Violation lifecycle

Each violation is a tracked record with identity `(reference, source_key)`, not a log line, so
repeated scans update one row instead of spamming duplicates:

```text
      first sighting              survived grace period
NEW ───────────────► CANDIDATE ───────────────────────► CONFIRMED
                        │                                    │
     parent appeared /  │                                    │  parent restored /
     child removed      ▼                                    ▼  child removed / repaired
                     RESOLVED ◄──────────────────────────────┘
                                                             │
                                        operator says "fine" ▼
                                                          IGNORED  (won't re-alert)
```

- `IGNORED` (a.k.a. "mark as expected") suppresses known-benign references so real ones stay
  visible.
- The store keeps `first_seen` / `last_seen` / `resolved_at` for every record — that history is
  what powers age, SLO evaluation, and post-incident forensics.

## Contract reference

Each reference maps `source.(service, table, column)` → `target.(service, table, column)`. The
engine treats the source column exactly like a foreign key the database can no longer enforce.

```yaml
references:
  - name: order_customer
    source:
      service: order
      table: orders
      column: customer_id
    target:
      service: customer
      table: customers
      column: id
    nullable: true              # guest checkout: NULL customer_id is valid, not an orphan
    where: "status <> 'DRAFT'"  # only scan rows the source considers live
    scan: incremental           # or: full
    slo:
      max_orphan_count: 0
      max_orphan_age: 5m        # tolerate briefly-in-flight references
```

| Option     | Meaning                                                                 |
| ---------- | ----------------------------------------------------------------------- |
| `nullable` | If `true`, `NULL` source values are valid and skipped (default `false`).|
| `where`    | SQL predicate narrowing which source rows are checked.                  |
| `scan`     | `full` or `incremental`.                                                |
| `slo`      | Objective for this relationship (`max_orphan_count`, `max_orphan_age`). |

An **integrity SLO** is why the accurate name is *Referential Integrity SLO*, not "foreign keys
for microservices":

```text
orphan_count = 0                    → ✓ SLO MET
orphan_count = 5, oldest = 42m      → ✗ SLO BREACHED
```

## Configuration

Databases are wired as ordinary Spring `DataSource`s, keyed by the `service` names used in the
contract. Give each one a **read-only** role (see [Security](#security)):

```yaml
integrity:
  contract: classpath:integrity.yaml
  scan:
    interval: 30s            # incremental cadence
    full-interval: 6h        # full-scan cadence
    batch-size: 1000         # keys probed per target round-trip
  datasources:
    order:
      url: jdbc:postgresql://order-db:5432/orders
      username: tendon_ro
      password: ${ORDER_DB_PASSWORD}
    customer:
      url: jdbc:postgresql://customer-db:5432/customers
      username: tendon_ro
      password: ${CUSTOMER_DB_PASSWORD}
    product:
      url: jdbc:postgresql://product-db:5432/products
      username: tendon_ro
      password: ${PRODUCT_DB_PASSWORD}
```

## REST API

```text
GET  /api/references                         list configured references + current status
GET  /api/references/{name}                  one reference, SLO state, orphan count
GET  /api/violations?status=CONFIRMED        query violations (filter by reference, status, age)
GET  /api/violations/{id}                    full detail incl. first_seen / last_seen
POST /api/violations/{id}/ignore             transition CONFIRMED/CANDIDATE → IGNORED
POST /api/references/{name}/scan             trigger an out-of-band scan
GET  /actuator/health                        UP only if every configured datasource is reachable
GET  /actuator/prometheus                    metrics (below)
```

## Observability

Prometheus metrics are labeled by `reference` so you can alert per relationship:

```text
integrity_orphans_total{reference="order_customer"}          gauge   current confirmed orphans
integrity_oldest_orphan_age_seconds{reference="..."}         gauge   drives max_orphan_age SLO
integrity_slo_breached{reference="..."}                      gauge   0 = met, 1 = breached
integrity_scan_duration_seconds{reference="...",mode="..."}  histogram
integrity_scan_rows_scanned_total{reference="..."}           counter
integrity_scan_errors_total{reference="...",service="..."}   counter   e.g. target DB unreachable
integrity_last_successful_scan_timestamp{reference="..."}    gauge   staleness watchdog
```

`integrity_last_successful_scan_timestamp` matters as much as the orphan count: a scanner that has
silently stopped reports **zero orphans** while integrity quietly rots. Alert on scan staleness,
not just on violations.

## Security

- **Least privilege.** The tool only ever needs `SELECT` on the specific tables/columns named in
  the contract. Provision a dedicated `tendon_ro` role and grant nothing else — the engine never
  writes to your service databases, even during "repair" (repair actions are proposed, and
  executed only through a channel you configure).
- **Data minimization.** Violation records store the **keys** involved (`order_id`,
  `customer_id`), not full rows, so no business PII is copied into the integrity store.
- **Blast radius.** Because access is read-only and out-of-band, a compromised or buggy scanner
  cannot corrupt or delete production data.

## Operational notes

- **Run one scanner leader.** If you deploy multiple replicas for availability, use leader
  election (or a distributed lock) so a reference is scanned by exactly one instance at a time —
  otherwise you double the read load and race on violation records.
- **Read replicas are fine, and encouraged**, for source and target reads — just be aware their
  lag widens the transient-orphan window, so size `max_orphan_age` above your worst-case
  replication lag.
- **The integrity store is the engine's own database** (violation lifecycle + checkpoints); it is
  separate from every service DB and the only thing the tool writes to.
