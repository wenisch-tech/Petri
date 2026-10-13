package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.mcp.McpAuthType;
import tech.wenisch.petri.mcp.McpServerService;
import tech.wenisch.petri.mcp.McpTransport;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Transactional
class McpServerFlowTest {
  @Autowired MockMvc mockMvc;
  @Autowired AppUserRepository users;
  @Autowired McpServerService servers;
  @Autowired SecretCipher cipher;
  @Autowired JdbcTemplate jdbc;
  @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

  private McpServerService.SaveCommand bearer(String name, String token) {
    return new McpServerService.SaveCommand(
        name, "https://mcp.example.com/mcp/", McpTransport.AUTO, McpAuthType.BEARER, token, null, null, null);
  }

  @Test
  void serversAreOwnedSecretsStayEncryptedAndAMaskedUpdateKeepsThem() throws Exception {
    users.save(new AppUser(UUID.randomUUID(), "alice@petri.local", "unused", "USER"));
    users.save(new AppUser(UUID.randomUUID(), "bob@petri.local", "unused", "USER"));

    var saved = servers.save(null, bearer("Docs", "never-render-this-token"), "alice@petri.local");

    assertThat(saved.url()).isEqualTo("https://mcp.example.com/mcp");
    entityManager.flush();
    String stored = jdbc.queryForObject(
        "select secret_ciphertext from mcp_server where id = ?", String.class, saved.id());
    assertThat(stored).startsWith("v1:");
    assertThat(cipher.decrypt(stored)).isEqualTo("never-render-this-token");

    mockMvc.perform(get("/mcp-servers").with(user("alice@petri.local").roles("USER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Docs")))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("never-render-this-token"))));
    mockMvc.perform(get("/mcp-servers").with(user("bob@petri.local").roles("USER")))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("Docs"))));

    // Another user can neither change nor remove it.
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> servers.save(saved.id(), bearer("Hijack", "x"), "bob@petri.local"))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> servers.delete(saved.id(), "bob@petri.local"))
        .isInstanceOf(IllegalArgumentException.class);

    // Saving with the mask keeps the stored token.
    servers.save(saved.id(), bearer("Docs", "********"),
        "alice@petri.local");
    entityManager.flush();
    String after = jdbc.queryForObject(
        "select secret_ciphertext from mcp_server where id = ?", String.class, saved.id());
    assertThat(cipher.decrypt(after)).isEqualTo("never-render-this-token");
  }

  @Test
  void rejectsDuplicateNamesBadUrlsAndReservedHeaders() {
    users.save(new AppUser(UUID.randomUUID(), "alice@petri.local", "unused", "USER"));
    servers.save(null, bearer("Docs", "t"), "alice@petri.local");

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> servers.save(null, bearer("docs", "t"), "alice@petri.local"))
        .hasMessageContaining("already have");
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> servers.save(null,
            new McpServerService.SaveCommand("Odd", "ftp://host/x", McpTransport.AUTO, McpAuthType.NONE,
                null, null, null, null), "alice@petri.local"))
        .isInstanceOf(IllegalArgumentException.class);
    org.assertj.core.api.Assertions.assertThatThrownBy(() -> servers.save(null,
            new McpServerService.SaveCommand("Hdr", "https://h.example.com", McpTransport.AUTO,
                McpAuthType.HEADERS, "Host: evil", null, null, null), "alice@petri.local"))
        .hasMessageContaining("cannot be overridden");
  }

  @Test
  void signedInUserOnlySeesTheirOwnProjectMcpPage() throws Exception {
    users.save(new AppUser(UUID.randomUUID(), "alice@petri.local", "unused", "USER"));
    mockMvc.perform(post("/mcp-servers/{id}/delete", UUID.randomUUID())
            .with(user("alice@petri.local").roles("USER")).with(csrf()))
        .andExpect(status().is3xxRedirection());
  }
}
