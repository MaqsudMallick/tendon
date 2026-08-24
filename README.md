# Cross-Service Referential Integrity Platform

> A foreign-key monitor for microservices.

When you split one database into many, the database stops enforcing the relationships it used
to guarantee for free. `orders.customer_id` can now point at a customer that no longer exists,
and nothing complains. This project watches those cross-database relationships and tells you
when they break.

```text
        MONOLITH                              MICROSERVICES

      PostgreSQL DB                    Order DB          Customer DB
           │                          orders            customers
      ┌────┼────┐                     ───────           ─────────
   customers  orders                  customer_id  ──?──►  id
      ▲         │                          │
      └──── FK ─┘                          ▼
                                  Cross-Service Integrity Engine
   DB guarantees validity                  │
                                    ┌───────┴───────┐
                                    ▼               ▼
                                 healthy          alert → repair
```

## The problem

In a monolith, PostgreSQL refuses to insert `orders.customer_id = 999` if customer `999`
doesn't exist. After you split `orders` and `customers` into separate services with separate
databases, that check is gone — the two databases don't know about each other, and a foreign
key cannot span two Postgres instances.

This tool restores the missing guardrail as **continuous verification** rather than a hard
constraint:

> **Are the relationships between data in different services still valid?**

An **orphan** is a child row that references a parent that no longer exists
(`order 500 → customer 999`, where customer 999 is missing). The engine finds orphans, ages
them, reports them, and — only when you explicitly allow it — helps repair them.

## What it is (and isn't)

- ✅ A read-only integrity monitor: declare cross-service relationships, scan for orphans,
  expose violations and metrics.
- ✅ Deployable against production with **read-only** database credentials and **zero
  application code changes**.
- ❌ Not a distributed foreign key, not distributed transactions, not 2PC, not a saga engine.
  Detection first; automated repair only when the business opts in.

The tool is intentionally an **observer**, not a participant in the write path. It cannot
prevent an orphan from being created — it detects one after the fact, quickly and reliably.
That trade (no coupling, no latency on your services, no consistency risk) is the whole point.

## How a developer uses it

1. Point the tool at your service databases with **read-only** credentials.
2. Declare the cross-service relationships in an `integrity.yaml` contract.
3. Start the tool. It scans on a schedule, records violations, and publishes metrics.

No changes to `order-service`, `customer-service`, or `product-service`.

### 1. Declare an integrity contract

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
    # customer_id may legitimately be absent (guest checkout); NULLs are not orphans.
    nullable: true
    # only scan rows the source considers live
    where: "status <> 'DRAFT'"

  - name: order_product
    source:
      service: order
      table: order_items
      column: product_id
    target:
      service: product
      table: products
      column: id
    nullable: false
```

Each reference is `source.(service, table, column)` → `target.(service, table, column)`. The
engine treats the source column exactly like a foreign key the database can no longer enforce.

Supported per-reference options:

| Option     | Meaning                                                                 |
| ---------- | ----------------------------------------------------------------------- |
| `nullable` | If `true`, `NULL` source values are valid and skipped (default `false`).|
| `where`    | SQL predicate narrowing which source rows are checked.                  |
| `slo`      | Objective for this relationship (see below).                            |
| `scan`     | `full` or `incremental` (see [How verification works](#how-verification-works)). |

### 2. (Optional) Set an integrity SLO

Instead of demanding perfection, you define a target the way you would for uptime — because in
an eventually-consistent system a *transient* orphan is normal and only a *durable* one is a
bug:

```yaml
    slo:
      max_orphan_count: 0
      max_orphan_age: 5m      # tolerate briefly-in-flight references
```

```text
orphan_count = 0                    → ✓ SLO MET
orphan_count = 5, oldest = 42m      → ✗ SLO BREACHED
```

This is why the accurate name is **Referential Integrity SLO**, not "foreign keys for
microservices."

### 3. Read the results

```text
Cross-Service Integrity Dashboard
─────────────────────────────────────────────
Relationship            Status
─────────────────────────────────────────────
orders → customers      ✓ HEALTHY
orders → products       ⚠ 3 ORPHANS
payments → orders       ✓ HEALTHY
─────────────────────────────────────────────

Violation #1
  Order ID:    500
  Product ID:  9999   ← does not exist
  First seen:  15 minutes ago
  Last seen:   30 seconds ago
  Status:      CONFIRMED
```

## How verification works

The naïve version — "load every target ID into a `HashSet`, then check each source value" —
works for a demo and falls over on real data (a `customers` table won't fit in heap, and you'd
re-read both tables in full every cycle). A few engineering decisions make it practical:

**You cannot join across two databases.** The source and target live in separate Postgres
instances, so there is no server-side `LEFT JOIN ... WHERE target.id IS NULL`. The engine has to
bring the two key sets together itself:

1. **Stream distinct source keys** from the source DB using **keyset pagination**
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
`WHERE id > :lastCheckpoint` (or an `updated_at` column) — to check only rows that appeared
since the last run, so the steady-state cost is proportional to new writes, not table size. The
two compose: cheap incremental passes for freshness, periodic full passes for correctness.

## The hard part: transient orphans vs. real ones

This is where a toy differs from something you can point at production. Because the tool scans
two databases at **different instants**, it will observe references that look broken but aren't:

```text
t0  scan reads customers  →  {1, 2, 3}
t1  order-service commits  order 500 → customer 4     (customer 4 already exists!)
t2  scan reads orders      →  sees 500 → 4
        └── "4 ∉ {1,2,3}"  → FALSE ORPHAN (the snapshot was just stale)
```

Replication lag, read-replica staleness, and normal create-order-before-scan-of-parent timing
all produce the same illusion. Reporting these as violations would make the tool cry wolf and
get muted. The engine treats a first sighting as a **candidate**, not a confirmed violation, and
filters it out through:

- **Aging / grace period.** A candidate must survive `max_orphan_age` before it is reported.
  Anything younger is assumed in-flight. Orphan *age* is a first-class field precisely because it
  separates "just written" from "genuinely dangling."
- **Re-verification against the live target.** Before confirming, the engine re-probes the
  target directly (bypassing any bloom filter / cached snapshot) so a merely-stale read is
  corrected instead of alerted.
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
2. **Scanner Engine** — streams source keys, probes targets, and computes the orphan set per the
   algorithm above.
3. **Monitor & Metrics** — schedules scans, ages candidates, evaluates SLOs, and exposes
   health/Prometheus metrics.
4. **Violations & API** — persists the violation lifecycle and serves it over a REST API /
   dashboard.
5. **Repair & Policy** — offers controlled options (investigate, mark expected, quarantine,
   manual repair, or an explicitly configured policy). Never blindly deletes data.

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
      username: integrity_ro
      password: ${ORDER_DB_PASSWORD}
    customer:
      url: jdbc:postgresql://customer-db:5432/customers
      username: integrity_ro
      password: ${CUSTOMER_DB_PASSWORD}
    product:
      url: jdbc:postgresql://product-db:5432/products
      username: integrity_ro
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

`integrity_last_successful_scan_timestamp` matters as much as the orphan count: a scanner that
has silently stopped reports **zero orphans** while integrity quietly rots. Alert on scan
staleness, not just on violations.

## Security

- **Least privilege.** The tool only ever needs `SELECT` on the specific tables/columns named in
  the contract. Provision a dedicated `integrity_ro` role and grant nothing else — the engine
  never writes to your service databases, even during "repair" (repair actions are proposed, and
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

## V1 scope

Deliberately small, so V1 is genuinely useful on its own:

| Area        | V1                                             |
| ----------- | ---------------------------------------------- |
| Database    | PostgreSQL                                     |
| Input       | YAML integrity contracts                       |
| Access      | Read-only DB credentials                       |
| Function    | Cross-database reference / orphan checking     |
| Output      | Violations, metrics, REST API                  |
| Monitoring  | Prometheus                                     |
| Deployment  | Docker                                         |
| Stack       | Java + Spring Boot                             |

**Explicitly out of scope for V1:** distributed transactions, 2PC, Kafka/CDC platforms, sagas,
workflow engines, non-PostgreSQL databases, automatic destructive repair, complex UIs, Merkle
trees.

## Roadmap

- **V1 — Reconciliation.** PostgreSQL + YAML + read-only orphan detection + REST API +
  Prometheus. Periodic scans.
- **V2 — CDC / near-real-time.** Consume change events (e.g. Debezium) so a deleted parent is
  noticed in seconds instead of on the next scan, and maintain a local index of valid IDs for
  write-path validation without an RPC to the owning service. CDC also collapses the
  transient-orphan window, because deletes arrive as events rather than being inferred from a
  stale snapshot.
- **V3 — Large-scale reconciliation.** Merkle trees / hash buckets / incremental reconciliation
  so billion-row comparisons only compare the buckets that actually differ, instead of streaming
  both key sets end to end.
- **Opt-in auto-fixer (later).** Today repair is proposal-only. A future release may let you
  attach an explicit, per-reference remediation policy (e.g. quarantine the orphaned row, null a
  nullable FK, or run a configured action) that executes **only** when the business opts in and
  only through a write channel you provision — never against the read-only scan credentials.
  Detection stays the default; destructive repair is never automatic.

```text
ANALYZE → DESIGN → SPLIT → DECLARE → MONITOR → DETECT → REPAIR
```

## Getting started

> ⚠️ V1 is under active development; commands below reflect the intended developer workflow.

```bash
# Build
./gradlew build

# Run tests
./gradlew test

# Run the application
./gradlew bootRun
```

## Contributing

This project is a Spring Boot **modular monolith**. Before contributing, read
[CONTRIBUTING.md](./CONTRIBUTING.md) for the module structure (`api/` vs `internal/`),
inter-module communication rules, DTO conventions, and coding standards.

## License

TBD.
