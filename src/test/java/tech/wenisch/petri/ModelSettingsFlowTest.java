package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Transactional
class ModelSettingsFlowTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SecretCipher cipher;
  @Autowired AppUserRepository users;
  @PersistenceContext EntityManager entityManager;

  @Test
  void administratorCanConfigureAndUpdateModelWithoutReenteringSecret() throws Exception {
    mockMvc.perform(post("/model-settings")
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("apiKey", "provider-super-secret")
            .param("model", "test-reasoning-model")
            .param("maxOutputTokens", "8192")
            .param("reasoningEffort", "HIGH")
            .param("textVerbosity", "LOW"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/model-settings"));

    String stored = storedSecret();
    assertThat(stored).startsWith("v1:").doesNotContain("provider-super-secret");
    assertThat(cipher.decrypt(stored)).isEqualTo("provider-super-secret");
    assertThat(jdbc.queryForObject(
        "select model_name from model_provider_credential where provider = 'OPENAI'",
        String.class)).isEqualTo("test-reasoning-model");

    mockMvc.perform(get("/model-settings")
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("test-reasoning-model")))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("provider-super-secret"))));

    mockMvc.perform(post("/model-settings")
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("apiKey", "")
            .param("model", "replacement-model")
            .param("maxOutputTokens", "16384")
            .param("reasoningEffort", "MEDIUM")
            .param("textVerbosity", "HIGH"))
        .andExpect(status().is3xxRedirection());

    assertThat(cipher.decrypt(storedSecret())).isEqualTo("provider-super-secret");
    assertThat(jdbc.queryForMap(
        "select model_name, max_output_tokens, reasoning_effort, text_verbosity "
            + "from model_provider_credential where provider = 'OPENAI'"))
        .containsEntry("model_name", "replacement-model")
        .containsEntry("max_output_tokens", 16384)
        .containsEntry("reasoning_effort", "MEDIUM")
        .containsEntry("text_verbosity", "HIGH");
  }

  @Test
  void modelSettingsRemainAdministratorOnlyWhileUsersCanOpenChat() throws Exception {
    String memberEmail = "member@petri.local";
    users.save(new AppUser(UUID.randomUUID(), memberEmail, "unused", "USER"));

    mockMvc.perform(get("/model-settings").with(user(memberEmail).roles("USER")))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/chat").with(user(memberEmail).roles("USER")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/projects"));
    mockMvc.perform(post("/model-settings")
            .with(user(memberEmail).roles("USER"))
            .with(csrf())
            .param("apiKey", "must-not-be-stored")
            .param("model", "model")
            .param("maxOutputTokens", "4096"))
        .andExpect(status().isForbidden());
  }

  @Test
  void administratorCanConfigureKeylessLocalOllamaEndpoint() throws Exception {
    mockMvc.perform(post("/model-settings/OLLAMA")
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("apiKey", "")
            .param("baseUrl", "http://ollama:11434/v1/")
            .param("model", "qwen3-coder")
            .param("maxOutputTokens", "4096")
            .param("reasoningEffort", "MEDIUM"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/model-settings"));

    entityManager.flush();
    assertThat(jdbc.queryForMap(
        "select api_key_ciphertext, base_url, model_name from model_provider_credential "
            + "where provider = 'OLLAMA'"))
        .containsEntry("api_key_ciphertext", null)
        .containsEntry("base_url", "http://ollama:11434/v1")
        .containsEntry("model_name", "qwen3-coder");

    mockMvc.perform(get("/model-settings")
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("qwen3-coder")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString(
            "http://ollama:11434/v1")));
  }

  @Test
  void chatRendersConfigurationCallToActionWithoutCredentials() throws Exception {
    jdbc.update("delete from model_provider_credential where provider = 'OPENAI'");

    mockMvc.perform(get("/chat").with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/projects"));
  }

  @Test
  void invalidModelSettingsNeverEchoOrStoreTheSubmittedSecret() throws Exception {
    mockMvc.perform(post("/model-settings")
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("apiKey", "must-never-be-rendered")
            .param("model", "")
            .param("maxOutputTokens", "2"))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("must-never-be-rendered"))));

    assertThat(jdbc.queryForObject(
        "select count(*) from model_provider_credential where provider = 'OPENAI'",
        Integer.class)).isZero();
  }

  @Test
  void chatRendersACompletedConversationAndGenerationSnapshot() throws Exception {
    UUID actorId = jdbc.queryForObject(
        "select id from app_user where email = ?", UUID.class, ADMIN_EMAIL);
    UUID projectId = UUID.randomUUID();
    UUID worktreeId = UUID.randomUUID();
    UUID runId = UUID.randomUUID();
    UUID chatId = UUID.randomUUID();
    Instant now = Instant.parse("2026-10-09T10:00:00Z");
    Timestamp timestamp = Timestamp.from(now);
    jdbc.update(
        "insert into agent_project "
            + "(id, name, slug, repository_uri, default_branch, status, created_by, created_at, updated_at) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        projectId, "Chat test", "chat-test-" + projectId, "https://example.test/repo.git",
        "main", "ACTIVE", actorId, timestamp, timestamp);
    jdbc.update(
        "insert into project_worktree "
            + "(id, project_id, git_revision, relative_path, status, created_by, created_at) "
            + "values (?, ?, ?, ?, ?, ?, ?)",
        worktreeId, projectId, "main", "projects/" + projectId + "/runs/" + worktreeId,
        "ACTIVE", actorId, timestamp);
    jdbc.update(
        "insert into agent_chat "
            + "(id, worktree_id, title, queue_paused, approval_gate_overridden, next_message_index, "
            + "created_by, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        chatId, worktreeId, "Explain the module", false, false, 1, actorId, timestamp, timestamp);
    jdbc.update(
        "insert into agent_run "
            + "(id, chat_id, chat_message_index, context_window_tokens, worktree_id, provider, model_name, prompt, status, max_model_turns, "
            + "model_turn_count, next_turn_index, final_output, created_by, created_at, "
            + "started_at, completed_at, max_output_tokens, reasoning_effort, text_verbosity) "
            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
        runId, chatId, 0, 32768, worktreeId, "OPENAI", "chat-model", "Explain the module", "COMPLETED",
        20, 1, 2, "The module is ready.", actorId, timestamp, timestamp, timestamp,
        12000, "HIGH", "LOW");
    jdbc.update(
        "insert into agent_turn "
            + "(id, run_id, turn_index, turn_kind, content, created_at) values (?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(), runId, 0, "USER_PROMPT", "Explain the module", timestamp);
    jdbc.update(
        "insert into agent_turn "
            + "(id, run_id, turn_index, turn_kind, provider_response_id, content, created_at) "
            + "values (?, ?, ?, ?, ?, ?, ?)",
        UUID.randomUUID(), runId, 1, "MODEL_RESPONSE", "resp_test",
        "The module is ready.", timestamp);

    mockMvc.perform(get("/chat")
            .param("run", runId.toString())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/workspace/projects/" + projectId
            + "?worktree=" + worktreeId + "&chat=" + chatId));

    mockMvc.perform(get("/workspace/projects/" + projectId)
            .param("worktree", worktreeId.toString())
            .param("chat", chatId.toString())
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Explain the module")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("The module is ready.")));
  }

  private String storedSecret() {
    entityManager.flush();
    return jdbc.queryForObject(
        "select api_key_ciphertext from model_provider_credential where provider = 'OPENAI'",
        String.class);
  }
}
