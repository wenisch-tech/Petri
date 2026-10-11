package tech.wenisch.petri.git.provider;

public interface GitProviderAdapter {
  GitProviderType type();

  ProviderIdentity identity(GitProviderInstance instance, String token, GitConnectionAuthMethod method);

  ProviderRepositoryPage repositories(
      GitProviderInstance instance,
      String token,
      GitConnectionAuthMethod method,
      int page,
      int pageSize,
      String query);

  ProviderRepository repository(
      GitProviderInstance instance,
      String token,
      GitConnectionAuthMethod method,
      String id);

  ProviderToken exchangeCode(
      GitProviderInstance instance,
      String clientSecret,
      String code,
      String redirectUri,
      String codeVerifier);

  ProviderToken refresh(
      GitProviderInstance instance, String clientSecret, String refreshToken);

  default String gitUsername(GitConnectionAuthMethod method, ProviderIdentity identity) {
    return identity.login();
  }
}
