package tech.wenisch.petri.security.oidc;

import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import tech.wenisch.petri.security.AppUser;

@Service
public class PetriOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {
  private final OidcAccountService accounts;
  private final OidcUserService delegate = new OidcUserService();

  public PetriOidcUserService(OidcAccountService accounts) {
    this.accounts = accounts;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest userRequest) {
    OidcUser oidcUser = delegate.loadUser(userRequest);
    AppUser user = accounts.resolveUser(
        userRequest.getClientRegistration().getRegistrationId(), oidcUser);
    return new PetriOidcUser(oidcUser, user);
  }
}
