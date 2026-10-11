package tech.wenisch.petri.git.provider;

import java.util.List;

public record ProviderRepositoryPage(
    List<ProviderRepository> repositories,
    int page,
    int pageSize,
    boolean hasNext,
    boolean truncated) {}
