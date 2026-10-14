# Skills

A skill is a Markdown playbook in `skills/<name>/SKILL.md` that agents follow. Agents list them with
`list_skills` and load one with `load_skill`; automations load their skill themselves.

## Skills tab

Each project has a **Skills** tab next to Automations. It lists every installed skill:

- **Run in the workspace**: *Run* starts a new direct chat in the chat panel on the right, with the
  currently selected model, that loads the skill and follows it for this project. The agent uses the
  usual approval-gated tools, so nothing changes without a visible proposal.
- **Pull request skills** (`code-review`, `code-review-and-comment`, `code-review-and-improve`) work
  on one pull request. They are started from the pull request with **Let Petri work on this**, or by
  an automation, so they have no *Run* button here.

## Bundled skills

| Skill | Purpose |
| --- | --- |
| `vulnerability-scan` | Scan the project with Trivy and report what to fix first. See below. |
| `code-review`, `code-review-and-comment`, `code-review-and-improve` | Review a pull request, optionally comment and fix. See [automations](automations.md). |
| `conventional-commit`, `pull-request-description` | Write commit messages and pull request descriptions. |
| `spring-boot-change`, `workflow-verification` | Working conventions for Spring Boot changes and verification. |

## Vulnerability scan

The `vulnerability-scan` skill uses the read-only `scan_vulnerabilities` tool, which runs
[Trivy](https://trivy.dev) over the project's worktree and returns counts by severity plus the most
important findings (package, installed and fixed version, advisory link):

- **Scanners**: `vuln` (dependencies in lockfiles and build files), `secret` (committed secrets;
  secret values are never returned) and `misconfig` (Dockerfiles, Kubernetes, Terraform).
- **Severities**: `CRITICAL`, `HIGH`, `MEDIUM` by default; `LOW` and `UNKNOWN` on request.
- **Confinement**: Trivy runs as a bounded subprocess under the same Landlock confinement as
  terminal commands. It can read the worktree and write only its cache and a private scratch
  directory. It never modifies the project.
- **Database**: the vulnerability database is downloaded on the first scan into
  `<data>/trivy-cache` (about 130 MB) and refreshed by Trivy as needed. The Petri container
  therefore needs outbound HTTPS to `mirror.gcr.io`/`ghcr.io`; behind a proxy set the usual proxy
  variables for the Petri process. Nothing about your code is sent: only the database is fetched.
- **Limits**: a scan is stopped after 12 minutes (scan a subdirectory with `path`), and at most 40
  findings are returned, most severe and fixable first.

### Vulnerabilities tab

The skill ends by saving its result with the `submit_vulnerability_report` tool. The project's
**Vulnerabilities** tab (next to Pull requests) shows the latest report of the branch that is open
in the workspace: a table with *Finding*, *Severity*, *Where*, *State today* and *Target state*, and
the full description of every finding below it. Each branch keeps its own report; a new scan
replaces it. A branch that was never scanned shows a button that runs the skill. A scan that could
not cover everything (for example unresolved dependencies) is marked as incomplete.

### Dependency resolution

Trivy needs the resolved dependency set of a project, and many projects only declare it. The scan
therefore detects the package managers of the project (any directory up to six levels deep) and,
where a lockfile is missing, runs the ecosystem's own tool in a throw-away copy of the project, so
the repository is never changed:

| Ecosystem | Detected by | Resolved with |
| --- | --- | --- |
| Maven | `pom.xml` | the project's `mvnw`, else an installed `mvn`: `dependency:resolve` into the shared cache that Trivy reads |
| Gradle | `build.gradle(.kts)`, `settings.gradle(.kts)` | `gradlew` or `gradle`: `dependencies --write-locks` with dependency locking enabled for every project |
| npm | `package.json` without `package-lock.json`, `yarn.lock` or `pnpm-lock.yaml` | `npm install --package-lock-only --ignore-scripts` |
| Cargo | `Cargo.toml` without `Cargo.lock` | `cargo generate-lockfile` |
| Composer | `composer.json` without `composer.lock` | `composer update --no-install --no-scripts` |
| Bundler | `Gemfile` without `Gemfile.lock` | `bundle lock` |
| Go | `go.mod` | not needed: `go.mod` lists the module graph |
| Python | `requirements.txt`, `pyproject.toml` | not resolved: without a lockfile only the declared constraints are checked |

Resolution runs under the same confinement as test runs and never executes project scripts. The
result lists each step; a missing package manager is reported as `tool-missing` and makes the scan
incomplete, and the skill then installs the tool with the approval-gated `install_packages` and
scans again. A project too large to copy (500 MB or 80,000 files) is scanned from its committed
lockfiles only, and the result says so.

### Trivy in the image

The release image contains Trivy 0.76.0, copied as a single static binary from the official
`aquasec/trivy` image and pinned by digest in the `Dockerfile` (Renovate raises the pin). Before it
was added the binary was scanned with Trivy itself: no HIGH or CRITICAL findings. It adds about
170 MB to the image. A custom image without Trivy still works; the skill's Run button is disabled
and says so. Set `PETRI_TRIVY_COMMAND` (property `petri.trivy.command`) to use a Trivy at another
path.
