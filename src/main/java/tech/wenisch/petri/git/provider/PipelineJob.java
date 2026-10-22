package tech.wenisch.petri.git.provider;

import java.time.Instant;

/**
 * One job (or step group) of a CI/CD run, normalised across providers.
 *
 * @param status SUCCESS, FAILED, RUNNING, PENDING, CANCELLED, SKIPPED or UNKNOWN
 */
public record PipelineJob(
    String id,
    String name,
    String status,
    String webUrl,
    Instant startedAt,
    Instant finishedAt) {}
