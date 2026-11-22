-- =====================================================================
-- V3  资源调度域：策略目录 / 请求指纹 / 队列任务 / 分区检查点 /
--     订阅与结果载荷 / 合并决策 / 事件流水
-- =====================================================================

-- 资源策略目录：按时段(DAY/NIGHT)、用途、角色保存资源上限，版本化
CREATE TABLE resource_policy (
    id                   BIGSERIAL PRIMARY KEY,
    family               TEXT NOT NULL DEFAULT 'fee-scheduling',
    window_name          TEXT NOT NULL CHECK (window_name IN ('DAY','NIGHT')),
    purpose              TEXT NOT NULL,          -- 具体用途或 '*'
    role_name            TEXT NOT NULL,          -- AUDIT/DRG/BUDGET 或 '*'
    version              INT  NOT NULL,
    max_concurrent_tasks INT  NOT NULL,          -- 队列内同时占用槽位的任务数
    max_total_rows       BIGINT NOT NULL,        -- 单任务扫描行数配额
    result_row_cap       INT  NOT NULL,          -- 结果载荷最大交付行数
    statement_timeout_ms INT  NOT NULL,
    work_mem             TEXT NOT NULL,
    max_parallel_workers INT  NOT NULL,
    status               TEXT NOT NULL DEFAULT 'DRAFT', -- DRAFT/ACTIVE/RETIRED
    created_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (family, window_name, purpose, role_name, version)
);

-- 请求指纹：模板 + 参数 + 快照 + 授权范围 四要素的摘要
-- 参数相同而授权机构范围不同 → 指纹必须不同，不能共享宽权限结果
CREATE TABLE query_fingerprint (
    id                  BIGSERIAL PRIMARY KEY,
    fingerprint         TEXT NOT NULL UNIQUE,
    registration_id     BIGINT NOT NULL REFERENCES query_registration(id),
    snapshot_id         BIGINT NOT NULL REFERENCES data_snapshot(id),
    params              JSONB NOT NULL,
    scope_orgs          JSONB NOT NULL,          -- 权限核对后的机构集合（已排序）
    scope_key           TEXT NOT NULL,           -- 规范化范围键 ORG001,ORG002
    purpose             TEXT NOT NULL,
    role_name           TEXT NOT NULL,
    policy_version      TEXT NOT NULL,           -- 生效权限策略版本（证据）
    components          JSONB NOT NULL,          -- 四要素各自的摘要（可审计）
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 调度任务：同一指纹可以是一条 PER_REQUEST 独立任务，也可以是 MERGE 合并任务
CREATE TABLE schedule_job (
    id                   BIGSERIAL PRIMARY KEY,
    fingerprint_id       BIGINT NOT NULL REFERENCES query_fingerprint(id),
    execution_model      TEXT NOT NULL CHECK (execution_model IN ('PER_REQUEST','MERGE')),
    queue_name           TEXT NOT NULL,          -- DAY:<purpose>:<role>
    window_name          TEXT NOT NULL,          -- DAY / NIGHT
    purpose              TEXT NOT NULL,
    role_name            TEXT NOT NULL,
    resource_policy_id   BIGINT NOT NULL REFERENCES resource_policy(id),
    status               TEXT NOT NULL DEFAULT 'QUEUED',
        -- QUEUED/RUNNING/BUDGET_PAUSED/PARTIAL/COMPLETED/FAILED/CANCELLED
    limit_snapshot       JSONB NOT NULL,         -- 启动时生效的上限（任务中途变更需审计）
    total_partitions     INT NOT NULL,
    completed_partitions INT NOT NULL DEFAULT 0,
    scanned_rows         BIGINT NOT NULL DEFAULT 0,
    result_rows          BIGINT NOT NULL DEFAULT 0,
    truncated            BOOLEAN NOT NULL DEFAULT false,
    queued_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    started_at           TIMESTAMPTZ,
    last_heartbeat_at    TIMESTAMPTZ,
    finished_at          TIMESTAMPTZ,
    paused_reason        TEXT,
    cancel_requested     BOOLEAN NOT NULL DEFAULT false,
    merge_decision_id    BIGINT,
    error                TEXT
);

-- 查询检查点：每个分区一条，保存完成状态与中间摘要（金额/机构分组/行数）
CREATE TABLE query_checkpoint (
    id              BIGSERIAL PRIMARY KEY,
    job_id          BIGINT NOT NULL REFERENCES schedule_job(id),
    partition_key   TEXT NOT NULL,               -- 结算月份 YYYY-MM
    status          TEXT NOT NULL DEFAULT 'PENDING', -- PENDING/DONE/SKIPPED
    scanned_rows    BIGINT NOT NULL DEFAULT 0,
    result_rows     BIGINT NOT NULL DEFAULT 0,
    total_amount    NUMERIC(18,2) NOT NULL DEFAULT 0,
    org_group       JSONB NOT NULL DEFAULT '{}',
    rows            JSONB NOT NULL DEFAULT '[]',
    elapsed_ms      BIGINT,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    UNIQUE (job_id, partition_key)
);

-- 订阅：一个请求者对某任务的结果订阅（合并任务下同一任务有多个订阅）
CREATE TABLE query_subscription (
    id             BIGSERIAL PRIMARY KEY,
    job_id         BIGINT NOT NULL REFERENCES schedule_job(id),
    username       TEXT NOT NULL,
    role_name      TEXT NOT NULL,
    scope_key      TEXT NOT NULL,
    status         TEXT NOT NULL DEFAULT 'WAITING',
        -- WAITING/READY/PARTIAL_READY/DELIVERED/CANCELLED/EXPIRED/POLICY_RETIRED
    priority       INT NOT NULL DEFAULT 100,
    keep_alive_ms  BIGINT NOT NULL,              -- 结果载荷保留时长（生命周期）
    submitted_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    ready_at       TIMESTAMPTZ,
    delivered_at   TIMESTAMPTZ,
    cancelled_at   TIMESTAMPTZ,
    expires_at     TIMESTAMPTZ,
    cancel_reason  TEXT,
    cancel_role    TEXT,                         -- REQUESTER / LAST_CANCEL / GOVERNANCE
    UNIQUE (job_id, username)
);

-- 结果载荷：已完成任务的机构过滤后交付数据（可被回收释放）
CREATE TABLE result_payload (
    id               BIGSERIAL PRIMARY KEY,
    job_id           BIGINT NOT NULL REFERENCES schedule_job(id),
    snapshot_checksum TEXT NOT NULL,
    row_count        BIGINT NOT NULL,
    total_amount     NUMERIC(18,2) NOT NULL,
    org_group        JSONB NOT NULL,
    rows             JSONB NOT NULL,             -- 最多 result_row_cap 行
    bytes            BIGINT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'AVAILABLE', -- AVAILABLE / RECLAIMED
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    reclaimed_at     TIMESTAMPTZ,
    reclaim_reason   TEXT,
    UNIQUE (job_id)
);

-- 合并决策：实际合并边界 + 资源收益，供治理人员查看
CREATE TABLE merge_decision (
    id               BIGSERIAL PRIMARY KEY,
    fingerprint_id   BIGINT NOT NULL REFERENCES query_fingerprint(id),
    chosen_model     TEXT NOT NULL,
    duplicate_rate   NUMERIC(6,4) NOT NULL,
    estimated_result_rows BIGINT NOT NULL,
    permission_overlap NUMERIC(6,4) NOT NULL,
    interactive_budget_ms INT NOT NULL,
    estimated_scan_ms INT NOT NULL,
    benefits         JSONB NOT NULL,
    reasons          JSONB NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 调度事件流水：高频调度动作可审计（预算准入、暂停、恢复、取消、回收）
CREATE TABLE scheduling_event (
    id            BIGSERIAL PRIMARY KEY,
    job_id        BIGINT REFERENCES schedule_job(id),
    subscription_id BIGINT REFERENCES query_subscription(id),
    event_type    TEXT NOT NULL,
    detail        JSONB NOT NULL DEFAULT '{}',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_job_fingerprint ON schedule_job (fingerprint_id);
CREATE INDEX idx_job_queue_status ON schedule_job (queue_name, status);
CREATE INDEX idx_sub_job ON query_subscription (job_id);
CREATE INDEX idx_sub_user ON query_subscription (username, status);
CREATE INDEX idx_event_job ON scheduling_event (job_id);

-- ---------------------------------------------------------------------
-- 种子：资源策略目录（白天受资源限制 / 夜间更大扫描配额）
-- 用途 * 与角色 * 为兜底；具体用途/角色优先
-- ---------------------------------------------------------------------
INSERT INTO resource_policy (family, window_name, purpose, role_name, version,
    max_concurrent_tasks, max_total_rows, result_row_cap, statement_timeout_ms,
    work_mem, max_parallel_workers, status) VALUES
 ('fee-scheduling','DAY','*','*',1,  2,   6000, 200, 4000, '8MB', 1, 'ACTIVE'),
 ('fee-scheduling','DAY','MONTHLY_AUDIT','*',1, 1, 4000, 200, 3000, '8MB', 1, 'ACTIVE'),
 ('fee-scheduling','DAY','*','AUDIT',1, 2, 5000, 200, 3000, '8MB', 1, 'ACTIVE'),
 ('fee-scheduling','NIGHT','*','*',1, 8, 2000000, 5000, 120000, '128MB', 4, 'ACTIVE'),
 ('fee-scheduling','NIGHT','MONTHLY_AUDIT','*',1, 6, 2000000, 5000, 120000, '128MB', 4, 'ACTIVE');
