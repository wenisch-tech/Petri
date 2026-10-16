package tech.wenisch.petri.mcp;

import org.springframework.data.jpa.repository.JpaRepository;

interface McpUserCredentialRepository extends JpaRepository<McpUserCredential, McpUserCredential.Key> {}
