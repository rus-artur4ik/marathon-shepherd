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

### The Manager API needs an API key

Since 0.2.0 every `/api/v1/...` call and the MCP endpoint `/mcp` require
`Authorization: Bearer <key>`. A call without one gets `401`.

- Keys are `msh_` plus 40 random characters from a cryptographically secure source, and
  only their SHA-256 digest is stored. A key is shown once, at creation or rotation.
- On first start the manager creates an admin key, prints it once on stdout and writes it
  to `<dataDir>/initial-admin-token` with owner-only permissions. Create a client per
  consumer with it, then delete that file. `MSH_ADMIN_TOKEN` provisions a known admin key
  instead, for deployments that manage secrets themselves.
- Each client has a role — `admin`, `user`, `viewer` or `provider` — and optional quotas
  (devices held at once, session lifetime, queue priority). A session may only be waited
  on, extended or released by its owner or an admin.
- Session, client, device and configuration changes are recorded in an audit log with the
  actor and the calling address, readable at `GET /api/v1/audit`.
- Keys are bearer credentials with no expiry: rotate with `mshctl clients rotate` and
  revoke with `mshctl clients revoke` (optionally releasing the client's sessions).

What is still open by design:

- **`/live`, `/ready`, `/health`, `/metrics`, `/openapi.yaml` and `/docs` are public.**
  `/metrics` and `/health` describe the fleet — provider names, device counts, session
  counts. Keep the port on a private network even though the API itself is authenticated.
- **There is no TLS.** The manager speaks plain HTTP, so a key travels in the clear.
  Terminate TLS in front of it (ingress, reverse proxy) whenever callers — CI, developer
  laptops, AI agents — reach it over a network you do not control.
- Adapter bearer secrets are redacted (`<redacted>`) in `GET /api/v1/config`, so an admin
  key does not hand out the credentials for every adapter. A `PUT` that echoes the
  placeholder back keeps the stored secret.

### Agents get a key like anyone else

The MCP endpoint and the `shepherd-mcp` binary act as the API key they are given: an agent
can do exactly what that client may do. Give each agent its own `user` client with a device
quota. The MCP guardrails (device cap, short lifetimes, idle timeout) limit accidents, not
a determined caller with a valid key.

### Adapters authenticate, and must

Each adapter (`shepherd-adb`, `shepherd-farm`, `shepherd-cuttlefish`) requires a bearer
token, configured as `ADAPTER_SECRET` on the adapter and `secret:` in the Manager's
`msh.yaml`. Tokens are compared in constant time.

Leaving `ADAPTER_SECRET` blank disables authentication. That is supported **for local
development only** — an adapter proxies raw adb, so an unauthenticated one is complete
device control for anyone who can reach the port. The adapter logs a warning at startup
when it runs in this mode.

An adapter may also register itself with the manager instead of being listed in `msh.yaml`.
Registration needs a key with the `provider` role (`MSH_REGISTRATION_TOKEN`), and the
adapter sends its own `ADAPTER_SECRET` in the registration so the manager can call it back.
A `provider` key can register and refresh providers; it cannot read or lease devices.

### Lease-scoped ADB proxies bind to all interfaces

`shepherd-adb` starts a per-lease TCP proxy so the test runner can reach the device. These
listeners bind `0.0.0.0` and are **not authenticated** — anyone who can reach the port for
the duration of a lease has adb access to that device.

Restrict the proxy port range at the firewall. The example Compose files publish the whole
range for convenience on a private lab network; review that before reusing them.

### TLS to the Cuttlefish orchestrator

The Cuttlefish adapter validates the orchestrator's TLS certificate by default. Setting
`CUTTLEFISH_ORCHESTRATOR_INSECURE_TLS=true` disables validation and logs a loud warning;
use it only against a local self-signed development host.

### Config files and databases hold credentials

`msh.yaml` contains adapter bearer tokens. Keep it `chmod 600`, or bind it from a secret
manager; the Helm chart keeps it in a Secret. It is git-ignored, along with `.env`, so it
cannot be committed by accident.

The manager's database holds key digests, session history and the audit log. With
`MSH_DB_URL` it is a Postgres database whose URL carries credentials: pass it from a secret,
not on the command line. The manager never logs the URL's credentials.

## What is out of scope

- Denial of service by a caller with a valid key that stays inside its quota — the manager
  schedules devices, it does not police intent.
- Anything requiring physical access to a device in the rack.
- The public probe, metrics and documentation endpoints, and the absence of TLS, which are
  documented above as deployment assumptions. Reports that they *can* be exposed unsafely
  are welcome as documentation issues.
