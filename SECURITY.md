# Security policy

## Reporting a vulnerability

Please report vulnerabilities privately through
[GitHub Security Advisories](https://github.com/rus-artur4ik/marathon-shepherd/security/advisories/new)
rather than by opening a public issue.

Include what the issue is, how to reproduce it, and what an attacker gains. Expect an
acknowledgement within a week. This is a personal open-source project, not a funded
product — there is no bug bounty, and fixes are best-effort.

## Supported versions

Only the latest release on `master` receives fixes. There are no maintained release
branches yet.

## Threat model — read this before deploying

Marathon Shepherd brokers access to real Android devices. Understanding its trust
boundaries matters more than any single CVE.

### The Manager API is unauthenticated

`manager/service` exposes its REST API (`/api/v1/...`) with **no authentication**. Anyone
who can reach the port can allocate every device in the fleet, release other people's
sessions, and rewrite the provider configuration.

This is deliberate — the Manager is designed to sit on a trusted CI network — but it
means:

- **Never expose the Manager to the internet or to an untrusted network.** Bind it to a
  private interface, or put it behind a reverse proxy that authenticates callers.
- Treat access to the Manager port as equivalent to access to every device behind it.

Adapter bearer secrets are redacted (`<redacted>`) in `GET /api/v1/config` responses so
that reaching the Manager does not hand out the credentials for every adapter as well.
A `PUT` that echoes the placeholder back keeps the stored secret.

### Adapters authenticate, and must

Each adapter (`shepherd-adb`, `shepherd-farm`, `shepherd-cuttlefish`) requires a bearer
token, configured as `ADAPTER_SECRET` on the adapter and `secret:` in the Manager's
`msh.yaml`. Tokens are compared in constant time.

Leaving `ADAPTER_SECRET` blank disables authentication. That is supported **for local
development only** — an adapter proxies raw adb, so an unauthenticated one is complete
device control for anyone who can reach the port. The adapter logs a warning at startup
when it runs in this mode.

### Lease-scoped ADB proxies bind to all interfaces

`shepherd-adb` starts a per-lease TCP proxy so the test runner can reach the device.
These listeners bind `0.0.0.0` and are **not authenticated** — anyone who can reach the
port for the duration of a lease has adb access to that device.

Restrict the proxy port range at the firewall. The example Compose files publish the
whole range for convenience on a private lab network; review that before reusing them.

### TLS

The Cuttlefish adapter validates the orchestrator's TLS certificate by default. Setting
`CUTTLEFISH_ORCHESTRATOR_INSECURE_TLS=true` disables validation and logs a loud warning;
use it only against a local self-signed development host.

### Config files hold credentials

`msh.yaml` contains adapter bearer tokens. Keep it `chmod 600`, or bind it from a secret
manager. It is git-ignored, along with `.env`, so it cannot be committed by accident.

## What is out of scope

- Denial of service by exhausting the device pool from an authorised caller — the
  Manager is a scheduler, not a quota system.
- Anything requiring physical access to a device in the rack.
- The unauthenticated Manager API itself, which is documented above as a design
  assumption. Reports that it *can* be exposed unsafely are welcome as documentation
  issues; reports that it *is* unauthenticated are already covered here.
