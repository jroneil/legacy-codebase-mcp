CREATE TABLE scan (
    id uuid PRIMARY KEY,
    status varchar(16) NOT NULL CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'FAILED')),
    repository_root text NOT NULL,
    analyzer_version varchar(200) NOT NULL,
    git_commit_sha varchar(64),
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    started_at timestamptz,
    completed_at timestamptz,
    failure_message text,
    file_count bigint NOT NULL DEFAULT 0 CHECK (file_count >= 0),
    error_count bigint NOT NULL DEFAULT 0 CHECK (error_count >= 0),
    UNIQUE (id, status),
    CHECK (git_commit_sha IS NULL OR git_commit_sha ~ '^([0-9a-f]{40}|[0-9a-f]{64})$'),
    CHECK ((status = 'PENDING' AND started_at IS NULL AND completed_at IS NULL)
        OR (status = 'RUNNING' AND started_at IS NOT NULL AND completed_at IS NULL)
        OR (status IN ('COMPLETED', 'FAILED') AND started_at IS NOT NULL AND completed_at IS NOT NULL)),
    CHECK ((status = 'FAILED') = (failure_message IS NOT NULL))
);
CREATE INDEX scan_created_idx ON scan (created_at DESC, id);

CREATE TABLE source_file (
    scan_id uuid NOT NULL REFERENCES scan(id),
    relative_path text NOT NULL,
    file_type varchar(16) NOT NULL CHECK (file_type IN ('JAVA', 'GROOVY', 'XML', 'SQL', 'JSP', 'GSP', 'PROPERTIES')),
    content_hash varchar(64) NOT NULL CHECK (content_hash ~ '^[0-9a-f]{64}$'),
    encoding varchar(32) NOT NULL,
    size_bytes bigint NOT NULL CHECK (size_bytes >= 0),
    PRIMARY KEY (scan_id, relative_path),
    CHECK (relative_path <> '' AND relative_path !~ '^/' AND relative_path !~ '(^|/)\.\.(/|$)')
);

CREATE TABLE analysis_error (
    id uuid PRIMARY KEY,
    scan_id uuid NOT NULL REFERENCES scan(id),
    relative_path text,
    stage varchar(32) NOT NULL,
    code varchar(64) NOT NULL,
    message text NOT NULL,
    resolution_state varchar(16) NOT NULL CHECK (resolution_state = 'UNRESOLVED')
);
CREATE INDEX analysis_error_scan_idx ON analysis_error (scan_id, relative_path, code, id);

CREATE TABLE active_scan (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    scan_id uuid,
    status varchar(16) NOT NULL DEFAULT 'COMPLETED' CHECK (status = 'COMPLETED'),
    FOREIGN KEY (scan_id, status) REFERENCES scan(id, status)
);
INSERT INTO active_scan (singleton) VALUES (true);

-- Terminal snapshots cannot be rewritten, even accidentally through future code.
CREATE FUNCTION guard_scan_lifecycle() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'Scan snapshots cannot be deleted';
    END IF;
    IF OLD.status IN ('COMPLETED', 'FAILED') THEN
        RAISE EXCEPTION 'Terminal scan is immutable';
    END IF;
    IF NOT ((OLD.status = 'PENDING' AND NEW.status = 'RUNNING')
        OR (OLD.status = 'RUNNING' AND NEW.status IN ('COMPLETED', 'FAILED'))) THEN
        RAISE EXCEPTION 'Invalid scan transition';
    END IF;
    IF NEW.id <> OLD.id OR NEW.repository_root <> OLD.repository_root
        OR NEW.analyzer_version <> OLD.analyzer_version OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'Scan identity is immutable';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER scan_lifecycle BEFORE UPDATE OR DELETE ON scan
    FOR EACH ROW EXECUTE FUNCTION guard_scan_lifecycle();

CREATE FUNCTION guard_inventory_write() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE scan_status varchar(16);
BEGIN
    IF TG_OP <> 'INSERT' THEN
        RAISE EXCEPTION 'Snapshot evidence is append-only';
    END IF;
    SELECT status INTO scan_status FROM scan WHERE id = NEW.scan_id FOR SHARE;
    IF scan_status IS DISTINCT FROM 'RUNNING' THEN
        RAISE EXCEPTION 'Evidence requires a running scan';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER source_file_guard BEFORE INSERT OR UPDATE OR DELETE ON source_file
    FOR EACH ROW EXECUTE FUNCTION guard_inventory_write();
CREATE TRIGGER analysis_error_guard BEFORE INSERT OR UPDATE OR DELETE ON analysis_error
    FOR EACH ROW EXECUTE FUNCTION guard_inventory_write();
