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
| `PETRI_TEST_COMMAND_TIMEOUT` | `10m` | Approved test process timeout. |
| `PETRI_MAX_TEST_OUTPUT_BYTES` | `2000000` | Captured test output limit. |

## Model runtime

| Variable | Default | Purpose |
| --- | --- | --- |
| `PETRI_OPENAI_BASE_URL` | `https://api.openai.com/v1` | Default URL shown for unconfigured OpenAI. |
| `PETRI_MODEL_REQUEST_TIMEOUT` | `2m` | Connect/request timeout and execution-lease basis. |
| `PETRI_MAX_MODEL_RESPONSE_BYTES` | `2000000` | Maximum provider response body. |
| `PETRI_MODEL_MAX_OUTPUT_TOKENS` | `4096` | Default output budget. |
| `PETRI_SKILLS_ROOT` | `./skills` | Operator-controlled skill directory. |
| `PETRI_MAX_SKILL_BYTES` | `200000` | Maximum `SKILL.md` size. |

Provider URLs and credentials are normally configured at `/model-settings` and persisted in the database.

## OIDC

Local login remains enabled when OIDC is configured. A provider must return a verified email address.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: petri
            client-secret: replace-me
            scope: openid,profile,email
        provider:
          keycloak:
            issuer-uri: https://sso.example.test/realms/platform
```

An OIDC identity matching an existing local email keeps that Petri account's role.
