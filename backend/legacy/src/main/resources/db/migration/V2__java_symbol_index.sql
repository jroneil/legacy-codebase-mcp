CREATE TABLE java_symbol (
    scan_id uuid NOT NULL REFERENCES scan(id),
    stable_id text NOT NULL,
    kind varchar(16) NOT NULL CHECK (kind IN ('PACKAGE', 'CLASS', 'INTERFACE', 'METHOD', 'FIELD')),
    simple_name text NOT NULL,
    qualified_name text NOT NULL,
    signature text,
    resolution_state varchar(16) NOT NULL CHECK (resolution_state IN ('RESOLVED', 'INFERRED', 'UNRESOLVED')),
    source_path text NOT NULL,
    start_line integer NOT NULL CHECK (start_line > 0),
    start_column integer NOT NULL CHECK (start_column > 0),
    end_line integer NOT NULL CHECK (end_line >= start_line),
    end_column integer NOT NULL CHECK (end_column > 0),
    PRIMARY KEY (scan_id, stable_id),
    FOREIGN KEY (scan_id, source_path) REFERENCES source_file(scan_id, relative_path)
);
CREATE INDEX java_symbol_name_idx ON java_symbol (scan_id, simple_name);

CREATE TABLE java_relationship (
    scan_id uuid NOT NULL REFERENCES scan(id),
    id uuid NOT NULL,
    source_id text NOT NULL,
    target_id text,
    target_description text,
    relationship_type varchar(16) NOT NULL CHECK (relationship_type IN ('CONTAINS','IMPORTS','EXTENDS','IMPLEMENTS','OVERRIDES','CALLS')),
    resolution_state varchar(16) NOT NULL CHECK (resolution_state IN ('RESOLVED','INFERRED','UNRESOLVED')),
    source_path text NOT NULL,
    line integer NOT NULL CHECK (line > 0),
    column_number integer NOT NULL CHECK (column_number > 0),
    evidence_type varchar(64) NOT NULL,
    PRIMARY KEY (scan_id, id),
    FOREIGN KEY (scan_id, source_id) REFERENCES java_symbol(scan_id, stable_id),
    FOREIGN KEY (scan_id, target_id) REFERENCES java_symbol(scan_id, stable_id),
    FOREIGN KEY (scan_id, source_path) REFERENCES source_file(scan_id, relative_path),
    CHECK (target_id IS NOT NULL OR target_description IS NOT NULL),
    CHECK (resolution_state <> 'UNRESOLVED' OR (target_id IS NULL AND target_description IS NOT NULL))
);
CREATE INDEX java_relationship_incoming_idx ON java_relationship (scan_id, target_id);
CREATE INDEX java_relationship_outgoing_idx ON java_relationship (scan_id, source_id);
CREATE TRIGGER java_symbol_guard BEFORE INSERT OR UPDATE OR DELETE ON java_symbol
    FOR EACH ROW EXECUTE FUNCTION guard_inventory_write();
CREATE TRIGGER java_relationship_guard BEFORE INSERT OR UPDATE OR DELETE ON java_relationship
    FOR EACH ROW EXECUTE FUNCTION guard_inventory_write();
