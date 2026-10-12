---
name: workflow-verification
description: How to verify work in Petri's shared, sandboxed environment and what must not be treated as a defect: busy ports, alternate-port checks, leftover processes.
---

# Verifying work in this environment

Petri runs agents inside a shared container. Other services, including Petri itself on port 8080,
are already running there. This skill is applied automatically to the planner, implementer,
reviewer and gate roles. Add new environment-specific rules here as they are discovered; every
agent picks them up on its next run.

## Ports that are already in use

A port conflict is a property of the environment, never a defect of the code under review.

- `Address already in use`, `Port 8080 was already in use`, `BindException`, `EADDRINUSE` or a
  failed health check on a default port means the environment's port is taken. It does not mean
  the application is broken, and it is not a failed acceptance criterion.
- Verify on another port instead and keep the application's default unchanged:
  - Spring Boot: `--server.port=<free port>` or `-Dspring-boot.run.arguments=--server.port=<port>`;
    `--server.port=0` picks a free port and the log line `Tomcat started on port <n>` names it.
  - Other stacks: use their port flag or environment variable (`PORT=...`, `--port`).
  - Choose a port in 18000-18999 and move on to the next one if it is taken as well.
- Never edit source, `pom.xml`, `build.gradle` or configuration only to avoid a busy port. The
  delivered default (for example 8080) stays as planned.
- Prefer tests that do not bind a fixed port: `@SpringBootTest(webEnvironment = RANDOM_PORT)`
  or MockMvc.
- Stop every server you start during verification (`kill` the process, or run it with `timeout`)
  so its port is released and later runs are not blocked by it.

## How roles treat it

- **Implementer:** a check performed on an alternate port satisfies a criterion that names the
  default port. Report it under `tests` and say which port was used. Do not list it under
  `deviations`: deviations are departures from the approved plan's scope or files, not
  environment adjustments.
- **Planner:** write endpoint criteria independent of the port, for example "returns HTTP 200 on
  the application's configured port (or another free port if it is occupied)".
- **Reviewer and gates:** a busy default port, or a verification on a different port, is not a
  finding, not `CHANGES_REQUIRED`, not `HOLD`, and not a reason to mark a criterion `PARTIAL` or
  `NOT_MET`. Judge the criterion by whether the application started and answered correctly on
  whatever port it ran. A test or command that failed only because a port was occupied is
  environmental: ask for a rerun on another port rather than rejecting the work.

## Other environment facts

- Dependencies are downloaded from the network; a transient download failure is worth one retry
  before it is reported as a failure.
- A project without `mvnw`/`gradlew` is built with an installed `mvn`/`gradle` (install the
  package first); a wrapper in the worktree takes precedence.
- Commands have a time limit. Long-running servers must be started in the background or wrapped
  with `timeout`, and checked with `curl`, not left running.
