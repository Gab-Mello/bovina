CREATE TABLE recall_case (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES organization(id),
    trigger_type varchar(32) NOT NULL,
    trigger_id uuid NOT NULL,
    donor_id uuid,
    semen_batch_id uuid,
    mating_id uuid,
    package_id uuid,
    collection_id uuid,
    cryopreservation_event_id uuid,
    source_document_id uuid,
    reason varchar(500) NOT NULL CHECK (btrim(reason) <> ''),
    opened_at timestamptz NOT NULL,
    opened_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,donor_id) REFERENCES animal(organization_id,id),
    FOREIGN KEY (organization_id,semen_batch_id) REFERENCES semen_batch(organization_id,id),
    FOREIGN KEY (organization_id,mating_id) REFERENCES mating(organization_id,id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,collection_id) REFERENCES oocyte_collection(organization_id,id),
    FOREIGN KEY (organization_id,cryopreservation_event_id) REFERENCES cryopreservation_event(organization_id,id),
    FOREIGN KEY (organization_id,source_document_id) REFERENCES document_reference(organization_id,id),
    FOREIGN KEY (organization_id,opened_by) REFERENCES organization_membership(organization_id,user_account_id),
    CHECK (num_nonnulls(donor_id,semen_batch_id,mating_id,package_id,collection_id,cryopreservation_event_id)=1
      AND coalesce((trigger_type='DONOR' AND trigger_id=donor_id)
        OR (trigger_type='SEMEN_BATCH' AND trigger_id=semen_batch_id)
        OR (trigger_type='MATING' AND trigger_id=mating_id)
        OR (trigger_type='PACKAGE' AND trigger_id=package_id)
        OR (trigger_type='OOCYTE_COLLECTION' AND trigger_id=collection_id)
        OR (trigger_type='CRYOPRESERVATION_EVENT' AND trigger_id=cryopreservation_event_id),false))
);
CREATE INDEX recall_case_tenant_date_idx ON recall_case(organization_id,opened_at DESC,id);
CREATE INDEX collection_donor_lineage_idx ON oocyte_collection(organization_id,donor_id,id);
CREATE INDEX package_item_reverse_lineage_idx ON package_item(organization_id,embryo_id,package_id);
CREATE TABLE recall_hold_execution (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL,
    recall_case_id uuid NOT NULL,
    analysis_cutoff timestamptz NOT NULL,
    executed_at timestamptz NOT NULL,
    executed_by uuid NOT NULL,
    UNIQUE (organization_id,id),
    FOREIGN KEY (organization_id,recall_case_id) REFERENCES recall_case(organization_id,id),
    FOREIGN KEY (organization_id,executed_by) REFERENCES organization_membership(organization_id,user_account_id)
);
CREATE TABLE recall_hold_result (
    organization_id uuid NOT NULL,
    execution_id uuid NOT NULL,
    package_id uuid NOT NULL,
    outcome varchar(40) NOT NULL CHECK (outcome IN ('HOLD_PLACED','ALREADY_HELD','OUT_OF_CUSTODY','MATERIAL_UNAVAILABLE','STALE_PACKAGE_VERSION')),
    hold_id uuid,
    observed_version bigint NOT NULL,
    observed_location_id uuid,
    PRIMARY KEY (organization_id,execution_id,package_id),
    FOREIGN KEY (organization_id,execution_id) REFERENCES recall_hold_execution(organization_id,id),
    FOREIGN KEY (organization_id,package_id) REFERENCES embryo_package(organization_id,id),
    FOREIGN KEY (organization_id,hold_id) REFERENCES inventory_hold(organization_id,id),
    FOREIGN KEY (organization_id,observed_location_id) REFERENCES storage_location(organization_id,id),
    CHECK ((outcome='HOLD_PLACED') = (hold_id IS NOT NULL))
);
GRANT SELECT,INSERT ON recall_case,recall_hold_execution,recall_hold_result TO bovina_runtime;
