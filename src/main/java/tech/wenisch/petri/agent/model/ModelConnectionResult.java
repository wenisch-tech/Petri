package tech.wenisch.petri.agent.model;

/** Outcome of probing a model endpoint: whether it works and a short, secret-free reason. */
public record ModelConnectionResult(boolean ok, String message) {}
