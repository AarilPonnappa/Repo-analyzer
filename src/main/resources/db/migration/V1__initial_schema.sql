/-- RepoLens initial schema.
-- Every analysis result hangs off analysis_run, so deleting a run deletes all of its data.

CREATE TABLE repository (
    id              BIGSERIAL PRIMARY KEY,
    owner_repo      VARCHAR(200) NOT NULL UNIQUE,
    is_private      BOOLEAN NOT NULL DEFAULT FALSE,
    default_branch  VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE analysis_run (
    id                  BIGSERIAL PRIMARY KEY,
    repository_id       BIGINT NOT NULL REFERENCES repository(id) ON DELETE CASCADE,
    branch              VARCHAR(200) NOT NULL,
    commit_sha          VARCHAR(40),
    status              VARCHAR(20) NOT NULL,          -- QUEUED, CLONING, PARSING, GRAPH, HISTORY, RISK, SAVING, DONE, FAILED
    error_message       TEXT,
    grade               VARCHAR(2),
    java_file_count     INT,
    class_count         INT,
    method_count        INT,
    endpoint_count      INT,
    cycle_count         INT,
    violation_count     INT,
    hotspot_count       INT,
    parse_error_count   INT,
    commits_scanned     INT,
    file_tree           TEXT,                          -- JSON: folder/file tree
    reading_order       TEXT,                          -- JSON: onboarding reading order
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at         TIMESTAMPTZ
);
CREATE INDEX idx_run_repo ON analysis_run(repository_id, created_at DESC);
CREATE INDEX idx_run_cache ON analysis_run(repository_id, branch, commit_sha, status);

CREATE TABLE class_node (
    id              BIGSERIAL PRIMARY KEY,
    analysis_run_id BIGINT NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
    qualified_name  VARCHAR(500) NOT NULL,             -- "org.example.owner.OwnerController"
    simple_name     VARCHAR(200) NOT NULL,
    package_name    VARCHAR(500),
    kind            VARCHAR(20) NOT NULL,              -- CLASS, INTERFACE, ENUM, RECORD, ANNOTATION
    layer           VARCHAR(20) NOT NULL,              -- CONTROLLER, SERVICE, REPOSITORY, ENTITY, CONFIG, COMPONENT, TEST, OTHER
    file_path       VARCHAR(1000) NOT NULL,
    start_line      INT,
    end_line        INT,
    line_count      INT,
    method_count    INT,
    is_test         BOOLEAN NOT NULL DEFAULT FALSE,
    annotations     TEXT,                              -- comma-separated, e.g. "RestController,RequestMapping"
    UNIQUE (analysis_run_id, qualified_name)
);

CREATE TABLE dependency_edge (
    id              BIGSERIAL PRIMARY KEY,
    analysis_run_id BIGINT NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
    from_class_id   BIGINT NOT NULL REFERENCES class_node(id) ON DELETE CASCADE,
    to_class_id     BIGINT NOT NULL REFERENCES class_node(id) ON DELETE CASCADE,
    kind            VARCHAR(20) NOT NULL               -- EXTENDS, IMPLEMENTS, INJECTS, CALLS, USES
);
CREATE INDEX idx_edge_run ON dependency_edge(analysis_run_id);

CREATE TABLE endpoint (
    id              BIGSERIAL PRIMARY KEY,
    analysis_run_id BIGINT NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
    class_id        BIGINT NOT NULL REFERENCES class_node(id) ON DELETE CASCADE,
    http_method     VARCHAR(10) NOT NULL,              -- GET, POST, ..., ANY
    path            VARCHAR(1000) NOT NULL,            -- "/owners/{ownerId}"
    method_name     VARCHAR(200) NOT NULL,
    method_signature VARCHAR(1000) NOT NULL,
    start_line      INT,
    trace           TEXT                               -- JSON: request-flow tree
);
CREATE INDEX idx_endpoint_run ON endpoint(analysis_run_id);

CREATE TABLE metric (
    id              BIGSERIAL PRIMARY KEY,
    analysis_run_id BIGINT NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
    target_type     VARCHAR(20) NOT NULL,              -- CLASS, METHOD, PROJECT
    target_name     VARCHAR(1000) NOT NULL,
    file_path       VARCHAR(1000),
    name            VARCHAR(50) NOT NULL,              -- "complexity", "lines", "changes", "authors", "hotspot_score", ...
    value           DOUBLE PRECISION NOT NULL
);
CREATE INDEX idx_metric_run_name ON metric(analysis_run_id, name);

CREATE TABLE finding (
    id              BIGSERIAL PRIMARY KEY,
    analysis_run_id BIGINT NOT NULL REFERENCES analysis_run(id) ON DELETE CASCADE,
    type            VARCHAR(30) NOT NULL,              -- CYCLE, LAYER_VIOLATION, HOTSPOT, BUS_FACTOR, TEST_GAP, DEAD_CODE, ...
    severity        VARCHAR(10) NOT NULL,              -- LOW, MEDIUM, HIGH
    message         TEXT NOT NULL,
    file_path       VARCHAR(1000),
    line            INT,
    details         TEXT                               -- JSON with type-specific details
);
CREATE INDEX idx_finding_run ON finding(analysis_run_id, type);
