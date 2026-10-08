# Petri

Petri is a self-hosted, Java-based coding agent for controlled analysis, modification and verification of software projects.

## Development

Requirements: JDK 25 and Maven 3.9+.

```bash
./mvnw test
./mvnw spring-boot:run
```

Open <http://localhost:8080> and sign in with `admin@petri.local` / `admin` for local evaluation. Set `PETRI_ADMIN_EMAIL` and `PETRI_ADMIN_PASSWORD` before exposing the service beyond localhost.

The standalone profile stores its H2 database below `data/`. PostgreSQL can be selected through the standard `SPRING_DATASOURCE_*` environment variables.

## Secret encryption

Repository credentials and future model-provider credentials are encrypted with AES-256-GCM before persistence. Configure a dedicated, Base64-encoded 32-byte key in every environment that stores credentials; Petri fails closed if a secret operation is attempted without it. Keep the value outside source control and back it up securely, because losing it makes encrypted records unrecoverable.

```bash
export PETRI_SECRETS_MASTER_KEY="$(openssl rand -base64 32)"
```

An instance administrator can configure or remove an HTTPS token or username/password credential from a project's settings page. Petri only displays whether a credential exists; the stored secret is never rendered back into the UI.

## Git workspace

Petri stores bare project mirrors and disposable run worktrees below `PETRI_WORKSPACE_ROOT` (default `./data/workspaces`). Git is executed directly without a shell, with interactive prompts disabled, a configurable timeout, bounded and redacted output, and global/system Git configuration disabled. HTTPS credentials are supplied through a temporary owner-only askpass helper and never through command arguments.

```bash
export PETRI_WORKSPACE_ROOT=/var/lib/petri/workspaces
export PETRI_GIT_COMMAND_TIMEOUT=2m
export PETRI_MAX_COMMAND_OUTPUT_BYTES=1000000
```

## OIDC

Local login remains available by default. To enable an OpenID Connect provider, configure a standard Spring Security client registration with `openid` and `email` scopes. Petri requires the provider's `email_verified` claim before it creates or links an account. A verified email matching an existing local account retains that account's Petri role.

```yaml
spring:
  security:
    oauth2:
      client:
        registration:
          keycloak:
            client-id: petri
            client-secret: change-me
            scope: openid,profile,email
        provider:
          keycloak:
            issuer-uri: https://sso.example.test/realms/platform
```

## Status

Phase 1 is in progress. The current slices provide the reproducible backend/frontend build, Flyway-managed persistence, local and optional OIDC authentication, project registry, audit events, administrator-managed API keys, encrypted project Git credentials, controlled repository mirrors and disposable worktrees.

## License

Petri is licensed under the GNU Affero General Public License v3.0.
