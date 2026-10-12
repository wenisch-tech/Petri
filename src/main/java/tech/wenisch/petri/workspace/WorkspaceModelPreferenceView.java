package tech.wenisch.petri.workspace;

import java.util.UUID;
import tech.wenisch.petri.agent.model.ModelProvider;

public record WorkspaceModelPreferenceView(
    ModelProvider provider, UUID providerInstanceId, String model, boolean persisted) {}
