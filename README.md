<h1 align="center">Tendon</h1>

<p align="center">
  <b>Referential integrity for microservices.</b><br>
  The foreign keys your database used to enforce — checked across the separate databases they now live in.
</p>

<p align="center">
  <img alt="status" src="https://img.shields.io/badge/status-pre--alpha-orange">
  <img alt="java" src="https://img.shields.io/badge/Java-17%2B-blue">
  <img alt="spring boot" src="https://img.shields.io/badge/Spring%20Boot-4.x-green">
  <img alt="license" src="https://img.shields.io/badge/license-Apache--2.0-blue">
</p>

---

When you split a monolith, PostgreSQL stops enforcing the relationships it used to guarantee for
free. `orders.customer_id` can now point at a customer that no longer exists, and nothing
complains — a foreign key can't span two databases.

**Tendon** reconnects them. Point it at your service databases with **read-only** credentials,
declare the cross-service relationships in a YAML file, and it continuously scans for **orphans**
(child rows referencing a parent that doesn't exist) — exposing violations, metrics, and alerts.
No changes to your services.

```text
orders.customer_id ──?──► customers.id      100 → 100  ✓   101 → 101  ✓   999 → ???  ✗ orphan
```

## Features

- 🔎 **Cross-database orphan detection** — verifies references PostgreSQL can no longer enforce.
- 🔒 **Read-only, zero code changes** — needs only `SELECT` on your service DBs.
- 📝 **Declarative contracts** — describe relationships in a simple `integrity.yaml`.
- 🎯 **Integrity SLOs** — tolerate brief in-flight references; alert only on durable breakage.
- 🚦 **Low-noise by design** — ages candidates and re-verifies to avoid false alarms from replication lag.
- 📊 **Prometheus + REST** — metrics, health, and a queryable violations API out of the box.

## Quick start

> ⚠️ Pre-alpha — under active development.

**1. Describe the relationships** (`integrity.yaml`):

```yaml
references:
  - name: order_customer
    source: { service: order,   table: orders, column: customer_id }
    target: { service: customer, table: customers, column: id }
    slo: { max_orphan_count: 0, max_orphan_age: 5m }
```

**2. Point Tendon at your databases** (`application.yaml`) with a read-only role, and set the
API credentials:

```yaml
integrity:
  contract: classpath:integrity.yaml
  security:
    reader:   { username: tendon_reader,   password: ${TENDON_READER_PASSWORD} }
    operator: { username: tendon_operator, password: ${TENDON_OPERATOR_PASSWORD} }
  datasources:
    order:    { url: jdbc:postgresql://order-db:5432/orders,      username: tendon_ro, password: ${ORDER_DB_PASSWORD} }
    customer: { url: jdbc:postgresql://customer-db:5432/customers, username: tendon_ro, password: ${CUSTOMER_DB_PASSWORD} }
```

**3. Run it:**

```bash
./gradlew bootRun
# then:
curl -u tendon_reader:$TENDON_READER_PASSWORD localhost:8080/api/violations?status=CONFIRMED
curl -u tendon_reader:$TENDON_READER_PASSWORD localhost:8080/actuator/prometheus | grep integrity_
```

## How it works

Tendon can't `JOIN` across two databases, so it brings the key sets together itself:

1. Streams distinct source keys (keyset pagination — no `OFFSET`).
2. Probes the target in batches: `SELECT id FROM customers WHERE id = ANY(?)`.
3. Whatever the target doesn't return is an orphan **candidate**.

Because the two databases are read at different instants, a freshly-written reference can *look*
orphaned when it isn't (replication lag, snapshot skew). Tendon suppresses these false alarms:
a candidate is only reported once it **survives a grace period** (`max_orphan_age`) and is
**re-verified against the live target**. What you get alerted on is durable breakage — not the
normal churn of eventual consistency.

📖 Full algorithm, consistency model, violation lifecycle, and operational notes:
[**docs/DESIGN.md**](./docs/DESIGN.md).

## API & metrics

| Endpoint | |
|---|---|
| `GET /api/references` | configured references + status |
| `GET /api/violations` | query violations (filter by status/reference/age) |
| `POST /api/violations/{id}/ignore` | mark a known-benign reference |
| `GET /actuator/prometheus` | metrics: `integrity_orphans_total`, `integrity_oldest_orphan_age_seconds`, `integrity_slo_breached`, `integrity_last_successful_scan_timestamp`, … |

Everything above needs HTTP Basic credentials: `reader` for the `GET`s, `operator` for the
`POST`. `GET /actuator/health` is the one endpoint left open, so Kubernetes probes need no
secret — see [Security](./docs/DESIGN.md#security).

## Roadmap

- **V1** *(current)* — PostgreSQL, YAML contracts, read-only periodic reconciliation, REST + Prometheus.
- **V2** — CDC / near-real-time detection via Debezium.
- **V3** — Large-scale reconciliation with Merkle trees / hash buckets.
- **Later** — opt-in, policy-driven auto-repair (never on read-only credentials).

Progress is tracked in [issues](https://github.com/MaqsudMallick/tendon/issues).

## Contributing

Tendon is a Spring Boot **modular monolith**. See [CONTRIBUTING.md](./CONTRIBUTING.md) for the
architecture and coding standards, and [AGENTS.md](./AGENTS.md) for the AI-usage policy.

## License

Licensed under the [Apache License 2.0](./LICENSE).
