package tech.wenisch.petri.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tech.wenisch.petri.config.PetriProperties;
import tech.wenisch.petri.workspace.WorktreeLocator;

class WorkspaceFileToolsTest {
  @TempDir Path temporaryDirectory;

  private Path worktree;
  private WorkspaceFileService files;
  private AgentToolRegistry registry;
  private UUID worktreeId;

  @BeforeEach
  void setUp() throws Exception {
    worktree = Files.createDirectory(temporaryDirectory.resolve("worktree")).toRealPath();
    Files.createDirectories(worktree.resolve("src/main"));
    Files.writeString(worktree.resolve("README.md"), "Petri workspace\nneedle one\n");
    Files.writeString(worktree.resolve("src/main/App.java"),
        "class App {\n  String first = \"needle two\";\n  String second = \"needle three\";\n}\n");
    Files.writeString(worktree.resolve(".git"), "gitdir: elsewhere\n");
    Files.createDirectories(worktree.resolve("vendor/.git"));
    Files.writeString(worktree.resolve("vendor/.git/config"), "must stay hidden\n");
    PetriProperties properties = new PetriProperties(
        null,
        null,
        new PetriProperties.Workspace(
            temporaryDirectory.resolve("workspace-root").toString(),
            Duration.ofSeconds(5),
            4096,
            1024,
            4096,
            2,
            null,
            null),
        null);
    files = new WorkspaceFileService(properties);
    WorktreeLocator locator = ignored -> worktree;
    registry = new AgentToolRegistry(List.of(
        new ListFilesTool(locator, files),
        new ReadFileTool(locator, files),
        new SearchFilesTool(locator, files)));
    worktreeId = UUID.randomUUID();
  }

  @Test
  void listsReadsAndSearchesThroughRegisteredTools() {
    ToolExecutionContext context = new ToolExecutionContext(worktreeId);

    ListFilesResult listed = (ListFilesResult) registry.execute(
        "list_files", context, Map.of("maxDepth", 3)).output();
    assertThat(listed.entries()).extracting(FileEntry::path)
        .contains("README.md", "src", "src/main", "src/main/App.java")
        .doesNotContain(".git", "vendor/.git", "vendor/.git/config");

    ReadFileResult read = (ReadFileResult) registry.execute(
        "read_file", context, Map.of("path", "README.md")).output();
    assertThat(read.content()).isEqualTo("Petri workspace\nneedle one\n");
    assertThat(read.sha256()).hasSize(64);
    assertThat(read.size()).isEqualTo(read.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length);

    SearchFilesResult searched = (SearchFilesResult) registry.execute(
        "search_files", context, Map.of("query", "needle", "maxResults", 50)).output();
    assertThat(searched.matches()).hasSize(2);
    assertThat(searched.matches()).extracting(SearchMatch::path)
        .containsExactly("README.md", "src/main/App.java");
    assertThat(searched.truncated()).isTrue();
  }

  @Test
  void hashChangesWhenFileChangesAfterRead() throws Exception {
    ToolExecutionContext context = new ToolExecutionContext(worktreeId);
    ReadFileResult before = (ReadFileResult) registry.execute(
        "read_file", context, Map.of("path", "README.md")).output();

    Files.writeString(worktree.resolve("README.md"), "changed\n");
    ReadFileResult after = (ReadFileResult) registry.execute(
        "read_file", context, Map.of("path", "README.md")).output();

    assertThat(after.sha256()).isNotEqualTo(before.sha256());
  }

  @Test
  void rejectsTraversalExternalSymlinksBinaryAndOversizedFiles() throws Exception {
    Path outside = temporaryDirectory.resolve("outside.txt");
    Files.writeString(outside, "outside\n");
    Files.createSymbolicLink(worktree.resolve("outside-link"), outside);
    Files.write(worktree.resolve("binary.bin"), new byte[] {(byte) 0xc3, (byte) 0x28});
    Files.writeString(worktree.resolve("large.txt"), "x".repeat(1025));
    ToolExecutionContext context = new ToolExecutionContext(worktreeId);

    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "read_file", context, Map.of("path", "../outside.txt")));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "read_file", context, Map.of("path", "/etc/passwd")));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "read_file", context, Map.of("path", "outside-link")));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "read_file", context, Map.of("path", "binary.bin")));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "read_file", context, Map.of("path", "large.txt")));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "read_file", context, Map.of("path", ".git")));
  }

  @Test
  void createsNewFileThroughCreateFileTool() throws Exception {
    ToolExecutionContext context = new ToolExecutionContext(worktreeId);

    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "create_file", context, Map.of("path", "README.md", "content", "overwrite\n")));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "create_file", context, Map.of("path", "../outside.md", "content", "x\n")));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "create_file", context, Map.of("path", "docs/Notes.md")));

    ReadFileResult created = (ReadFileResult) files.applyCreation(
        worktree, "src/main/Notes.md", "new file\n");
    assertThat(created.path()).isEqualTo("src/main/Notes.md");
    assertThat(worktree.resolve("src/main/Notes.md")).hasContent("new file\n");

    ReadFileResult nested = (ReadFileResult) files.applyCreation(
        worktree, "missing/dir/Notes.md", "nested\n");
    assertThat(nested.path()).isEqualTo("missing/dir/Notes.md");
    assertThat(worktree.resolve("missing/dir/Notes.md")).hasContent("nested\n");

    assertThatIllegalArgumentException().isThrownBy(() -> files.applyCreation(
        worktree, "README.md/blocked.md", "blocked\n"));

    ReplacementPreview preview = files.previewCreation(worktree, "src/main/More.md", "more\n");
    assertThat(preview.path()).isEqualTo("src/main/More.md");
    assertThat(preview.expectedSha256()).isNull();
    assertThat(preview.diff()).contains("--- /dev/null", "+++ b/src/main/More.md", "+more");

    ReplacementPreview deferred = files.previewCreation(worktree, "deferred/Deep.md", "deep\n");
    assertThat(deferred.path()).isEqualTo("deferred/Deep.md");
    assertThat(worktree.resolve("deferred")).doesNotExist();
  }

  @Test
  void exposesStableDefinitionsAndRejectsUnknownOrMalformedCalls() {
    assertThat(registry.definitions()).extracting(ToolDefinition::name)
        .containsExactly("list_files", "read_file", "search_files");
    assertThat(registry.definitions())
        .allSatisfy(definition -> {
          assertThat(definition.inputSchema())
              .containsEntry("type", "object")
              .containsEntry("additionalProperties", false);
          @SuppressWarnings("unchecked")
          Map<String, Object> properties =
              (Map<String, Object>) definition.inputSchema().get("properties");
          @SuppressWarnings("unchecked")
          List<String> required = (List<String>) definition.inputSchema().get("required");
          assertThat(required).containsExactlyInAnyOrderElementsOf(properties.keySet());
        });

    ToolExecutionContext context = new ToolExecutionContext(worktreeId);
    assertThatIllegalArgumentException()
        .isThrownBy(() -> registry.execute("unknown", context, Map.of()));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "list_files", context, Map.of("maxDepth", 21)));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.execute(
        "read_file", context, Map.of()));
  }
}
