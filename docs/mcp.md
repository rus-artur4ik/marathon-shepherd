# Devices for AI agents (MCP)

Marathon Shepherd speaks the [Model Context Protocol](https://modelcontextprotocol.io), so
agents such as Claude Code can find, lease and return Android devices themselves. There are two
ways to connect:

- **The manager's `/mcp` endpoint** (Streamable HTTP). Nothing to install; every request carries
  the agent's API key.
- **`shepherd-mcp`**, a local stdio server for hosts that start MCP servers as processes. It
  talks to the manager over HTTP and releases the sessions it opened when the agent exits.

Both serve the same tools, and both act as the API key's client: an agent sees what that client
may see and holds devices against that client's quota.

## Give the agent its own key

```bash
mshctl clients create --name claude-agent --role user --max-devices 2 --max-lifetime 7200
```

A dedicated `user` client keeps the agent's sessions and audit entries apart from CI, and its
quota caps what the agent can hold no matter what it asks for. A `viewer` key can list devices
but not lease them.

## Connect over HTTP

Claude Code:

```bash
claude mcp add --transport http shepherd https://shepherd.example.com/mcp \
  --header "Authorization: Bearer msh_..."
```

Other clients take the same URL and header, for example in a `.mcp.json`:

```json
{
  "mcpServers": {
    "shepherd": {
      "type": "http",
      "url": "https://shepherd.example.com/mcp",
      "headers": { "Authorization": "Bearer msh_..." }
    }
  }
}
```

The endpoint is stateless: each request is answered with JSON, and there is no event stream or
`Mcp-Session-Id`. Put TLS in front of the manager if agents reach it over an untrusted network;
the key travels in every request.

## Connect over stdio

Build the binary once:

```bash
./gradlew :manager:mcp:installDist   # manager/mcp/build/install/shepherd-mcp/bin/shepherd-mcp
```

Claude Code:

```bash
claude mcp add shepherd \
  --env MSH_URL=https://shepherd.example.com --env MSH_TOKEN=msh_... \
  -- /path/to/shepherd-mcp/bin/shepherd-mcp
```

Or in a client's JSON configuration:

```json
{
  "mcpServers": {
    "shepherd": {
      "command": "/path/to/shepherd-mcp/bin/shepherd-mcp",
      "env": { "MSH_URL": "https://shepherd.example.com", "MSH_TOKEN": "msh_..." }
    }
  }
}
```

`shepherd-mcp` logs to stderr (stdout carries the protocol) and releases every session it opened
when the host closes its input or stops the process.

## Tools

| Tool               | What it does                                                                  |
|--------------------|-------------------------------------------------------------------------------|
| `list_devices`     | Devices with state, type, API level, model, labels and holder; provider pools |
| `acquire_devices`  | Lease devices by count, API levels, type, device ids or labels                 |
| `wait_for_session` | Wait for a queued session to get its devices                                   |
| `get_session`      | A session's state, adb servers and devices; also keeps it alive                |
| `list_my_sessions` | The key's active sessions, quota and usage                                     |
| `extend_session`   | Keep a session for longer, on providers that can renew leases                  |
| `release_session`  | Return the devices                                                             |

A READY session answers with commands like `adb -H 10.0.0.5 -P 7600 devices`, which the agent can
run as they are. The resources `shepherd://devices` and `shepherd://sessions` offer the same data
as JSON.

## Guardrails

Agents forget to clean up more often than CI jobs do, so sessions opened through MCP are small,
short and released soon after the agent stops checking in, on top of the key's quota:

| `msh.yaml` (`mcp:`)    | `shepherd-mcp` variable        | Default | Meaning                                              |
|------------------------|--------------------------------|---------|------------------------------------------------------|
| `enabled`              | —                              | `true`  | Serve `/mcp`                                         |
| `maxDevicesPerSession` | `MSH_MCP_MAX_DEVICES`          | 2       | Most devices per `acquire_devices`                   |
| `defaultTtlSeconds`    | `MSH_MCP_DEFAULT_TTL_SECONDS`  | 1800    | Lifetime when the agent does not ask for one         |
| `maxTtlSeconds`        | `MSH_MCP_MAX_TTL_SECONDS`      | 14400   | Longest lifetime an agent may ask for or extend to   |
| `idleTimeoutSeconds`   | `MSH_MCP_IDLE_TIMEOUT_SECONDS` | 900     | Released after this long without `get_session`       |
| `maxWaitSeconds`       | `MSH_MCP_MAX_WAIT_SECONDS`     | 60      | Longest one tool call waits for busy devices         |

Sessions opened through MCP carry `metadata.client = mcp`, so `mshctl list` and the event stream
show which sessions belong to agents.
