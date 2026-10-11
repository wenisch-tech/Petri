package tech.wenisch.petri.project;

import java.util.UUID;

public record ProjectImportResult(
    String externalRepositoryId,
    String fullName,
    Status status,
    UUID projectId,
    String message) {
  public enum Status { CREATED, ALREADY_EXISTS, FAILED }
}
