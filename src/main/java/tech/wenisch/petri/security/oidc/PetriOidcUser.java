package tech.wenisch.petri.security.oidc;

import java.io.Serial;
import java.io.Serializable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.OidcUserInfo;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import tech.wenisch.petri.security.AppUser;

public final class PetriOidcUser implements OidcUser, Serializable {
  @Serial private static final long serialVersionUID = 1L;

  private final OidcUser delegate;
  private final String email;
  private final Set<GrantedAuthority> authorities;

  public PetriOidcUser(OidcUser delegate, AppUser user) {
    this.delegate = delegate;
    this.email = user.getEmail();
    this.authorities = new LinkedHashSet<>(delegate.getAuthorities());
    this.authorities.add(new SimpleGrantedAuthority("ROLE_" + user.getRole()));
  }

  @Override
  public Map<String, Object> getAttributes() { return delegate.getAttributes(); }

  @Override
  public Collection<? extends GrantedAuthority> getAuthorities() { return Set.copyOf(authorities); }

  @Override
  public Map<String, Object> getClaims() { return delegate.getClaims(); }

  @Override
  public OidcUserInfo getUserInfo() { return delegate.getUserInfo(); }

  @Override
  public OidcIdToken getIdToken() { return delegate.getIdToken(); }

  @Override
  public String getName() { return email; }
}
