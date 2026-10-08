package tech.wenisch.petri.agent.model;

import org.springframework.data.jpa.repository.JpaRepository;

interface ModelProviderCredentialRepository
    extends JpaRepository<ModelProviderCredential, ModelProvider> {}
