package tech.wenisch.petri.git.provider;

import java.util.List;

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

  /** Open pull requests, most recently updated first. {@code fullName} is {@code owner/name}. */
  default List<PullRequestRef> openPullRequests(
      GitProviderInstance instance,
      String token,
      GitConnectionAuthMethod method,
      String repositoryId,
      String fullName) {
    throw new GitProviderException("This provider does not support pull request automation");
  }

  /** Adds a comment to a pull request and returns a link to it when the provider gives one. */
  default String postPullRequestComment(
      GitProviderInstance instance,
      String token,
      GitConnectionAuthMethod method,
      String repositoryId,
      String fullName,
      long number,
      String webUrl,
      String markdown) {
    throw new GitProviderException("This provider does not support pull request automation");
  }

  /** Adds an emoji reaction (provider name such as {@code eyes}) to a pull request. */
  default void reactToPullRequest(
      GitProviderInstance instance,
      String token,
      GitConnectionAuthMethod method,
      String repositoryId,
      String fullName,
      long number,
      String reaction) {
    throw new GitProviderException("This provider does not support reactions");
  }

  default String gitUsername(GitConnectionAuthMethod method, ProviderIdentity identity) {
    return identity.login();
  }
}
