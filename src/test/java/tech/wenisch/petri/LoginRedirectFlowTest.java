package tech.wenisch.petri;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import tech.wenisch.petri.security.AppUser;
import tech.wenisch.petri.security.AppUserRepository;

@SpringBootTest(properties =
    "petri.secrets.master-key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=")
@AutoConfigureMockMvc
@Transactional
class LoginRedirectFlowTest {
  @Autowired MockMvc mockMvc;
  @Autowired AppUserRepository users;
  @Autowired PasswordEncoder encoder;

  private void user() {
    users.save(new AppUser(UUID.randomUUID(), "dana@petri.local", encoder.encode("secret"), "USER"));
  }

  private void login(MockHttpSession session, String expectedRedirect) throws Exception {
    mockMvc.perform(post("/login").param("username", "dana@petri.local")
            .param("password", "secret").with(csrf()).session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(expectedRedirect));
  }

  @Test
  void aPageOpenedBeforeSigningInIsOpenedAfterTheLogin() throws Exception {
    user();
    MockHttpSession session = new MockHttpSession();

    UUID project = UUID.randomUUID();

    mockMvc.perform(get("/workspace/projects/{id}?view=pullrequests&pr=44", project)
            .accept("text/html").session(session))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/login"));

    login(session, "http://localhost/workspace/projects/" + project + "?view=pullrequests&pr=44");
  }

  @Test
  void backgroundRequestsAreNotRememberedAsTheLandingPage() throws Exception {
    user();
    MockHttpSession session = new MockHttpSession();

    mockMvc.perform(get("/projects/{id}/pull-requests", UUID.randomUUID())
            .param("embedded", "true").accept("text/html").session(session))
        .andExpect(status().is3xxRedirection());

    login(session, "/");
  }
}
