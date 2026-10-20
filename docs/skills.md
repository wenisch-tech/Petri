# Skills

A skill is a Markdown playbook in `skills/<name>/SKILL.md` that agents follow. Agents list them with
`list_skills` and load one with `load_skill`; automations load their skill themselves.

## Skill format

A skill is a directory with a `SKILL.md`: YAML front matter, then the instructions in Markdown.
`name` (equal to the directory name) and `description` are required. Everything else is optional,
so skills written without it keep working:

```markdown
---
name: vulnerability-scan-and-comment
description: Scan a pull request and publish the findings as a comment.
extends: vulnerability-scan
context: pull_request
produces: vulnerability_report
requires: [read_repo, scan]
version: 1.0.0
license: MIT
---
```

| Field | Meaning |
| --- | --- |
| `extends` | The skill whose method this one builds on. Agents follow the base first, then the skill's own text; the front matter of each is not part of what the agent reads. Chains up to four deep; cycles are refused. |
| `context` | What it works on: `pull_request`, `branch` or `any`. |
| `produces` | How it ends: `review`, `vulnerability_report` or `none`. |
| `requires` | The tool capabilities it needs (`read_repo`, `scan`, `write_proposal`, …). Declared for the importer and the permissions view; a skill can never widen the tools of the action that runs it. |
| `version`, `license` | For skills you share. |

Skills are looked up in the bundled directory (`PETRI_SKILLS_ROOT`, default `./skills`) and then in
an installed directory (`PETRI_SKILLS_INSTALLED_ROOT`, for skills an administrator adds). A bundled
skill always wins on a name clash, so an installed skill cannot replace a built-in one. A skill is
instructions only: it contains no code and runs nothing by itself.

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
replaces it, except that a CVE-only scan (`cve-scan` and its automations) replaces only the CVE findings
and keeps the secrets and misconfigurations that an earlier full scan of the branch saved. A branch that was never scanned shows a button that runs the skill. A scan that could
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

### As an automation

The scan also runs on pull requests, in three variants that mirror the code review ones:
`vulnerability-scan` (keep the table, post nothing), `vulnerability-scan-and-comment` (post it) and
`vulnerability-scan-and-improve` (post it and let a workflow fix the findings on the branch). They
are configured under Automations or started on one pull request with **Let Petri work on this**; see
[automations](automations.md#vulnerability-scans). The first of them is also what the Skills tab
runs, on the open branch.

### Fixing findings

Every finding on the Vulnerabilities tab has a **Fix** button, and the page has **Fix all**, which
covers the `CRITICAL`, `HIGH` and `MEDIUM` findings. Each starts a new direct chat in the chat panel
with a task that carries the finding (where, state today, target state, details) and the
`vulnerability-fix` skill: the agent upgrades or changes the smallest thing that clears it,
proposes the changes for your approval like any other change, runs the tests, rescans and reports.
The fix happens on the branch that is open in the workspace.

`cve-scan` and `cve-fix` are the same two skills restricted to known vulnerabilities (CVEs) in
dependencies: only the `vuln` scanner, no secrets, Dockerfiles, Helm charts or other configuration.

The same method drives two automations that need no chat: *Vulnerability fix pull request* and
*Vulnerability fix and merge* scan a selected branch on a schedule, fix the findings on a new branch
and open a pull request into it (the second also approves and merges it once the checks pass); see
[automations](automations.md#vulnerability-fix-pull-request-and-merge).

### Where the database comes from

Under **Administration > Vulnerability scanning** an administrator chooses the source of the
vulnerability database:

- **Online** (default): Trivy downloads and refreshes it. Leave the repository empty for Trivy's
  default (`mirror.gcr.io/aquasec/trivy-db:2`, then `ghcr.io/aquasecurity/trivy-db:2`), or enter a
  registry of your own, with its path and tag, for example
  `registry.internal:5000/security/trivy-db:2`. The Java database and the checks bundle (the rules
  behind Dockerfile, Kubernetes and Terraform findings) can be redirected the same way. A user and
  password or token can be stored for the registry; they are encrypted at rest, are only passed to
  Trivy in its environment and are never shown again. *Allow an insecure registry connection*
  permits plain HTTP or a certificate Petri does not trust.
- **Offline** (air-gapped): Trivy never reaches out. It uses the database that was supplied with
  the image, installed into the scan cache before each scan, and runs with `--skip-db-update`,
  `--skip-java-db-update`, `--skip-check-update` and `--offline-scan`. Dependency resolution still
  needs your package repositories (use your own mirrors, via the proxy settings of the test
  environment).

**Check database** gets the database the way a scan would. With a registry of your own it proves
that the address, path and credentials work; offline it installs the supplied database.

The registry has to hold the database as an OCI artifact, which is what `trivy-db` is. Mirror it
with `crane copy mirror.gcr.io/aquasec/trivy-db:2 registry.internal:5000/security/trivy-db:2` (or
`oras cp`, `skopeo copy`) and repeat that on a schedule, since the upstream database is rebuilt
every few hours.

#### The database in the image

The image build includes the vulnerability database at `/opt/trivy-db` (the `Dockerfile` argument
`BAKE_TRIVY_DB` is `true` by default, and the release workflow sets it explicitly). Consequences:

- A fresh installation scans immediately, without any registry access: the first scan installs the
  supplied database into the scan cache.
- An *online* instance then refreshes it when it is due. When that refresh fails (no route to the
  registry, bad credentials) the scan still runs with the database that is there, and the result
  says how old it is.
- An *offline* instance (see above) never refreshes; it is exactly as current as the image.
- The image is larger by the database (about 1.6 GB uncompressed). Build with `--build-arg BAKE_TRIVY_DB=false` for a slim
  image that downloads it on first use.

To build with a database from your own registry, or to rebuild an air-gapped image:

```bash
docker build --build-arg BAKE_TRIVY_DB=true \
  [--build-arg TRIVY_DB_REPOSITORY=registry.internal:5000/security/trivy-db:2] \
  [--secret id=trivy_username,env=REGISTRY_USER --secret id=trivy_password,env=REGISTRY_PASSWORD] \
  -t petri:airgapped .
```

and set the mode to *Offline* for an air-gapped installation. The database is large (about 1.6 GB uncompressed, several hundred
MB compressed), and it is only as current as the build, so rebuild the image to refresh it. The
directory can also be mounted or copied in by other means: it needs `db/trivy.db` and
`db/metadata.json`, and its path is configurable (`PETRI_TRIVY_OFFLINE_DB_PATH`, default
`/opt/trivy-db`). `PETRI_TRIVY_MODE` sets the mode that applies until an administrator saves one.

### Trivy in the image

The release image contains Trivy 0.76.0, copied as a single static binary from the official
`aquasec/trivy` image and pinned by digest in the `Dockerfile` (Renovate raises the pin). Before it
was added the binary was scanned with Trivy itself: no HIGH or CRITICAL findings. It adds about
170 MB to the image. A custom image without Trivy still works; the skill's Run button is disabled
and says so. Set `PETRI_TRIVY_COMMAND` (property `petri.trivy.command`) to use a Trivy at another
path.

## Installing and exporting skills

With `PETRI_SKILLS_INSTALLED_ROOT` set to a writable directory, instance admins can install a
prompt-only skill under **Administration → Actions → Skills** by uploading or pasting its
`SKILL.md`. The name comes from the front matter, a built-in skill of the same name cannot be
replaced, and a skill that `extends` another must extend one that exists. Installed skills can be
removed again; any skill can be exported as its `SKILL.md`. Installed skills are instructions only:
they never change which tools an agent may use.

## What a skill may use

A skill's `requires` list is what its agent run is allowed to call: `read_repo` (list, read and
search files, read the pull request) and `scan` (the vulnerability scanner). The tools of
everything a skill `extends` are added, the result tool (`submit_review` or
`submit_vulnerability_report`) is added for the kind of run, and nothing outside the automation
policy is ever granted, whatever a skill asks for. A skill that asks for nothing still reads the
repository. The grant is stored with the agent run, so a run keeps it if the skill changes.
