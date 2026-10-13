# Getting started

## Requirements

- JDK 25 or newer
- Git
- Maven is optional because the repository includes `mvnw`
- A reachable model endpoint, such as a local Ollama instance

## Start Petri

```bash
./mvnw spring-boot:run
```

Open <http://localhost:8080> and sign in. The local development defaults are:

- Email: `admin@petri.local`
- Password: `admin`

Set `PETRI_ADMIN_EMAIL` and `PETRI_ADMIN_PASSWORD` before exposing Petri beyond localhost.

On first startup, Petri creates an encryption key at `./data/petri-master.key` when no explicit master key exists. Back up this file together with the database. Losing it makes encrypted model and Git credentials unreadable.

## Configure a local model

1. Sign in as the administrator and open `/model-settings` (Admin → LLM providers).
2. Choose **Add provider** and pick the **Ollama** kind.
3. Keep `http://localhost:11434/v1` when Ollama runs on the same host.
4. Enter an installed model ID, for example your preferred coding model.
5. Leave the API key blank unless the endpoint requires one.
6. Add the provider.

Providers that need a key (for example OpenAI) are usable once each user adds their own key under
**LLM providers** in the avatar menu, or once you store a shared key on the provider.

When Petri runs in a container, `localhost` refers to that container. Use the Ollama container name, `host.docker.internal`, or another reachable host instead.

## Register a repository

1. Open `/projects` and select **Add project**.
2. Enter a name and repository URI.
3. Enter the expected default branch or leave the suggested value.
4. Open the new project's settings.
5. Configure HTTPS credentials when required.
6. Select **Clone mirror**.

Petri accepts local paths, SSH repository identities and HTTP(S) URLs. Stored credentials are only attached to HTTPS operations.

## Start a task

1. Open `/chat` and select the project.
2. Select **Prepare default branch** if no active worktree exists.
3. Choose a configured model provider and worktree.
4. Describe the desired result and send the task.
5. Review any proposed file change or test run in the conversation.
6. Approve and then explicitly apply or execute the proposal.

The model cannot write files or execute tests directly. It can only create persisted proposals for an administrator.

## Build a production JAR

```bash
./mvnw package
java -jar target/petri-0.1.0-SNAPSHOT.jar
```

The default database is file-backed H2 below `data/`. Configure PostgreSQL with the standard `SPRING_DATASOURCE_*` variables for a durable multi-process deployment.
