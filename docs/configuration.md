# Configuration

All settings use Spring Boot configuration. Environment variable examples below match `application.yml`.

## Administrator and secrets

| Variable | Default | Purpose |
| --- | --- | --- |
| `PETRI_ADMIN_EMAIL` | `admin@petri.local` | Initial local administrator email. |
| `PETRI_ADMIN_PASSWORD` | `admin` | Initial local administrator password. |
| `PETRI_SECRETS_MASTER_KEY` | empty | Base64-encoded 32-byte AES key. Takes precedence over the key file. |
| `PETRI_SECRETS_MASTER_KEY_FILE` | `./data/petri-master.key` | Persistent auto-generated master-key file. |

Generate an explicit key with:

```bash
export PETRI_SECRETS_MASTER_KEY="$(openssl rand -base64 32)"
```

Do not rotate or delete the key without re-encrypting existing credentials.

## Database

| Variable | Default |
| --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:h2:file:./data/petri;MODE=PostgreSQL;...` |
| `SPRING_DATASOURCE_USERNAME` | `sa` |
| `SPRING_DATASOURCE_PASSWORD` | empty |

Example PostgreSQL configuration:

```bash
export SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/petri
export SPRING_DATASOURCE_USERNAME=petri
export SPRING_DATASOURCE_PASSWORD='replace-me'
```

Flyway applies schema migrations during startup. Hibernate validates the resulting schema and does not create it.

## Workspace and process limits

| Variable | Default | Purpose |
| --- | --- | --- |
| `PETRI_WORKSPACE_ROOT` | `./data/workspaces` | Mirror and disposable-worktree root. |
| `PETRI_GIT_COMMAND_TIMEOUT` | `2m` | Git process timeout. |
| `PETRI_MAX_COMMAND_OUTPUT_BYTES` | `1000000` | Captured Git output limit. |
| `PETRI_MAX_TOOL_FILE_BYTES` | `1000000` | Maximum file read size. |
| `PETRI_MAX_TOOL_OUTPUT_BYTES` | `1000000` | Maximum tool result size. |
| `PETRI_MAX_SEARCH_RESULTS` | `200` | Search-result count limit. |
| `PETRI_IMPORT_MAX_PARALLEL` | `4` | Maximum concurrent mirror-initialization jobs. |
| `PETRI_PODMAN_EXECUTABLE` | `podman` | Rootless Podman CLI path/name. |

Test timeout, output, CPU, memory, PIDs, image and network settings are managed through administrator sandbox profiles. The seeded defaults remain 10 minutes and 2 MB.

## Model runtime

| Variable | Default | Purpose |
| --- | --- | --- |
| `PETRI_OPENAI_BASE_URL` | `https://api.openai.com/v1` | Default URL shown for unconfigured OpenAI. |
| `PETRI_MODEL_REQUEST_TIMEOUT` | `2m` | Connect/request timeout and execution-lease basis. |
| `PETRI_MAX_MODEL_RESPONSE_BYTES` | `2000000` | Maximum provider response body. |
| `PETRI_MODEL_MAX_OUTPUT_TOKENS` | `4096` | Default output budget. |
| `PETRI_TESTS_EXECUTOR` | `podman` | `podman` (isolated sandbox) or `local` (run tests in Petri's own Alpine container and enable `install_packages`). |
| `PETRI_TOOLCHAIN_ROOT` | `./data/toolchain` | Unprivileged apk prefix for agent-installed packages (`local` executor). |
| `PETRI_APK_EXECUTABLE` | `apk` | apk binary used for package installs. |
| `PETRI_INSTALL_TIMEOUT` | `10m` | Maximum duration of one package install. |
| `PETRI_AGENT_APPROVAL_WAIT` | `12h` | How long a run waits for a decision on a proposal before continuing with a "still pending" result. |
| `PETRI_TERMINAL_CONFINE_COMMAND` | empty | Path of the Landlock helper `petri-confine`. Required for terminal commands (and confined tests) with the `local` executor; set by the image. |
| `PETRI_TERMINAL_TIMEOUT` | `2m` | Maximum duration of one terminal command. |
| `PETRI_TERMINAL_MAX_OUTPUT_BYTES` | `200000` | Maximum retained output per command. |
| `PETRI_SKILLS_ROOT` | `./skills` | Operator-controlled skill directory. |
| `PETRI_MAX_SKILL_BYTES` | `200000` | Maximum `SKILL.md` size. |

Provider URLs and credentials are normally configured at `/model-settings` and persisted in the database.

## OIDC

Local login remains enabled when OIDC is configured. A provider must return a verified email address.
Petri requests the `openid`, `profile` and `email` scopes.

Set `OIDC_ENABLED=true` and configure one or more providers. For several providers use indexed
variables and repeat with `_1_`, `_2_`, and so on; each provider gets its own sign-in button:

```bash
OIDC_ENABLED=true
OIDC_PROVIDERS_0_NAME="Company SSO"
OIDC_PROVIDERS_0_CLIENT_ID=petri
OIDC_PROVIDERS_0_CLIENT_SECRET=replace-me
OIDC_PROVIDERS_0_ISSUER_URI=https://sso.example.test/realms/platform
OIDC_PROVIDERS_1_NAME="GitLab"
OIDC_PROVIDERS_1_CLIENT_ID=petri
OIDC_PROVIDERS_1_CLIENT_SECRET=replace-me
OIDC_PROVIDERS_1_ISSUER_URI=https://gitlab.example.test
```

| Variable | Default | Purpose |
|---|---|---|
| `OIDC_ENABLED` | `false` | Enable the `OIDC_*` provider configuration |
| `OIDC_PROVIDERS_<n>_NAME` | `Provider <n+1>` | Label on the sign-in button |
| `OIDC_PROVIDERS_<n>_CLIENT_ID` / `_CLIENT_SECRET` | | OIDC client credentials |
| `OIDC_PROVIDERS_<n>_ISSUER_URI` | | Issuer discovery URL |
| `OIDC_PROVIDERS_<n>_REGISTRATION_ID` | derived from the name | Spring registration id; the callback is `/login/oauth2/code/<id>` |
| `OIDC_PROVIDERS_<n>_USER_NAME_ATTRIBUTE` | `sub` | Claim Spring Security uses as the principal name |
| `OIDC_PROVIDER_NAME`, `OIDC_CLIENT_ID`, `OIDC_CLIENT_SECRET`, `OIDC_ISSUER_URI` | | Single-provider shorthand, used when no indexed provider is set |
| `OIDC_CREATEUSERS` | `true` | Create a local `USER` account for unknown identities; `false` requires an administrator to create the account first |
| `OIDC_INSECURE_SKIP_TLS_VERIFY` | `false` | Skip certificate and hostname verification for OIDC provider connections only |

Providers with a private CA or self-signed certificate can be reached with
`OIDC_INSECURE_SKIP_TLS_VERIFY=true`, or by the global `IGNORE_TLS` flag below. Both are meant for
self-hosted environments and troubleshooting; prefer adding the CA to the Java trust store.
Failed OIDC logins are logged at `WARN` with the underlying cause.

Without `OIDC_ENABLED`, the standard Spring Boot `spring.security.oauth2.client.registration.*` and
`...provider.*` properties continue to work.

An OIDC identity matching an existing local email keeps that Petri account's role.

When a proxy terminates TLS, keep `SERVER_FORWARD_HEADERS_STRATEGY=framework` so redirect URIs use
the public HTTPS origin.

## Command environment

Commands run by Petri get a scrubbed environment. Proxy variables (`HTTP_PROXY`, `HTTPS_PROXY`,
`NO_PROXY`, ...) are forwarded; `PETRI_COMMAND_ENV_<NAME>=value` adds `<NAME>` for every command and
sandbox profiles can add more in the UI. See
[Test sandboxes](test-sandboxes.md#command-environment-and-http-proxies).

## Ignoring TLS errors

`IGNORE_TLS=true` (default `false`) disables TLS certificate and hostname verification for all
outbound HTTPS connections made by Petri: OIDC providers, Git provider APIs, model providers and
`git` subprocesses (`GIT_SSL_NO_VERIFY`). It is meant for self-hosted environments behind a private
CA or self-signed certificates. It does not affect inbound TLS, test containers or package
installs. Petri logs a warning at startup when it is on.
