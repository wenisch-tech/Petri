# Kubernetes workspaces

By default Petri keeps every project's repository and runs every command in its own container. A
project can instead have its workspace in a **pod on a Kubernetes cluster**, started from a Docker
image of your choice. The workspace feels the same: explorer, editor, git bar, diffs, terminal,
agent tools and test runs all work as before.

## How it works

- Petri creates a **volume claim**, a **secret** and a **pod** per project in a namespace you
  choose. The volume holds the repository mirror and all branch worktrees, in the same layout Petri
  uses locally.
- Petri copies a small static program, the **workspace agent**, into the pod through the Kubernetes
  exec API and reaches it through the API server's pod proxy. No port is exposed and no extra
  network access is needed beyond the API server. The agent requires a random token that only the
  pod's secret and Petri know. Petri sends it in its own `X-Petri-Agent-Token` header, because the
  API server consumes the `Authorization` header to authenticate Petri and does not pass it on.
- Files, git, the terminal, tests and agent commands run in the pod. The pod is the sandbox: Petri
  does not use Landlock or Podman for it.
- The pod is **started on first use** and **stopped after it was idle** (60 minutes by default). The
  volume is kept, so starting again is fast and nothing is lost. Settings show the pod's phase and
  offer Start and Stop.

## Requirements

- A cluster Petri can reach over the network, and credentials that may create pods, volume claims
  and secrets in one namespace, run `exec` in pods and use the pod proxy. **Test connection** checks
  exactly this and names missing permissions.
- A Docker image with a POSIX shell (`sh`). Add the JDK or other tools your projects build with.
  `git`, `curl`, `python`, `go`, `node` and `npm` can come from the [bundled tools](#bundled-tools)
  instead. Petri copies its agent for `amd64` or `arm64` automatically.
- A storage class that can provide a `ReadWriteOnce` volume.

## Setting it up

1. **Administration > Kubernetes workspaces** (administrators) stores the default cluster and the
   pod defaults. Provide a pasted **kubeconfig**, or an **API server URL and token** with an
   optional CA certificate, or let Petri use its own service account when it runs in the cluster.
   Credentials are encrypted and never shown again; leave a field empty to keep the stored value.
2. In a project, **Settings > Kubernetes** turns the feature on and may override the cluster, image,
   namespace, volume size, storage class, CPU and memory, user ID, file system group and idle time.
   Empty fields inherit the default.
3. Switching a project to or from a pod needs a project without branch worktrees: the repository is
   cloned again on the new host (use **Clone mirror** in the project settings).

## Differences from the local workspace

| Topic | In a pod |
| --- | --- |
| Package installs | `install_packages` is disabled. Tools come from the image and the [bundled tools](#bundled-tools). Prompts tell agents so. |
| Sandbox profiles | Timeout, output limit, build systems and variables apply. Image, CPU, memory and network of the profile do not; the pod settings do. |
| Package sources | Applied like locally: Maven, Gradle, npm, pip, Go, Cargo, Composer, RubyGems and NuGet settings and the CA bundle are generated for each command and copied into the pod. See [Package sources and variables](#package-sources-and-variables). |
| Environment variables | The instance, sandbox profile and project variables, and the forwarded proxy variables, reach every command, test and dependency resolution in the pod. |
| Vulnerability scans | The package managers run **in the pod** on a throw-away copy, with its tools, variables and package sources; the lockfiles they write come back to Petri, where the scanner and its database run. See [Vulnerability scans](#vulnerability-scans). |
| Network | Whatever the cluster's network policy allows for the pod. |

## Bundled tools

The `-tools` variant of Petri's image (`ghcr.io/wenisch-tech/petri:<version>-tools`) carries a self-contained set of command line tools: **git, curl, python (with pip), go,
node and npm** as Alpine's latest packages, built once per CPU architecture (`amd64` and `arm64`).
When a pod starts, Petri copies the set into the project's volume, once per volume and Petri release.
After that a plain image with nothing but a shell is a complete workspace, and nothing is downloaded
from the internet, which is what an air-gapped installation needs.

The setting **Bundled tools** (default for all projects under Administration, override per project):

| Mode | Effect |
| --- | --- |
| Fallback (default) | The bundled tools come **after** the image's own on the `PATH`: they fill the gaps. |
| Prefer | They come **first**: use them when the image's tools are old or missing parts. |
| Off | Nothing is copied. The image must bring every tool. |

How it works, and why it runs on any image:

- The tools are built for **musl** and carry their own loader and libraries. Every executable is
  rewritten to use them by absolute path under `/petri/workspace/toolchain`, so the same bundle runs
  in glibc images (Debian, Ubuntu, Temurin) and musl images (Alpine). The image only needs `sh`.
- Small wrapper scripts in `toolchain/bin` set what each tool needs (`GIT_EXEC_PATH`, `GOROOT`, a CA
  bundle) without touching the rest of the environment. Variables you or the package sources set win.
- The transfer goes through the pod's agent, so the image needs no `tar`. The agent unpacks it into a
  staging directory and swaps it in only when the whole bundle was valid, so an interrupted transfer
  leaves the previous version in place.
- The bundle takes about 0.6 GB of the volume. Size the volume accordingly (the default is 10 Gi).
- If this Petri instance has no bundle for the pod's CPU, or copying fails, the pod simply uses its
  image's tools; a missing `git` is reported when the pod starts.

### Which image carries the bundle

The regular Petri image does **not** contain the bundle, which keeps it about 0.4 GB smaller. Petri looks
for the bundle in its own file system, in `/opt/petri/workspace-toolchain` (`PETRI_KUBERNETES_TOOLCHAIN_DIR`):

- **Found:** it is copied into pods as described above.
- **Not found:** pods start without it and use only their image's tools. Nothing fails unless the image
  also lacks `git`, in which case the start error says so.

To use the bundle, run the **`-tools` image** (`:<version>-tools`, `:latest-tools`), which is the regular image plus
the bundle. It is built from `Dockerfile.tools` on top of the regular image, and you can build it yourself
(the build needs internet access or an Alpine mirror; the resulting image does not):

```bash
docker build -t petri .
docker build -f Dockerfile.tools --build-arg BASE_IMAGE=petri \
  --build-arg APK_MIRROR=https://mirror.example.com/alpine -t petri-tools .
```

| Build argument | Effect |
| --- | --- |
| `BASE_IMAGE` | The regular Petri image to build on. |
| `WORKSPACE_TOOLCHAIN_PACKAGES` | The Alpine packages to bundle. Default: `git curl python3 py3-pip go nodejs npm ca-certificates-bundle`. Add more, for example `openjdk25 maven`; the commands of every listed package get a wrapper. |
| `APK_MIRROR` | The Alpine mirror the build uses. |

Alternatively mount a bundle built by `scripts/build-workspace-toolchain.sh` at that directory. For development
the script writes into `data/workspace-toolchain`, which Petri also searches.

## Package sources and variables

Package sources (**Administration > Package sources** and the project's own) and environment
variables work in a pod as they do locally. For every terminal command, approved test and dependency
resolution Petri

1. renders the tool configuration (Maven `settings.xml`, `.npmrc`, pip, Go, Cargo, Composer, Gradle
   init script, RubyGems, NuGet) with paths **as they are inside the pod**,
2. copies it, with the CA bundle and Java trust store when one is configured, into a scratch
   directory of the pod, and places the files tools only read from their own home into the project's
   build cache there,
3. starts the command with the variables that point the tools at them, and removes the scratch
   directory afterwards. The files hold repository credentials, so they live only as long as the
   command.

Variables are layered as locally: forwarded proxy variables, `PETRI_COMMAND_ENV_<NAME>` instance
variables, the sandbox profile's variables and the project's own, which win. The generated package
configuration and reserved names (`PATH`, `HOME`, `MAVEN_OPTS` ...) cannot be overridden.

The package sources must be reachable **from the pod**, not only from Petri. In an air-gapped cluster
that means a mirror inside the cluster network.

## Vulnerability scans

The scanner and its vulnerability database stay in Petri, where they are already managed (online or
offline, see Vulnerability scanning). For a project in a pod a scan

1. copies the worktree **inside the pod** into a scratch directory,
2. runs each package manager that has no lockfile yet (npm, Cargo, Composer, Bundler, Gradle, Maven)
   in that copy, **in the pod**, with the pod's tools, variables and package sources, so build code
   of the repository never runs in Petri's container,
3. brings the copy, including the lockfiles the tools wrote, to Petri and scans it there,
4. removes the copy in the pod. The repository itself is never written to.

Maven dependencies that the resolution downloaded stay in the pod's cache. The scanner reads the
`pom.xml` files itself and fetches missing artifacts through the Maven package source configured for
the project, which Petri must therefore be able to reach.

## RBAC

When Petri runs in the cluster, the Helm chart can create the service account and role:

```yaml
kubernetesWorkspaces:
  rbac:
    create: true
    namespace: petri-workspaces   # the namespace workspace pods run in
```

The role allows pods (get, list, watch, create, delete), `pods/exec` (create), `pods/proxy` (get,
create), volume claims and secrets (get, list, create, delete) in that namespace. Select **Petri's
own service account** as the access mode.

## Troubleshooting

| Message | Cause |
| --- | --- |
| `ImagePullBackOff` | The cluster cannot pull the image. Check the name and the registry access of the nodes. |
| The image has no git | Use an image with git installed, or allow the bundled tools. |
| The bundle was built for another path | The bundle and the pod disagree on the installation directory; rebuild the image from the same version of Petri. |
| A bundled tool fails with "not found" | The image has no `sh` at `/bin/sh`, or the volume is mounted `noexec`. |
| The workspace volume is not writable | Set a file system group (for example `1000`) or the user ID matching the image. |
| The workspace agent rejected Petri's token | Stop and start the pod to renew it. |
| The namespace does not exist | Create it first; Petri does not create namespaces. |
| Pending for a long time with `local-path` | The volume binds when the pod is scheduled; the node holding the volume must be available. |
