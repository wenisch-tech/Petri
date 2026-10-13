package tech.wenisch.petri.agent.model;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The models of one provider instance as a viewer sees them.
 *
 * @param canRefresh the viewer may re-list models from the endpoint
 * @param canShare the viewer may add custom models for every user of the instance
 */
public record ModelCatalogView(
    UUID instanceId,
    List<AvailableModel> models,
    Instant refreshedAt,
    boolean canRefresh,
    boolean canShare) {}
