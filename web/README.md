# Vercel Public API Layer (`web/`)

This directory will contain the thin public API gateway deployed to `https://gmdarser.vercel.app`.

## Planned Components:
- **Serverless API Routes**:
  - `/api/health`: Health and upstream status probe.
  - `/api/transaction`: Transaction forwarding proxy to Apps Script.
  - `/api/taxonomy`: Cached taxonomy retrieval proxy.
- **Invariants**:
  - Stateless; no independent database.
  - Proxies traffic securely with upstream authentication.
  - Provides stable public HTTPS ingress for diverse mobile networks.
