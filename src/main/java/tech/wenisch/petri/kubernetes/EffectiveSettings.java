package tech.wenisch.petri.kubernetes;

/** Everything needed to run one project's workspace pod. */
public record EffectiveSettings(ClusterConfig cluster, PodSettings pod) {}
