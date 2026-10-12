# Rootless Podman test sandboxes

Approved repository tests execute only in rootless Podman. There is no host fallback: unavailable Podman, images or profiles cause a closed failure.

The seeded **Java 25** profile supports Maven and Gradle wrappers with 2 CPUs, 2 GiB memory, 256 PIDs, a 10-minute timeout, 2 MiB output and outbound networking.

Containers use `--userns=keep-id`, a read-only root filesystem, `cap-drop=all`, `no-new-privileges` and bounded `/tmp`. Petri mounts no host home, secrets or container socket. Only the managed worktree is writable; Maven and Gradle caches are separate per project.

Administrators manage profiles and project overrides at `/sandbox-profiles`. Networking can inherit the profile or be forced on/off per project. Test runs snapshot profile, image, image digest and effective network mode.

Timeout and interruption explicitly kill and remove the container in addition to `--rm`.

## Alpine local executor and package installs

By default tests use the Podman sandbox above. For deployments where Petri itself runs on Alpine
(the release image and the dev container), set `PETRI_TESTS_EXECUTOR=local`. Approved tests then run
**inside Petri's own container** with a freshly built environment (no inherited variables or
secrets), the profile's timeout and output limits, and per-project Maven/Gradle caches.

Agents can request extra tooling with the `install_packages` tool:

- Names must be plain lowercase Alpine package names (1–20 per request; no flags, URLs, paths or
  version pins). The reason is stored with the request.
- A request is a **proposal** shown in the chat and review tray; it runs only after the user approves
  it, unless **Auto-approve package installs** is enabled for the project at `/sandbox-profiles`.
  Workflow plan approval never covers installs.
- Packages are installed with `apk --usermode --no-scripts` (package scripts need a user namespace,
  which container runtimes block) into an unprivileged prefix
  (`PETRI_TOOLCHAIN_ROOT`, default `./data/toolchain`, on the data volume). No root, `sudo` or
  setuid is used. The prefix is prepended to `PATH`/`LD_LIBRARY_PATH` for later tests.
- Approved packages are recorded and restored on start-up if the toolchain volume is empty.

Prompts for every role tell the model it runs on Alpine, how to request installs and which extra
packages are already present. With the Podman executor the tool is disabled and prompts say so.

**Trade-off:** in `local` mode repository code runs as the same operating-system user as Petri and
can read `./data`. Prefer `PETRI_SECRETS_MASTER_KEY` (environment) over the key file, an external
PostgreSQL, and use the Podman executor for untrusted repositories.

## Command environment and HTTP proxies

Terminal commands, approved tests and package installs run with a freshly built environment, so
variables of the Petri container are **not** inherited. To let them download packages from a
restricted network, Petri adds variables in this order (later wins):

1. **Forwarded proxy variables** of the Petri container: `HTTP_PROXY`, `HTTPS_PROXY`, `FTP_PROXY`,
   `ALL_PROXY`, `NO_PROXY` in either case (the missing case is mirrored, because `apk` and `curl`
   read lower-case names and Java tooling upper-case). Set `PETRI_COMMAND_ENV_FORWARD_PROXY=false`
   to turn forwarding off.
2. **Instance variables**: any `PETRI_COMMAND_ENV_<NAME>=value` becomes `<NAME>=value`, for example
   `PETRI_COMMAND_ENV_PIP_INDEX_URL`.
3. **Sandbox profile variables**: *Environment variables* in the profile dialog at
   `/sandbox-profiles` (`NAME=VALUE` per line). Values are encrypted at rest, shown masked
   (`********` keeps a stored value), and only variable names are written to the audit log.

`PATH`, `HOME`, `TMPDIR`, `LD_LIBRARY_PATH`, `LD_PRELOAD`, `JAVA_HOME`, `JAVA_TOOL_OPTIONS`,
`MAVEN_OPTS`, `GRADLE_USER_HOME`, `CI`, `LC_ALL` and names starting with `PETRI_` cannot be set.
A profile holds at most 50 variables of at most 4096 characters. Variables apply to the Podman
container (`--env`) and the local executor alike. Repository code run by a command can read them,
so do not put credentials there that untrusted repositories must not see.

## Terminal

The workspace has a **Terminal** panel below the editor. It is open by default (toggle it with the
status-bar button or `Ctrl+\``, drag its top edge to resize; the choice is remembered). Commands you
type run immediately in the worktree root; there is no approval because you are the one running them.

Agents get the `run_command` tool (implementer and direct runs). An agent command is a proposal:
it runs only after the user approves it in the review tray/chat, unless **Auto-approve agent
terminal commands** is enabled for the project at `/sandbox-profiles`. The run waits for the
decision, and a decline is reported back to the model together with the exit code and output of
approved commands.

**Confinement is enforced by the operating system, not by filtering strings:**

| Executor | How commands are confined |
| --- | --- |
| `podman` | A rootless container with only the worktree mounted, read-only root filesystem, no capabilities, the project's profile limits and network setting. |
| `local` | `petri-confine` (a small static Landlock helper, `PETRI_TERMINAL_CONFINE_COMMAND`) wraps the command: it may read the system, the JDK and the toolchain, and write only the worktree, the build cache and a private temporary home. Everything else, including `/app/data` (database, master key, other worktrees), is denied even for the same user. Without the helper, commands are refused. |

Landlock needs no root, no setuid and no user namespace, so it works with **any UID**
(`docker run --user 12345`, OpenShift `anyuid`) and with the Helm chart's hardened
`securityContext`. It requires a kernel with Landlock (Linux 5.13+; Docker's default seccomp profile
allows it). If Landlock is unavailable the helper exits with status 125 and the command is not run.
In `local` mode approved tests are wrapped the same way. Network access follows the container's
network (`local`) or the sandbox profile (`podman`).
