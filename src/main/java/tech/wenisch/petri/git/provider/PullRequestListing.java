package tech.wenisch.petri.git.provider;

import java.util.List;

/** Open pull requests plus the login of the connected account that listed them. */
public record PullRequestListing(String accountLogin, List<PullRequestRef> pullRequests) {}
