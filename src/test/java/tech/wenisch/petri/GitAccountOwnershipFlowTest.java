package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.git.provider.GitAccountConnection;
import tech.wenisch.petri.git.provider.GitAccountConnectionRepository;
import tech.wenisch.petri.git.provider.GitConnectionAuthMethod;
import tech.wenisch.petri.git.provider.GitConnectionStatus;
import tech.wenisch.petri.git.provider.GitProviderInstance;
import tech.wenisch.petri.git.provider.GitProviderInstanceRepository;
import tech.wenisch.petri.git.provider.ProviderIdentity;
import tech.wenisch.petri.secret.SecretCipher;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Transactional
class GitAccountOwnershipFlowTest {
  private static final UUID GITHUB = UUID.fromString("10000000-0000-0000-0000-000000000001");

  @Autowired MockMvc mockMvc;
  @Autowired AppUserRepository users;
  @Autowired GitAccountConnectionRepository connections;
  @Autowired GitProviderInstanceRepository providerInstances;
  @Autowired SecretCipher cipher;

  @Test
  void personalConnectionsAndSelfHostedInstancesAreOwnerScoped() throws Exception {
    AppUser alice = users.save(new AppUser(UUID.randomUUID(), "alice@petri.local", "unused", "USER"));
    users.save(new AppUser(UUID.randomUUID(), "bob@petri.local", "unused", "USER"));
    GitAccountConnection connection = connections.save(new GitAccountConnection(
        UUID.randomUUID(), GITHUB, alice.getId(), GitConnectionAuthMethod.PAT,
        new ProviderIdentity("github-7", "alice", "Alice"),
        cipher.encrypt("never-render-this-token"), null, null, null, "repo", Instant.now()));

    mockMvc.perform(get("/git-accounts").with(user("alice@petri.local").roles("USER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("alice")))
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("never-render-this-token"))));

    mockMvc.perform(get("/git-connections/{id}/repositories", connection.getId())
            .with(user("bob@petri.local").roles("USER")))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/git-accounts/{id}/disconnect", connection.getId())
            .with(user("bob@petri.local").roles("USER"))
            .with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/git-accounts/{id}/validate", connection.getId())
            .with(user("bob@petri.local").roles("USER"))
            .with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/git-accounts/{id}/token", connection.getId())
            .param("token", "stolen-attempt")
            .with(user("bob@petri.local").roles("USER"))
            .with(csrf()))
        .andExpect(status().isNotFound());
    mockMvc.perform(get("/git-accounts").with(user("alice@petri.local").roles("USER")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Update token")));

    mockMvc.perform(get("/git-provider-instances")
            .with(user("alice@petri.local").roles("USER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString(
            "Your self-hosted Git instances")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("FORGEJO")));

    String forgejoUrl = "https://forgejo.alice.test";
    mockMvc.perform(post("/git-provider-instances")
            .with(user("alice@petri.local").roles("USER"))
            .with(csrf())
            .param("providerType", "FORGEJO")
            .param("displayName", "Alice Forgejo")
            .param("webBaseUrl", forgejoUrl)
            .param("apiBaseUrl", forgejoUrl + "/api/v1")
            .param("enabled", "true"))
        .andExpect(status().is3xxRedirection());
    GitProviderInstance aliceForgejo = providerInstances.findAll().stream()
        .filter(instance -> forgejoUrl.equals(instance.getWebBaseUrl()))
        .filter(instance -> alice.getId().equals(instance.getOwnerId()))
        .findFirst().orElseThrow();

    mockMvc.perform(get("/git-provider-instances")
            .with(user("alice@petri.local").roles("USER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Alice Forgejo")));
    mockMvc.perform(get("/git-provider-instances")
            .with(user("bob@petri.local").roles("USER")))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.not(
            org.hamcrest.Matchers.containsString("Alice Forgejo"))));
    mockMvc.perform(post("/git-accounts/pat")
            .with(user("bob@petri.local").roles("USER"))
            .with(csrf())
            .param("instanceId", aliceForgejo.getId().toString())
            .param("token", "must-not-be-sent"))
        .andExpect(status().isNotFound());
    mockMvc.perform(post("/git-provider-instances")
            .with(user("bob@petri.local").roles("USER"))
            .with(csrf())
            .param("id", aliceForgejo.getId().toString())
            .param("providerType", "FORGEJO")
            .param("displayName", "Stolen Forgejo")
            .param("webBaseUrl", forgejoUrl)
            .param("apiBaseUrl", forgejoUrl + "/api/v1")
            .param("enabled", "true"))
        .andExpect(status().isNotFound());

    mockMvc.perform(post("/git-provider-instances")
            .with(user("bob@petri.local").roles("USER"))
            .with(csrf())
            .param("providerType", "FORGEJO")
            .param("displayName", "Bob Forgejo")
            .param("webBaseUrl", forgejoUrl)
            .param("apiBaseUrl", forgejoUrl + "/api/v1")
            .param("enabled", "true"))
        .andExpect(status().is3xxRedirection());
    assertThat(providerInstances.findAll().stream()
        .filter(instance -> forgejoUrl.equals(instance.getWebBaseUrl())))
        .hasSize(2);

    mockMvc.perform(get("/sandbox-profiles")
            .with(user("alice@petri.local").roles("USER")))
        .andExpect(status().isForbidden());

    mockMvc.perform(post("/git-accounts/{id}/disconnect", connection.getId())
            .with(user("alice@petri.local").roles("USER"))
            .with(csrf()))
        .andExpect(status().is3xxRedirection());
    assertThat(connections.findById(connection.getId()).orElseThrow().getStatus())
        .isEqualTo(GitConnectionStatus.DISCONNECTED);
  }
}
