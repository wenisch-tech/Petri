package tech.wenisch.petri;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.project.Project;
import tech.wenisch.petri.project.ProjectRepository;
import tech.wenisch.petri.secret.SecretCipher;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Transactional
class KubernetesWorkspaceFlowTest {
  private static final String ADMIN = "test-admin@petri.local";
  private static final String KUBECONFIG =
      "apiVersion: v1\nclusters:\n- name: c\n  cluster: {server: 'https://k8s.test:6443'}\n"
          + "users:\n- name: u\n  user: {token: super-secret-token}\nkind: Config\n";

  @Autowired MockMvc mockMvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired SecretCipher cipher;
  @Autowired ProjectRepository projects;
  @PersistenceContext EntityManager entityManager;

  @Test
  void onlyAnAdministratorManagesTheDefaults() throws Exception {
    mockMvc.perform(get("/admin/kubernetes").with(user("someone@petri.local").roles("USER")))
        .andExpect(status().isForbidden());
    mockMvc.perform(post("/admin/kubernetes").with(user("someone@petri.local").roles("USER"))
            .with(csrf()).param("authMode", "KUBECONFIG"))
        .andExpect(status().isForbidden());
    mockMvc.perform(get("/admin/kubernetes").with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Kubernetes workspaces")))
        .andExpect(content().string(containsString("Docker image")));
  }

  @Test
  void aKubeconfigIsStoredEncryptedAndNeverRenderedBack() throws Exception {
    mockMvc.perform(post("/admin/kubernetes").with(user(ADMIN).roles("INSTANCE_ADMIN")).with(csrf())
            .param("authMode", "KUBECONFIG").param("kubeconfig", KUBECONFIG)
            .param("namespace", "petri-workspaces").param("image", "registry.test/ws:1"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/admin/kubernetes"));
    entityManager.flush();

    String stored = jdbc.queryForObject(
        "select kubeconfig_ciphertext from kubernetes_workspace_config where project_id is null", String.class);
    assertThat(stored).startsWith("v1:").doesNotContain("super-secret-token");
    assertThat(cipher.decrypt(stored)).contains("super-secret-token");

    mockMvc.perform(get("/admin/kubernetes").with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("registry.test/ws:1")))
        .andExpect(content().string(containsString("A kubeconfig is stored")))
        .andExpect(content().string(not(containsString("super-secret-token"))));
  }

  @Test
  void aProjectOwnerSeesTheTabAndCannotEnableWithoutACluster() throws Exception {
    mockMvc.perform(post("/projects").with(user(ADMIN).roles("INSTANCE_ADMIN")).with(csrf())
            .param("name", "K8s Project").param("repositoryUri", "https://git.example.test/a/b.git")
            .param("defaultBranch", "main"))
        .andExpect(status().is3xxRedirection());
    Project project = projects.findBySlug("k8s-project").orElseThrow();
    String tab = "/projects/" + project.getId() + "/settings/kubernetes";

    mockMvc.perform(get(tab).with(user(ADMIN).roles("INSTANCE_ADMIN")))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Run this project's workspace in a Kubernetes pod")))
        .andExpect(content().string(containsString("No default cluster is configured")));
    mockMvc.perform(post(tab).with(user(ADMIN).roles("INSTANCE_ADMIN")).with(csrf()).param("enabled", "true"))
        .andExpect(status().is3xxRedirection()).andExpect(redirectedUrl(tab));
    assertThat(jdbc.queryForObject(
        "select count(*) from kubernetes_workspace_config where enabled = true", Integer.class)).isZero();

    mockMvc.perform(get(tab).with(user("stranger@petri.local").roles("USER")))
        .andExpect(status().isNotFound());
  }
}
