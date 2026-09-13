# Signing in

People sign in to Marathon Shepherd with a username and password by default. You can add an
LDAP or Active Directory server, any number of OIDC providers (Keycloak, Google, Microsoft Entra
ID, Okta, GitLab, …), or both. Their groups decide who is an admin, a user or a viewer.

API keys are separate and always work: CI jobs, scripts and agents use client keys, and people
use personal tokens (see [clients.md](clients.md)).

| Who | Signs in with | Lands in |
|-----|---------------|----------|
| People | password, LDAP or an OIDC provider | the web UI, with a session cookie |
| People on the command line | `mshctl login`, which creates a personal token | `mshctl`, scripts, agents |
| CI jobs, scripts, bots | an API client key (`mshctl clients create`) | the REST API and `/mcp` |

## The first admin

On first start the manager creates the user `admin` with a one-time password. It prints the
password once and writes it to `<MSH_DATA_DIR>/initial-admin-password`, readable by the manager's
user only. Sign in, choose a password, then delete the file.

Set `MSH_ADMIN_PASSWORD` to choose the first password yourself; it does not have to be changed.
`MSH_ADMIN_TOKEN` still provisions a static admin API key for automation.

## Local accounts

Admins create people and hand them a one-time password:

```bash
mshctl users create --username dana --role user --display-name "Dana Scully"
mshctl users reset-password usr_…      # a new one-time password; signs them out everywhere
mshctl users update usr_… --role admin
mshctl users disable usr_… --release-sessions
```

Everyone with a one-time password chooses their own at first sign-in, in the browser or with
`mshctl passwd --username dana`. Passwords are stored as PBKDF2-HMAC-SHA256 hashes (600,000
iterations). New passwords need at least `auth.local.minPasswordLength` characters (10 by
default) and must not be the username. Set `auth.local.enabled: false` to allow directory sign-in
only.

## LDAP and Active Directory

```yaml
auth:
  ldap:
    url: "ldaps://dc1.corp.example.com:636"   # or ldap://…:389 with startTls: true
    bindDn: "CN=svc-shepherd,OU=Service Accounts,DC=corp,DC=example,DC=com"
    bindPasswordEnv: MSH_LDAP_BIND_PASSWORD      # or bindPassword: "…"
    userSearchBase: "DC=corp,DC=example,DC=com"
    userSearchFilter: "(&(objectClass=user)(sAMAccountName={0}))"
    usernameAttribute: sAMAccountName
    displayNameAttribute: displayName
    emailAttribute: mail
    groupAttribute: memberOf
    roleMapping:
      "Shepherd Admins": admin
      "Mobile QA": user
    defaultRole: viewer        # null turns away people in none of the groups
```

The manager binds with the service account, finds exactly one entry for the username, and binds
as that entry with the password that was typed. The username goes into the filter as a
parameter, so characters like `*` or `)` cannot change what it matches, and an empty password
is refused before any bind, because many servers treat it as an anonymous bind.

For OpenLDAP-style directories use `userSearchFilter: "(uid={0})"` and either `groupAttribute:
memberOf` (with the memberof overlay) or a group search:

```yaml
    groupAttribute: null
    groupSearchBase: "ou=groups,dc=example,dc=com"
    groupSearchFilter: "(member={0})"   # {0} is the user's DN, {1} the username
    groupNameAttribute: cn
```

Group names match by their common name or their full DN. Active Directory compares names
regardless of case. A username that exists as a local account always signs in locally.

## OIDC providers

```yaml
auth:
  publicUrl: "https://shepherd.example.com"     # redirect URIs are built from it
  oidc:
    - id: keycloak
      displayName: "Company SSO"
      issuer: "https://sso.example.com/realms/engineering"
      clientId: marathon-shepherd
      clientSecretEnv: MSH_OIDC_KEYCLOAK_SECRET
      groupsClaim: groups
      roleMapping:
        shepherd-admins: admin
        mobile-qa: user
      defaultRole: viewer
```

Register `https://shepherd.example.com/auth/oidc/keycloak/callback` as the redirect URI of a
confidential client with the provider. The sign-in page shows one button per provider. When a
provider cannot be reached or does not sign someone in, the browser comes back to the sign-in page,
which says why.

The manager uses the authorization code flow with PKCE, exchanges the code itself, and trusts
the ID token only after checking its signature against the provider's published keys (RSA or EC,
never `none` or HMAC), its issuer, audience, expiry and nonce. It reads groups from the ID token,
or from the userinfo endpoint when the token has none.

| Provider | `issuer` | Groups |
|----------|----------|--------|
| Keycloak | `https://<host>/realms/<realm>` | add a *Group Membership* mapper named `groups` (full path off) |
| Microsoft Entra ID | `https://login.microsoftonline.com/<tenant-id>/v2.0` | enable the `groups` claim; values are group object ids |
| Okta | `https://<org>.okta.com/oauth2/default` | add a `groups` claim to the ID token |
| Google | `https://accounts.google.com` | no groups: use `defaultRole` and promote people with `mshctl users update` |
| GitLab | `https://gitlab.com` | `groups` lists group paths |

People are recognised at the next sign-in by the provider and their subject id, never by name
alone, so an OIDC account can never take over a local account with the same username. A name that
is already taken gets `@<provider>` appended.

When `roleMapping` is set, every sign-in refreshes the role from the groups and admins cannot
edit it. Without it, people start with `defaultRole` and admins change their role.

## Browser sessions

A successful sign-in sets the `msh_session` cookie: `HttpOnly`, `SameSite=Lax`, and `Secure`
whenever `auth.publicUrl` is https (or `auth.sessions.secureCookie` says so). The manager keeps
only a hash of the cookie. A session ends after `idleTimeoutMinutes` without a request (8 hours)
or `maxLifetimeHours` after sign-in (7 days), at sign-out, when the password changes, and when the
user is disabled.

Requests made with the cookie that change anything must send the session's CSRF token in the
`X-CSRF-Token` header; the web UI does this for you.

After `maxFailedAttempts` failed sign-ins in a row (5) a username is locked for `lockoutMinutes`
(15), and an address gets four times as many tries. Every sign-in, sign-out and account change is
in the audit log (`mshctl audit --action auth.` and `--action user.`).

```yaml
auth:
  sessions:
    idleTimeoutMinutes: 480
    maxLifetimeHours: 168
    maxFailedAttempts: 5
    lockoutMinutes: 15
```

## Personal tokens

```bash
mshctl login --username dana          # prompts for the password, saves a 90-day token
mshctl whoami
mshctl tokens create --name "nightly script" --expires-days 30
mshctl tokens
mshctl tokens revoke tok_…
mshctl logout                          # revokes the saved token
```

A personal token acts as its person, with their role and quota, in `mshctl`, scripts, the
Kotlin and Python clients, and MCP. It stops working when it is revoked or expires, when its
person is disabled, and while their password is a one-time password.
