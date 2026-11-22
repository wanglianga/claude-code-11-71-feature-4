-- =====================================================================
-- V1  改写验证服务元数据模型
-- =====================================================================

-- 数据快照（冻结只读副本的逻辑代表：snap_* 物理表 + 校验和）
CREATE TABLE data_snapshot (
    id          BIGSERIAL PRIMARY KEY,
    name        TEXT NOT NULL,
    mode        TEXT NOT NULL DEFAULT 'FROZEN_READ_REPLICA',
    status      TEXT NOT NULL DEFAULT 'ACTIVE',      -- ACTIVE / SUPERSEDED
    row_count   BIGINT,
    checksum    TEXT,
    table_stats JSONB,
    note        TEXT,
    frozen_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 查询登记：语句摘要、用途、授权范围、参数形态、数据快照
CREATE TABLE query_registration (
    id           BIGSERIAL PRIMARY KEY,
    sql_digest   TEXT NOT NULL,
    sql_text     TEXT NOT NULL,
    purpose      TEXT NOT NULL,
    auth_scope   JSONB NOT NULL,        -- {"roles":[...],"orgCodes":["*"]}
    param_shape  JSONB NOT NULL,        -- [{"name":"monthFrom","type":"STRING","nullable":false,...}]
    snapshot_id  BIGINT REFERENCES data_snapshot(id),
    status       TEXT NOT NULL DEFAULT 'REGISTERED',
    created_by   TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 计划采集：执行计划、扫描量、锁等待、代价（按参数签名分别采集）
CREATE TABLE plan_capture (
    id               BIGSERIAL PRIMARY KEY,
    registration_id  BIGINT NOT NULL REFERENCES query_registration(id),
    param_signature  TEXT NOT NULL,     -- 参数分级 + 参数值哈希
    param_class      TEXT NOT NULL,     -- NARROW / MEDIUM / WIDE
    params           JSONB NOT NULL,
    plan_hash        TEXT NOT NULL,
    plan_json        JSONB NOT NULL,
    est_rows         BIGINT,
    total_cost       NUMERIC(18,2),
    lock_wait_ms     BIGINT NOT NULL DEFAULT 0,
    captured_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 规则诊断：按 (登记, 参数签名, 计划哈希) 分别建档
CREATE TABLE diagnosis (
    id               BIGSERIAL PRIMARY KEY,
    registration_id  BIGINT NOT NULL REFERENCES query_registration(id),
    param_signature  TEXT NOT NULL,
    param_class      TEXT NOT NULL,
    plan_hash        TEXT NOT NULL,
    findings         JSONB NOT NULL,    -- [{"type":"INDEXABLE_CONDITION","severity":"HIGH",...}]
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (registration_id, param_signature, plan_hash)
);

-- 改写候选
CREATE TABLE rewrite_candidate (
    id                  BIGSERIAL PRIMARY KEY,
    registration_id     BIGINT NOT NULL REFERENCES query_registration(id),
    rewrite_type        TEXT NOT NULL,  -- PUSHDOWN / PAGINATION / PRE_AGGREGATE / JOIN_REORDER
    variant             TEXT NOT NULL DEFAULT 'current',
    rewritten_sql       TEXT NOT NULL,
    compare_mode        TEXT NOT NULL,  -- FULL / PAGE_WINDOW / AGGREGATE
    required_null_params JSONB NOT NULL DEFAULT '[]',
    note                TEXT,
    status              TEXT NOT NULL DEFAULT 'GENERATED', -- GENERATED/VERIFIED/BLOCKED/PUBLISHED/ROLLED_BACK
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 影子执行：固定快照 + 受限资源下运行原语句与候选语句
CREATE TABLE shadow_execution (
    id               BIGSERIAL PRIMARY KEY,
    candidate_id     BIGINT NOT NULL REFERENCES rewrite_candidate(id),
    snapshot_id      BIGINT NOT NULL REFERENCES data_snapshot(id),
    side             TEXT NOT NULL,     -- ORIGINAL / CANDIDATE
    params           JSONB NOT NULL,
    resource_limits  JSONB NOT NULL,
    status           TEXT NOT NULL,     -- SUCCESS / FAILED / TIMEOUT
    row_count        BIGINT,
    total_amount     NUMERIC(18,2),
    pk_list          JSONB,             -- 明细主键集合（演示规模全量保存）
    org_group        JSONB,             -- 机构级分组汇总 {"ORG001": 123.45}
    lock_wait_ms     BIGINT NOT NULL DEFAULT 0,
    elapsed_ms       BIGINT,
    actual_plan      JSONB,             -- EXPLAIN (ANALYZE) 实际计划
    error            TEXT,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 结果裁判
CREATE TABLE judgement (
    id                       BIGSERIAL PRIMARY KEY,
    candidate_id             BIGINT NOT NULL REFERENCES rewrite_candidate(id),
    snapshot_id              BIGINT NOT NULL REFERENCES data_snapshot(id),
    snapshot_checksum        TEXT NOT NULL,
    original_execution_id    BIGINT NOT NULL REFERENCES shadow_execution(id),
    candidate_execution_id   BIGINT NOT NULL REFERENCES shadow_execution(id),
    compare_mode             TEXT NOT NULL,
    pk_set_equal             BOOLEAN,
    amount_equal             BOOLEAN,
    org_group_equal          BOOLEAN,
    total_amount_original    NUMERIC(18,2),
    total_amount_candidate   NUMERIC(18,2),
    org_group_diff           JSONB,     -- [{"org":"ORG001","original":..,"candidate":..}]
    missing_pks              JSONB,
    extra_pks                JSONB,
    missing_reason           JSONB,     -- [{"pk":..,"reason":".."}]
    verdict                  TEXT NOT NULL,  -- PASS / BLOCK
    status                   TEXT NOT NULL DEFAULT 'CURRENT', -- CURRENT / STALE（快照更新后需重验）
    detail                   TEXT,
    created_at               TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 权限策略（版本化；灰度期间收窄会触发路由处置）
CREATE TABLE permission_policy (
    id          BIGSERIAL PRIMARY KEY,
    family      TEXT NOT NULL,
    version     INT  NOT NULL,
    rules       JSONB NOT NULL,         -- [{"role":"AUDIT","allowedOrgs":["ORG001"]|"*"}]
    status      TEXT NOT NULL DEFAULT 'DRAFT',  -- DRAFT / ACTIVE / RETIRED
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (family, version)
);

-- 发布门禁：把通过验证的一版改写绑定到参数范围与权限策略
CREATE TABLE release_gate (
    id            BIGSERIAL PRIMARY KEY,
    candidate_id  BIGINT NOT NULL REFERENCES rewrite_candidate(id),
    judgement_id  BIGINT NOT NULL REFERENCES judgement(id),
    param_range   JSONB NOT NULL,       -- {"monthFrom":"2025-11",...,"orgCode":"ANY|NULL_ONLY|ORG003"}
    policy_id     BIGINT NOT NULL REFERENCES permission_policy(id),
    status        TEXT NOT NULL DEFAULT 'GRAY',  -- GRAY / PUBLISHED / BLOCKED / ROLLED_BACK
    published_at  TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 路由请求账本（在途/待复核/已完成/拒绝）
CREATE TABLE route_request (
    id             BIGSERIAL PRIMARY KEY,
    gate_id        BIGINT NOT NULL REFERENCES release_gate(id),
    username       TEXT NOT NULL,
    policy_version INT  NOT NULL,
    params         JSONB NOT NULL,
    routed_to      TEXT NOT NULL,       -- CANDIDATE / ORIGINAL / REJECTED
    status         TEXT NOT NULL,       -- IN_FLIGHT / COMPLETED / PENDING_REVIEW / REJECTED
    reason         TEXT,
    row_count      BIGINT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at   TIMESTAMPTZ
);

-- 回退账本：路由切换、受影响请求、恢复依据
CREATE TABLE rollback_ledger (
    id                BIGSERIAL PRIMARY KEY,
    gate_id           BIGINT REFERENCES release_gate(id),
    action            TEXT NOT NULL,    -- ROLLBACK / POLICY_NARROW / DATA_REFRESH
    from_status       TEXT,
    to_status         TEXT,
    affected_requests INT NOT NULL DEFAULT 0,
    recovery_basis    JSONB NOT NULL,   -- {"judgementId":..,"snapshotChecksum":"..","reason":".."}
    detail            TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 平台用户（测试账号）
CREATE TABLE app_user (
    username     TEXT PRIMARY KEY,
    token        TEXT NOT NULL UNIQUE,
    role         TEXT NOT NULL,         -- AUDIT / DRG / BUDGET
    org_code     TEXT,                  -- NULL 表示省级（全机构）账号
    display_name TEXT
);

CREATE INDEX idx_plan_capture_reg   ON plan_capture (registration_id);
CREATE INDEX idx_diagnosis_reg      ON diagnosis (registration_id);
CREATE INDEX idx_candidate_reg      ON rewrite_candidate (registration_id);
CREATE INDEX idx_shadow_candidate   ON shadow_execution (candidate_id);
CREATE INDEX idx_judgement_cand     ON judgement (candidate_id);
CREATE INDEX idx_route_gate         ON route_request (gate_id);
CREATE INDEX idx_ledger_gate        ON rollback_ledger (gate_id);
