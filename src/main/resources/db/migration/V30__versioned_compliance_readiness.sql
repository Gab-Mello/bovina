CREATE TABLE compliance_rule_definition (
    rule_key varchar(80) NOT NULL CHECK (rule_key ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    version_label varchar(80) NOT NULL CHECK (btrim(version_label) <> ''),
    authority varchar(80) NOT NULL CHECK (btrim(authority) <> ''),
    source_reference varchar(160) NOT NULL CHECK (btrim(source_reference) <> ''),
    source_url varchar(500) NOT NULL CHECK (btrim(source_url) <> ''),
    article varchar(80) NOT NULL CHECK (btrim(article) <> ''),
    effective_from date NOT NULL,
    effective_to date,
    applies_to varchar(40) NOT NULL CHECK (applies_to ~ '^[A-Z][A-Z0-9_]{0,39}$'),
    severity varchar(24) NOT NULL CHECK (severity IN ('INFORMATION','WARNING','BLOCKING')),
    validator_key varchar(80) NOT NULL CHECK (validator_key ~ '^[A-Z][A-Z0-9_]{0,79}$'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE','RETIRED')),
    PRIMARY KEY (rule_key,version_label),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);
CREATE INDEX compliance_rule_effective_idx
    ON compliance_rule_definition(applies_to,effective_from DESC,rule_key)
    WHERE status='ACTIVE';
GRANT SELECT ON compliance_rule_definition TO bovina_runtime;

-- Verified Art. 28 IV-V-VI source; this narrow rule only checks recorded field presence.
-- It does not certify overall CPIVE compliance or the still-unverified SIPEAGRO template.
INSERT INTO compliance_rule_definition(
    rule_key,version_label,authority,source_reference,source_url,article,
    effective_from,applies_to,severity,validator_key,status)
VALUES (
    'CPIVE_A28_SEMEN_FERTILIZATION_FIELDS','1204_2024','MAPA',
    'PORTARIA_SDA_MAPA_1204_2024',
    'https://www.gov.br/agricultura/pt-br/assuntos/insumos-agropecuarios/insumos-pecuarios/material-genetico/copy_of_PortariaSDA_MAPAn1.204de28denovembrode2024PortariaSDA_MAPAn1.204de28denovembrode2024DOUImprensaNacional.pdf',
    'ART_28_IV_V_VI',DATE '2024-12-02','MATING','INFORMATION',
    'RECORDED_SEMEN_FERTILIZATION_FIELDS','ACTIVE');
