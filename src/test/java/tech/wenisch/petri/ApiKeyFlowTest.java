package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.apikey.ApiKey;
import tech.wenisch.petri.apikey.ApiKeyRepository;
import tech.wenisch.petri.apikey.ApiKeyService;
import tech.wenisch.petri.apikey.CreateApiKeyCommand;
import tech.wenisch.petri.apikey.IssuedApiKey;
import tech.wenisch.petri.audit.AuditEventRepository;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class ApiKeyFlowTest {
  private static final String ADMIN_EMAIL = "test-admin@petri.local";

  @Autowired MockMvc mockMvc;
  @Autowired ApiKeyService apiKeys;
  @Autowired ApiKeyRepository keys;
  @Autowired AuditEventRepository auditEvents;

  @Test
  void issuedKeyAuthenticatesThenStopsWorkingAfterRevocation() throws Exception {
    IssuedApiKey issued = apiKeys.create(new CreateApiKeyCommand("VS Code integration", null), ADMIN_EMAIL);
    assertThat(issued.rawKey()).startsWith("petri_pk_");

    mockMvc.perform(get("/api/v1/identity").header("Authorization", "Bearer " + issued.rawKey()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.keyId").value(issued.id().toString()))
        .andExpect(jsonPath("$.keyName").value("VS Code integration"));

    ApiKey stored = keys.findById(issued.id()).orElseThrow();
    assertThat(stored.getLastUsedAt()).isNotNull();
    assertThat(auditEvents.findTop100ByOrderByOccurredAtDesc())
        .anySatisfy(event -> assertThat(event.getAction()).isEqualTo("API_KEY_CREATED"));

    assertThat(apiKeys.revoke(issued.id(), ADMIN_EMAIL)).isTrue();
    mockMvc.perform(get("/api/v1/identity").header("Authorization", "Bearer " + issued.rawKey()))
        .andExpect(status().isUnauthorized());
    assertThat(auditEvents.findTop100ByOrderByOccurredAtDesc())
        .anySatisfy(event -> assertThat(event.getAction()).isEqualTo("API_KEY_REVOKED"));
  }

  @Test
  void apiEndpointRejectsMissingCredentials() throws Exception {
    mockMvc.perform(get("/api/v1/identity"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void administratorCanCreateKeyFromUiAndUsersCannotManageKeys() throws Exception {
    mockMvc.perform(post("/api-keys")
            .with(user(ADMIN_EMAIL).roles("INSTANCE_ADMIN"))
            .with(csrf())
            .param("name", "Build runner"))
        .andExpect(status().isOk())
        .andExpect(header().string("Cache-Control", "no-store, max-age=0"))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("Save this API key now")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("petri_pk_")));

    mockMvc.perform(get("/api-keys").with(user("member@petri.local").roles("USER")))
        .andExpect(status().isForbidden());
  }
}
