package tech.wenisch.petri.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;
import tech.wenisch.petri.audit.AuditLogService;
import tech.wenisch.petri.security.oidc.UserIdentityRepository;

class UserAdminServiceTest {
  private final AppUserRepository users = mock(AppUserRepository.class);
  private final UserIdentityRepository identities = mock(UserIdentityRepository.class);
  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final UserAdminService service =
      new UserAdminService(users, identities, encoder, mock(AuditLogService.class));

  private AppUser admin(String email) {
    return new AppUser(UUID.randomUUID(), email, "{x}hash", UserAdminService.ADMIN);
  }

  @Test
  void createsLocalUserWithEncodedPasswordAndRejectsWeakInput() {
    when(users.findByEmailIgnoreCase("new@petri.local")).thenReturn(Optional.empty());
    when(encoder.encode("correct-horse-battery")).thenReturn("{enc}pw");

    service.create(" New@Petri.local ", "correct-horse-battery", "USER", "admin@petri.local");

    var saved = org.mockito.ArgumentCaptor.forClass(AppUser.class);
    verify(users).save(saved.capture());
    assertThat(saved.getValue().getEmail()).isEqualTo("new@petri.local");
    assertThat(saved.getValue().getPasswordHash()).isEqualTo("{enc}pw");
    assertThat(saved.getValue().getRole()).isEqualTo("USER");

    assertThatThrownBy(() -> service.create("not-an-email", "correct-horse-battery", "USER", "a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.create("x@petri.local", "short", "USER", "a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.create("x@petri.local", "correct-horse-battery", "ROOT", "a"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lastAdministratorCannotBeDemotedDisabledOrDeleted() {
    AppUser only = admin("admin@petri.local");
    when(users.findById(only.getId())).thenReturn(Optional.of(only));
    when(users.countByRoleAndEnabledTrue("INSTANCE_ADMIN")).thenReturn(1L);

    assertThatThrownBy(() -> service.changeRole(only.getId(), "USER", "other@petri.local"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.setEnabled(only.getId(), false, "other@petri.local"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.delete(only.getId(), "other@petri.local"))
        .isInstanceOf(IllegalStateException.class);
    verify(users, never()).delete(any());
  }

  @Test
  void anAdministratorCanBeDemotedWhenAnotherOneExists() {
    AppUser first = admin("a@petri.local");
    when(users.findById(first.getId())).thenReturn(Optional.of(first));
    when(users.countByRoleAndEnabledTrue("INSTANCE_ADMIN")).thenReturn(2L);

    service.changeRole(first.getId(), "USER", "b@petri.local");

    assertThat(first.getRole()).isEqualTo("USER");
  }

  @Test
  void youCannotDisableOrDeleteYourself() {
    AppUser self = new AppUser(UUID.randomUUID(), "me@petri.local", "{x}h", "USER");
    when(users.findById(self.getId())).thenReturn(Optional.of(self));

    assertThatThrownBy(() -> service.setEnabled(self.getId(), false, "ME@petri.local"))
        .isInstanceOf(IllegalStateException.class);
    assertThatThrownBy(() -> service.delete(self.getId(), "me@petri.local"))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void ssoOnlyAccountsHaveNoLocalPasswordToReset() {
    AppUser sso = AppUser.oidcUser(UUID.randomUUID(), "sso@petri.local", "USER");
    when(users.findById(sso.getId())).thenReturn(Optional.of(sso));

    assertThatThrownBy(() -> service.setPassword(sso.getId(), "correct-horse-battery", "admin"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(service.list("admin").size()).isEqualTo(0);
    when(users.findAllByOrderByEmailAsc()).thenReturn(List.of(sso));
    assertThat(service.list("admin")).singleElement().satisfies(view -> {
      assertThat(view.local()).isFalse();
      assertThat(view.email()).isEqualTo("sso@petri.local");
    });
  }
}
