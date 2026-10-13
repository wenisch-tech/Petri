-- Models offered by a provider instance. DISCOVERED rows (custom = FALSE) mirror what the endpoint
-- lists and are replaced on every refresh. CUSTOM rows are free-text model IDs: owner_id IS NULL
-- shares one with every user of a global instance, otherwise it is private to that user.
CREATE TABLE model_provider_model (
    id UUID PRIMARY KEY,
    instance_id UUID NOT NULL,
    model_id VARCHAR(200) NOT NULL,
    custom BOOLEAN NOT NULL,
    owner_id UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_model_catalog_instance FOREIGN KEY (instance_id)
        REFERENCES model_provider_instance (id) ON DELETE CASCADE,
    CONSTRAINT fk_model_catalog_owner FOREIGN KEY (owner_id) REFERENCES app_user (id)
);

CREATE INDEX idx_model_provider_model_instance ON model_provider_model (instance_id);

ALTER TABLE model_provider_instance ADD COLUMN models_refreshed_at TIMESTAMP WITH TIME ZONE;
