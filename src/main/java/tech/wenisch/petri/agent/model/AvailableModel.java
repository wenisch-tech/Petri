package tech.wenisch.petri.agent.model;

import java.util.UUID;

/**
 * A model a viewer can pick on a provider instance.
 *
 * @param id catalogue row, or null for the instance's configured default
 * @param custom typed in by a person rather than reported by the endpoint
 * @param personal custom model that only its owner sees
 * @param removable the viewer may delete it
 */
public record AvailableModel(
    UUID id, String modelId, boolean custom, boolean personal, boolean removable) {}
