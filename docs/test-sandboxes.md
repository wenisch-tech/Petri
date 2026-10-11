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
