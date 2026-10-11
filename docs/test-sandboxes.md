# Rootless Podman test sandboxes

Approved repository tests execute only in rootless Podman. There is no host fallback: unavailable Podman, images or profiles cause a closed failure.

The seeded **Java 25** profile supports Maven and Gradle wrappers with 2 CPUs, 2 GiB memory, 256 PIDs, a 10-minute timeout, 2 MiB output and outbound networking.

Containers use `--userns=keep-id`, a read-only root filesystem, `cap-drop=all`, `no-new-privileges` and bounded `/tmp`. Petri mounts no host home, secrets or container socket. Only the managed worktree is writable; Maven and Gradle caches are separate per project.

Administrators manage profiles and project overrides at `/sandbox-profiles`. Networking can inherit the profile or be forced on/off per project. Test runs snapshot profile, image, image digest and effective network mode.

Timeout and interruption explicitly kill and remove the container in addition to `--rm`.
