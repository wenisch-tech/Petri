# Security model

## Trust boundaries

Petri separates four roles:

- The **model** may request only registered tools.
- An **API client** acts only on projects owned by its key creator and cannot approve privileged proposals.
- An authenticated **project owner** controls personal self-hosted Git instances, personal Git accounts, projects, workspaces, chats, project credentials, Git actions, file approvals and test approvals.
- An **instance administrator** controls global Git-provider instances and OAuth applications, model endpoints, audit data and sandbox profiles. Administrators do not implicitly gain access to projects owned by another user.

Model text and skill instructions are untrusted input. They cannot bypass Java-side path validation, tool registration or approval state transitions.

## Secrets

- Git, connected-account token and model secrets are encrypted with AES-256-GCM before persistence.
- The master key comes from an explicit environment value or an owner-only filesystem file.
- Secrets are not returned by status APIs or rendered into HTML.
- Git secrets are supplied with a temporary askpass process rather than command arguments.
- Model keys are added to the `Authorization` header only for the configured provider request.

Back up the master key securely. Database backup without the matching key cannot restore encrypted credentials.

## Filesystem controls

- Workspace paths are derived beneath one configured root.
- Existing paths are normalized and checked against their real path.
- Symlink escapes, traversal, `.git` internals and oversized files are rejected.
- Worktree deletion validates the persisted relative path against the expected UUID-derived path.

These checks constrain Petri's own operations; they are not a replacement for OS isolation.

## External processes

Git runs without a shell, with bounded output and timeouts. Git global/system configuration is disabled and interactive prompting is off.

Approved tests run exclusively in rootless Podman; there is no host fallback. Containers use a read-only root filesystem, `--userns=keep-id`, dropped capabilities, `no-new-privileges`, CPU/memory/PID/time/output limits and an isolated temporary directory. Only the managed worktree and project-specific build cache are writable mounts. Petri does not mount the host home, secrets or container socket.

## Network controls

Model and Git-provider base URLs may intentionally target local containers or LAN services. Petri validates URL structure and provider clients reject redirects, but the Petri process itself has no general network egress isolation. Restrict its network when administrators must not be able to configure internal destinations.

Test-container outbound access is enabled in the seeded profile so wrappers can resolve dependencies. Administrators can disable it per profile or project.

Use HTTPS whenever bearer credentials cross an untrusted network. Plain HTTP is intended only for trusted local services such as Ollama.

Variables injected into commands (proxy settings, `PETRI_COMMAND_ENV_*`, sandbox profile variables)
are visible to the repository code those commands run. Use proxy credentials that are scoped to
package downloads, and prefer the Podman executor for untrusted repositories.

## Production checklist

- Replace the default administrator password.
- Use PostgreSQL and protected backups.
- Persist and protect the master key separately from ephemeral containers.
- Run as a dedicated unprivileged user.
- Restrict workspace permissions and network egress.
- Put Petri behind TLS and a trusted reverse proxy.
- Configure OIDC for centralized identity when appropriate.
- Leave `IGNORE_TLS` and `OIDC_INSECURE_SKIP_TLS_VERIFY` off; trust your private CA instead. Both disable
  man-in-the-middle protection for bearer tokens and provider credentials.
- Review audit events and revoke unused API keys.

## Local executor and package installs

`PETRI_TESTS_EXECUTOR=local` trades Podman isolation for the ability to run tests and agent-approved
package installs in Petri's own Alpine container. Repository code then shares the Petri process's
Unix user, filesystem (including `./data`) and network. Package installs fetch and run third-party
package code, so each is a user-approved proposal: names are strictly validated, apk runs without a
shell as an unprivileged user into a separate prefix, and the only automatic path is the explicit
per-project *Auto-approve package installs* setting. Keep the Podman executor for untrusted code.
