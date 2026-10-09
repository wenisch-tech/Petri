package tech.wenisch.petri.workspace;

import tech.wenisch.petri.agent.model.ModelProvider;

public record WorkspaceModelPreferenceView(ModelProvider provider, String model, boolean persisted) {}
