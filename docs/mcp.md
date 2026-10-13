# MCP servers

Petri agents can use tools from [Model Context Protocol](https://modelcontextprotocol.io) servers.
MCP servers belong to **you**, like Git accounts and personal LLM providers; there is no
administrator configuration. A project only decides which of its creator's servers its agents may use.

## Add a server

Avatar menu → **MCP servers** (`/mcp-servers`) → **Add server**.

| Field | Meaning |
| --- | --- |
| Server URL | The MCP endpoint over HTTP(S), for example `https://mcp.example.com/mcp`. |
| Transport | *Automatic* tries Streamable HTTP and falls back to the older HTTP + SSE transport. |
| Authentication | See below. |

Use **Test** to connect and list the server's tools; **Tools** shows what was found and which tools
declare themselves read-only (`readOnlyHint`).

stdio servers (a command Petri would have to start) are not supported: Petri only connects to
remote servers.

### Authentication

- **None**.
- **Bearer token / API key**: sent as `Authorization: Bearer …`.
- **Custom headers**: one `Name: value` per line (for example `X-Api-Key`). `Host`, `Content-Type`,
  `Accept` and the MCP protocol headers are set by Petri and cannot be overridden.
- **OAuth sign-in** (OAuth 2.1, authorization code with PKCE): Petri discovers the server's
  authorization server (RFC 9728 / RFC 8414), registers itself as a public client when the server
  supports dynamic registration (RFC 7591) or uses the client ID and secret you enter, sends the
  `resource` parameter (RFC 8707), and refreshes the token automatically. Choose **Sign in** in the
  server's edit dialog; the callback is `/mcp-servers/oauth/callback`, so the address users reach Petri
  under must be the one the authorization server may redirect to.

Secrets and tokens are encrypted at rest with the instance master key and are never shown again; a
saved header or secret appears as `********` and keeps its value when left unchanged.

## Use a server in a project

Project settings → **MCP** (`/projects/{id}/settings/mcp`): switch each server on for the project and,
optionally, enable **Auto-approve**.

- **Read-only tools** (the server annotates them `readOnlyHint: true`) run directly.
- **All other tools** wait in the review tray as an *MCP* card with the arguments; the agent run
  pauses until you approve or reject, exactly like file changes and terminal commands. A rejection is
  reported to the agent so it can change course.
- **Auto-approve** skips the approval for that server in that project. Use it only for servers you
  trust to act without supervision.
- Tools without annotations are treated as side-effecting (fail closed).
- Automations (unattended runs) never get MCP tools. Review-only workflow roles (Orchestrator, Planner,
  Reviewer) only get read-only tools.

Tools reach the model as `mcp__<server>__<tool>` (names are sanitised and limited to 64 characters).
The chat shows them as "Called *tool* on *server*".

## Security notes

- Petri's server connects to the URLs users enter. As for LLM providers and Git hosts, internal
  addresses are allowed on purpose; redirects are never followed.
- Tool results are untrusted data: they are labelled as such for the agent, size-limited (64 KB) and
  must not be treated as instructions.
- Every call has a 30 second timeout; set `petri.mcp.call-timeout` and `petri.mcp.max-response-bytes`
  (Spring properties, e.g. `PETRI_MCP_CALL_TIMEOUT` is not wired: use `SPRING_APPLICATION_JSON` or
  `-Dpetri.mcp.call-timeout=45s`).
- Adding, changing and removing servers, signing in, and approving or rejecting calls are written to the
  audit log (never secrets).
