package tech.wenisch.petri;

import jakarta.persistence.EntityManager;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.audit.AuditEventRepository;
import tech.wenisch.petri.apikey.ApiKeyService;
import tech.wenisch.petri.apikey.CreateApiKeyCommand;
import tech.wenisch.petri.agent.change.ChangeProposalService;
import tech.wenisch.petri.agent.change.ChangeProposalStatus;
import tech.wenisch.petri.agent.change.ChangeProposalView;
import tech.wenisch.petri.agent.change.ChangeType;
import tech.wenisch.petri.agent.tool.AgentToolRegistry;
import tech.wenisch.petri.agent.tool.ReadFileResult;
import tech.wenisch.petri.agent.tool.ToolExecutionContext;
import tech.wenisch.petri.agent.run.TestRunService;
import tech.wenisch.petri.agent.run.TestRunStatus;
import tech.wenisch.petri.agent.run.TestRunView;
import tech.wenisch.petri.agent.model.ModelCredentialService;
import tech.wenisch.petri.agent.model.ModelProvider;
import tech.wenisch.petri.agent.model.SaveModelCredentialCommand;
import tech.wenisch.petri.git.ProjectMirrorService;
import tech.wenisch.petri.git.ProjectWorktreeService;
import tech.wenisch.petri.git.ProjectWorktreeStatus;
import tech.wenisch.petri.project.CreateProjectCommand;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectService;
import tech.wenisch.petri.workspace.WorktreeLocator;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ProjectMirrorServiceTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";
  private static final AtomicInteger MODEL_REQUESTS = new AtomicInteger();
  private static final HttpServer MODEL_SERVER = modelServer();

  @TempDir static Path temporaryDirectory;

  @DynamicPropertySource
  static void workspaceProperties(DynamicPropertyRegistry registry) {
    registry.add("petri.workspace.root", () -> temporaryDirectory.resolve("workspace").toString());
    registry.add(
        "petri.agent.open-ai-base-url",
        () -> "http://localhost:" + MODEL_SERVER.getAddress().getPort());
  }

  @AfterAll
  static void stopModelServer() {
    MODEL_SERVER.stop(0);
  }

  @Autowired ProjectService projectService;
  @Autowired ProjectMirrorService mirrors;
  @Autowired ProjectWorktreeService worktrees;
  @Autowired WorktreeLocator worktreeLocator;
  @Autowired AgentToolRegistry tools;
  @Autowired ChangeProposalService proposals;
  @Autowired TestRunService testRuns;
  @Autowired AuditEventRepository auditEvents;
  @Autowired ApiKeyService apiKeys;
  @Autowired MockMvc mockMvc;
  @Autowired ModelCredentialService modelCredentials;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;
  @Autowired EntityManager entityManager;

  @Test
  void clonesAndFetchesLocalRepositoryIntoControlledMirror() throws Exception {
    Path source = createSourceRepository();
    Project project = projectService.create(
        new CreateProjectCommand(
            "Mirror test " + UUID.randomUUID(), source.toString(), "main"),
        ADMIN_EMAIL);

    assertThat(mirrors.synchronize(project.getId(), ADMIN_EMAIL).initialized()).isTrue();
    Path mirror = temporaryDirectory.resolve("workspace/mirrors/" + project.getId() + ".git");
    assertThat(mirror.resolve("HEAD")).isRegularFile();
    assertThat(git("--git-dir=" + mirror, "rev-parse", "refs/heads/main")).isNotBlank();
    assertThat(auditActions()).contains("PROJECT_MIRROR_CLONED");

    Files.writeString(source.resolve("README.md"), "second revision\n");
    git("-C", source.toString(), "add", "README.md");
    git("-C", source.toString(), "commit", "-m", "second");
    String sourceHead = git("-C", source.toString(), "rev-parse", "HEAD");

    assertThat(mirrors.synchronize(project.getId(), ADMIN_EMAIL).initialized()).isTrue();
    assertThat(git("--git-dir=" + mirror, "rev-parse", "refs/heads/main"))
        .isEqualTo(sourceHead);
    assertThat(auditActions()).contains("PROJECT_MIRROR_FETCHED");

    var worktree = worktrees.create(project.getId(), "main", ADMIN_EMAIL);
    Path worktreePath = temporaryDirectory.resolve(
        "workspace/runs/" + project.getId() + "/" + worktree.id());
    assertThat(worktree.status()).isEqualTo(ProjectWorktreeStatus.ACTIVE);
    assertThat(worktreeLocator.requireActiveWorktree(worktree.id())).isEqualTo(worktreePath);
    assertThat(worktreePath.resolve("README.md")).hasContent("second revision\n");
    ToolExecutionContext toolContext = new ToolExecutionContext(worktree.id());
    String apiKey = apiKeys.create(new CreateApiKeyCommand("Agent integration", null), ADMIN_EMAIL)
        .rawKey();
    String apiWorktree = mockMvc.perform(post(
            "/api/v1/projects/{projectId}/worktrees", project.getId())
            .header("Authorization", "Bearer " + apiKey)
            .contentType("application/json")
            .content("{\"revision\":\"main\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.projectId").value(project.getId().toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    UUID apiWorktreeId = UUID.fromString(objectMapper.readTree(apiWorktree).path("id").asText());
    mockMvc.perform(get("/api/v1/projects/{projectId}/worktrees", project.getId())
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].id").value(org.hamcrest.Matchers.hasItem(
            apiWorktreeId.toString())));
    mockMvc.perform(delete(
            "/api/v1/projects/{projectId}/worktrees/{worktreeId}",
            project.getId(), apiWorktreeId)
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("REMOVED"));
    modelCredentials.save(
        ModelProvider.OPENAI,
        new SaveModelCredentialCommand("provider-secret", "configured-model"),
        ADMIN_EMAIL);
    entityManager.flush();
    String encryptedModelKey = jdbc.queryForObject(
        "select api_key_ciphertext from model_provider_credential where provider = 'OPENAI'",
        String.class);
    assertThat(encryptedModelKey)
        .startsWith("v1:")
        .doesNotContain("provider-secret");
    mockMvc.perform(get("/api/v1/agent/tools")
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].name").value(org.hamcrest.Matchers.hasItems(
            "list_files", "read_file", "search_files", "create_patch", "show_diff", "run_tests",
            "list_skills", "load_skill")));
    mockMvc.perform(post("/api/v1/agent/worktrees/{worktreeId}/tools/read_file", worktree.id())
            .header("Authorization", "Bearer " + apiKey)
            .contentType("application/json")
            .content("{\"path\":\"README.md\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.toolName").value("read_file"))
        .andExpect(jsonPath("$.output.content").value("second revision\n"));
    mockMvc.perform(get("/api/v1/agent/worktrees/{worktreeId}/events", worktree.id())
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].type").value(org.hamcrest.Matchers.hasItems(
            "TOOL_STARTED", "TOOL_SUCCEEDED")));
    String createdRun = mockMvc.perform(post(
            "/api/v1/agent/worktrees/{worktreeId}/runs", worktree.id())
            .header("Authorization", "Bearer " + apiKey)
            .contentType("application/json")
            .content("{\"prompt\":\"Inspect this repository\",\"provider\":\"OPENAI\",\"maxModelTurns\":8}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.model").value("configured-model"))
        .andExpect(jsonPath("$.turns[0].kind").value("USER_PROMPT"))
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    UUID agentRunId = UUID.fromString(objectMapper.readTree(createdRun).path("id").asText());
    mockMvc.perform(get(
            "/api/v1/agent/worktrees/{worktreeId}/runs/{runId}", worktree.id(), agentRunId)
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.turns[0].content").value("Inspect this repository"));
    mockMvc.perform(post(
            "/api/v1/agent/worktrees/{worktreeId}/runs/{runId}/execute",
            worktree.id(), agentRunId)
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"))
        .andExpect(jsonPath("$.modelTurnCount").value(4))
        .andExpect(jsonPath("$.finalOutput").value("Repository inspection complete."))
        .andExpect(jsonPath("$.turns[*].kind").value(org.hamcrest.Matchers.hasItems(
            "MODEL_RESPONSE", "TOOL_CALL", "TOOL_RESULT")))
        .andExpect(jsonPath("$.turns[3].content")
            .value(org.hamcrest.Matchers.containsString("second revision")));
    assertThat(MODEL_REQUESTS.get()).isEqualTo(4);
    mockMvc.perform(get(
            "/api/v1/agent/worktrees/{worktreeId}/runs/{runId}/events",
            worktree.id(), agentRunId)
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[*].agentRunId").value(org.hamcrest.Matchers.everyItem(
            org.hamcrest.Matchers.is(agentRunId.toString()))))
        .andExpect(jsonPath("$[*].type").value(org.hamcrest.Matchers.hasItems(
            "TOOL_STARTED", "TOOL_SUCCEEDED")));
    assertThat(proposals.listForAgentRun(agentRunId))
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.agentRunId()).isEqualTo(agentRunId);
          assertThat(candidate.status()).isEqualTo(ChangeProposalStatus.PROPOSED);
        });
    assertThat(testRuns.listForAgentRun(agentRunId))
        .singleElement()
        .satisfies(candidate -> {
          assertThat(candidate.agentRunId()).isEqualTo(agentRunId);
          assertThat(candidate.status()).isEqualTo(TestRunStatus.PROPOSED);
        });
    mockMvc.perform(get("/runs/{runId}", agentRunId)
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .content().string(org.hamcrest.Matchers.containsString("Change approvals")))
        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
            .content().string(org.hamcrest.Matchers.containsString("agent revision")));
    UUID runProposalId = proposals.listForAgentRun(agentRunId).getFirst().id();
    UUID runTestId = testRuns.listForAgentRun(agentRunId).getFirst().id();
    mockMvc.perform(post("/runs/{runId}/changes/{proposalId}/reject", agentRunId, runProposalId)
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf()))
        .andExpect(status().is3xxRedirection());
    mockMvc.perform(post("/runs/{runId}/tests/{testRunId}/reject", agentRunId, runTestId)
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(proposals.listForAgentRun(agentRunId).getFirst().status())
        .isEqualTo(ChangeProposalStatus.REJECTED);
    assertThat(testRuns.listForAgentRun(agentRunId).getFirst().status())
        .isEqualTo(TestRunStatus.REJECTED);
    mockMvc.perform(get("/runs")
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().is3xxRedirection());

    String budgetRun = mockMvc.perform(post(
            "/api/v1/agent/worktrees/{worktreeId}/runs", worktree.id())
            .header("Authorization", "Bearer " + apiKey)
            .contentType("application/json")
            .content("{\"prompt\":\"Inspect within one turn\",\"maxModelTurns\":1}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    UUID budgetRunId = UUID.fromString(objectMapper.readTree(budgetRun).path("id").asText());
    mockMvc.perform(post(
            "/api/v1/agent/worktrees/{worktreeId}/runs/{runId}/execute",
            worktree.id(), budgetRunId)
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("BUDGET_EXHAUSTED"))
        .andExpect(jsonPath("$.modelTurnCount").value(1))
        .andExpect(jsonPath("$.errorMessage").value("Maximum model turns reached"));

    String cancellableRun = mockMvc.perform(post(
            "/api/v1/agent/worktrees/{worktreeId}/runs", worktree.id())
            .header("Authorization", "Bearer " + apiKey)
            .contentType("application/json")
            .content("{\"prompt\":\"Cancel this run\"}"))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    UUID cancellableRunId = UUID.fromString(
        objectMapper.readTree(cancellableRun).path("id").asText());
    mockMvc.perform(post(
            "/api/v1/agent/worktrees/{worktreeId}/runs/{runId}/cancel",
            worktree.id(), cancellableRunId)
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));

    TestRunView requestedTestRun = (TestRunView) tools.execute(
        "run_tests", toolContext, java.util.Map.of()).output();
    assertThat(requestedTestRun.status()).isEqualTo(TestRunStatus.PROPOSED);
    assertThat(requestedTestRun.command()).isEqualTo(
        "./mvnw --batch-mode --no-transfer-progress test");
    assertThatIllegalStateException().isThrownBy(() -> testRuns.execute(requestedTestRun.id()));
    mockMvc.perform(post("/operations/test-runs/{testRunId}/approve", requestedTestRun.id())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("APPROVED"));
    mockMvc.perform(post("/operations/test-runs/{testRunId}/execute", requestedTestRun.id())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PASSED"))
        .andExpect(jsonPath("$.exitCode").value(0))
        .andExpect(jsonPath("$.output").value(org.hamcrest.Matchers.containsString(
            "fixture tests passed")));
    mockMvc.perform(post("/operations/test-runs/{testRunId}/execute", requestedTestRun.id())
            .header("Authorization", "Bearer " + apiKey))
        .andExpect(status().isForbidden());
    TestRunView rejectedTestRun = (TestRunView) tools.execute(
        "run_tests", toolContext, java.util.Map.of()).output();
    assertThat(testRuns.reject(rejectedTestRun.id(), ADMIN_EMAIL).status())
        .isEqualTo(TestRunStatus.REJECTED);
    assertThatIllegalStateException().isThrownBy(() -> testRuns.execute(rejectedTestRun.id()));
    assertThat(auditActions()).contains(
        "TEST_RUN_REQUESTED", "TEST_RUN_APPROVED", "TEST_RUN_STARTED", "TEST_RUN_PASSED",
        "TEST_RUN_REJECTED");

    ReadFileResult read = (ReadFileResult) tools.execute(
        "read_file", toolContext, java.util.Map.of("path", "README.md")).output();
    ChangeProposalView proposal = (ChangeProposalView) tools.execute(
        "create_patch",
        toolContext,
        java.util.Map.of(
            "path", "README.md",
            "expectedSha256", read.sha256(),
            "replacementContent", "approved revision\n"))
        .output();
    assertThat(proposal.status()).isEqualTo(ChangeProposalStatus.PROPOSED);
    assertThat(proposal.unifiedDiff()).contains("-second revision", "+approved revision");
    ChangeProposalView shown = (ChangeProposalView) tools.execute(
        "show_diff", toolContext, java.util.Map.of("proposalId", proposal.id().toString())).output();
    assertThat(shown.id()).isEqualTo(proposal.id());
    assertThat(worktreePath.resolve("README.md")).hasContent("second revision\n");
    assertThatIllegalStateException().isThrownBy(() -> proposals.apply(proposal.id()));

    proposals.approve(proposal.id(), ADMIN_EMAIL);
    ChangeProposalView applied = proposals.apply(proposal.id());
    assertThat(applied.status()).isEqualTo(ChangeProposalStatus.APPLIED);
    assertThat(worktreePath.resolve("README.md")).hasContent("approved revision\n");
    assertThat(auditActions()).contains("CHANGE_PROPOSAL_APPROVED", "CHANGE_PROPOSAL_APPLIED");

    ReadFileResult nextRead = (ReadFileResult) tools.execute(
        "read_file", toolContext, java.util.Map.of("path", "README.md")).output();
    ChangeProposalView stale = (ChangeProposalView) tools.execute(
        "create_patch",
        toolContext,
        java.util.Map.of(
            "path", "README.md",
            "expectedSha256", nextRead.sha256(),
            "replacementContent", "must not overwrite\n"))
        .output();
    proposals.approve(stale.id(), ADMIN_EMAIL);
    Files.writeString(worktreePath.resolve("README.md"), "external edit\n");
    assertThat(proposals.apply(stale.id()).status()).isEqualTo(ChangeProposalStatus.CONFLICT);
    assertThat(worktreePath.resolve("README.md")).hasContent("external edit\n");
    assertThat(auditActions()).contains("CHANGE_PROPOSAL_CONFLICT");

    ReadFileResult rejectionRead = (ReadFileResult) tools.execute(
        "read_file", toolContext, java.util.Map.of("path", "README.md")).output();
    ChangeProposalView rejectedCandidate = (ChangeProposalView) tools.execute(
        "create_patch",
        toolContext,
        java.util.Map.of(
            "path", "README.md",
            "expectedSha256", rejectionRead.sha256(),
            "replacementContent", "rejected edit\n"))
        .output();
    assertThat(proposals.reject(rejectedCandidate.id(), ADMIN_EMAIL).status())
        .isEqualTo(ChangeProposalStatus.REJECTED);
    assertThatIllegalStateException().isThrownBy(() -> proposals.apply(rejectedCandidate.id()));
    assertThat(worktreePath.resolve("README.md")).hasContent("external edit\n");
    assertThat(auditActions()).contains("CHANGE_PROPOSAL_REJECTED");

    ChangeProposalView creation = (ChangeProposalView) tools.execute(
        "create_file",
        toolContext,
        java.util.Map.of(
            "path", "notes/new-file.md",
            "content", "created by agent\n"))
        .output();
    assertThat(creation.status()).isEqualTo(ChangeProposalStatus.PROPOSED);
    assertThat(creation.changeType()).isEqualTo(ChangeType.CREATE);
    assertThat(creation.expectedSha256()).isNull();
    assertThat(creation.unifiedDiff()).contains("--- /dev/null", "+++ b/notes/new-file.md");
    assertThat(worktreePath.resolve("notes/new-file.md")).doesNotExist();
    assertThatIllegalArgumentException().isThrownBy(() -> tools.execute(
        "create_file",
        toolContext,
        java.util.Map.of("path", "README.md", "content", "overwrite\n")));
    proposals.approve(creation.id(), ADMIN_EMAIL);
    ChangeProposalView appliedCreation = proposals.apply(creation.id());
    assertThat(appliedCreation.status()).isEqualTo(ChangeProposalStatus.APPLIED);
    assertThat(worktreePath.resolve("notes/new-file.md")).hasContent("created by agent\n");
    assertThat(auditActions()).contains("CHANGE_PROPOSAL_APPROVED", "CHANGE_PROPOSAL_APPLIED");

    Files.writeString(worktreePath.resolve("untracked.txt"), "disposable\n");
    assertThat(auditActions()).contains("PROJECT_WORKTREE_CREATED");

    var removed = worktrees.remove(worktree.id(), ADMIN_EMAIL);
    assertThat(removed.status()).isEqualTo(ProjectWorktreeStatus.REMOVED);
    assertThat(worktreePath).doesNotExist();
    assertThat(auditActions()).contains("PROJECT_WORKTREE_REMOVED");
    assertThatIllegalStateException()
        .isThrownBy(() -> worktreeLocator.requireActiveWorktree(worktree.id()));
  }

  @Test
  void usesMirrorHeadWhenConfiguredDefaultBranchDoesNotExist() throws Exception {
    Path source = createSourceRepository("master");
    Project project = projectService.create(
        new CreateProjectCommand(
            "Default branch fallback " + UUID.randomUUID(), source.toString(), "main"),
        ADMIN_EMAIL);

    mirrors.synchronize(project.getId(), ADMIN_EMAIL);
    var worktree = worktrees.create(project.getId(), null, ADMIN_EMAIL);

    assertThat(worktree.gitRevision()).isEqualTo("master");
    assertThat(worktreeLocator.requireActiveWorktree(worktree.id()).resolve("README.md"))
        .hasContent("first revision\n");
  }

  private Path createSourceRepository() throws Exception {
    return createSourceRepository("main");
  }

  private Path createSourceRepository(String initialBranch) throws Exception {
    Path source = temporaryDirectory.resolve("source-" + UUID.randomUUID());
    git("init", "--initial-branch=" + initialBranch, source.toString());
    git("-C", source.toString(), "config", "user.name", "Petri Test");
    git("-C", source.toString(), "config", "user.email", "petri@example.test");
    Files.writeString(source.resolve("README.md"), "first revision\n");
    Files.writeString(source.resolve("pom.xml"), "<project/>\n");
    Path wrapper = source.resolve("mvnw");
    Files.writeString(wrapper, "#!/bin/sh\nprintf 'fixture tests passed\\n'\n");
    if (!wrapper.toFile().setExecutable(true)) {
      throw new AssertionError("Could not mark fixture Maven wrapper executable");
    }
    git("-C", source.toString(), "add", "README.md", "pom.xml", "mvnw");
    git("-C", source.toString(), "commit", "-m", "first");
    return source;
  }

  private static String git(String... arguments) throws Exception {
    java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git"));
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
    int exitCode = process.waitFor();
    if (exitCode != 0) throw new AssertionError("Git test setup failed: " + output);
    return output;
  }

  private static HttpServer modelServer() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
      server.createContext("/responses", ProjectMirrorServiceTest::respondAsModel);
      server.start();
      return server;
    } catch (java.io.IOException exception) {
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static void respondAsModel(HttpExchange exchange) throws java.io.IOException {
    String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    int requestNumber = MODEL_REQUESTS.incrementAndGet();
    boolean continuation = request.contains("\"previous_response_id\"");
    String response;
    if (requestNumber == 2) {
      response = """
          {"id":"resp_patch_2","status":"completed","output":[
            {"type":"function_call","call_id":"call_2","name":"create_patch",
             "arguments":"{\\\"path\\\":\\\"README.md\\\",\\\"expectedSha256\\\":\\\"efb3539512cde51a50afa53ba67d29102829c744a5506119674e4e31f3737ee6\\\",\\\"replacementContent\\\":\\\"agent revision\\\\n\\\"}"}
          ],"usage":{"input_tokens":20,"output_tokens":8}}
          """;
    } else if (requestNumber == 3) {
      response = """
          {"id":"resp_tests_3","status":"completed","output":[
            {"type":"function_call","call_id":"call_3","name":"run_tests","arguments":"{}"}
          ],"usage":{"input_tokens":24,"output_tokens":4}}
          """;
    } else if (continuation) {
      response = """
          {"id":"resp_final_%d","status":"completed","output":[
            {"type":"message","content":[
              {"type":"output_text","text":"Repository inspection complete."}
            ]}
          ],"usage":{"input_tokens":20,"output_tokens":5}}
          """.formatted(requestNumber);
    } else {
      response = """
          {"id":"resp_tool_%d","status":"completed","output":[
            {"type":"function_call","call_id":"call_%d","name":"read_file",
             "arguments":"{\\\"path\\\":\\\"README.md\\\"}"}
          ],"usage":{"input_tokens":10,"output_tokens":4}}
          """.formatted(requestNumber, requestNumber);
    }
    byte[] body = response.getBytes(StandardCharsets.UTF_8);
    if (request.contains("\"stream\":true")) {
      body = ("data: {\"type\":\"response.completed\",\"response\":"
          + response.replace("\n", "") + "}\n\n")
          .getBytes(StandardCharsets.UTF_8);
      exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
    } else {
      exchange.getResponseHeaders().set("Content-Type", "application/json");
    }
    exchange.sendResponseHeaders(200, body.length);
    exchange.getResponseBody().write(body);
    exchange.close();
  }

  private List<String> auditActions() {
    return auditEvents.findTop100ByOrderByOccurredAtDesc().stream()
        .map(event -> event.getAction())
        .toList();
  }
}
