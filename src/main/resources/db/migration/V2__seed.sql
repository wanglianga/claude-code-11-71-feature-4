-- =====================================================================
-- V2  可计算的费用样例数据（确定性生成，可用 SQL 手工重算核对）
--     业务基表：fee_detail / dim_org / dim_diagnosis / dim_drug
--               fee_daily_org（正确预聚合）/ fee_daily_org_stale（含机构合并回溯缺陷的预聚合）
-- =====================================================================

-- 机构维表：ORG002 于 2026-01-01 并入 ORG001（机构合并，用于预聚合缺陷场景）
CREATE TABLE dim_org (
    org_code        TEXT PRIMARY KEY,
    org_name        TEXT NOT NULL,
    region          TEXT NOT NULL,
    org_level       TEXT NOT NULL,
    merged_into     TEXT,
    merge_effective DATE
);

INSERT INTO dim_org (org_code, org_name, region, org_level, merged_into, merge_effective) VALUES
 ('ORG000','省本级中心医院','省本级','三甲', NULL, NULL),
 ('ORG001','杭州市第一人民医院','杭州','三甲', NULL, NULL),
 ('ORG002','杭州市中医院','杭州','三甲', 'ORG001', DATE '2026-01-01'),
 ('ORG003','宁波市第二医院','宁波','三甲', NULL, NULL),
 ('ORG004','温州市人民医院','温州','三甲', NULL, NULL),
 ('ORG005','嘉兴市第一医院','嘉兴','三乙', NULL, NULL),
 ('ORG006','湖州市中心医院','湖州','三乙', NULL, NULL),
 ('ORG007','绍兴市人民医院','绍兴','三甲', NULL, NULL),
 ('ORG008','金华市中心医院','金华','三乙', NULL, NULL),
 ('ORG009','衢州市人民医院','衢州','三乙', NULL, NULL),
 ('ORG010','台州市立医院','台州','三乙', NULL, NULL),
 ('ORG011','丽水市中心医院','丽水','三乙', NULL, NULL);

-- 诊断维表
CREATE TABLE dim_diagnosis (
    diagnosis_code TEXT PRIMARY KEY,
    diag_name      TEXT NOT NULL,
    category       TEXT NOT NULL
);

INSERT INTO dim_diagnosis (diagnosis_code, diag_name, category)
SELECT 'D' || lpad(g::text, 3, '0'),
       '诊断病种-' || lpad(g::text, 3, '0'),
       CASE (g % 3) WHEN 0 THEN '内科' WHEN 1 THEN '外科' ELSE '妇产' END
FROM generate_series(1, 30) g;

-- 药品维表
CREATE TABLE dim_drug (
    drug_code TEXT PRIMARY KEY,
    drug_name TEXT NOT NULL,
    price     NUMERIC(10,2) NOT NULL,
    category  TEXT NOT NULL
);

INSERT INTO dim_drug (drug_code, drug_name, price, category)
SELECT 'G' || lpad(g::text, 3, '0'),
       '药品-' || lpad(g::text, 3, '0'),
       round(((g * 137) % 2000 + 50) / 10.0, 2),
       CASE (g % 4) WHEN 0 THEN '甲类' WHEN 1 THEN '乙类' WHEN 2 THEN '丙类' ELSE '谈判药' END
FROM generate_series(1, 50) g;

-- 费用明细：24000 行，确定性生成
--   机构倾斜：ORG000 约占 55%（i%20<11），其余 11 家均分约 45%（用于参数倾斜→不同计划）
--   结算日期：2025-07-01 .. 2026-06-29（跨 2026-01 政策切换期）
--   金额公式：round(((i*7919)%50000+100)/100.0,2)，可手工重算
CREATE TABLE fee_detail (
    id             BIGSERIAL PRIMARY KEY,
    org_code       TEXT NOT NULL REFERENCES dim_org(org_code),
    diagnosis_code TEXT NOT NULL REFERENCES dim_diagnosis(diagnosis_code),
    drug_code      TEXT NOT NULL REFERENCES dim_drug(drug_code),
    settle_status  TEXT NOT NULL,       -- SETTLED / PENDING / REJECTED
    amount         NUMERIC(14,2) NOT NULL,
    settle_date    DATE NOT NULL,
    patient_id     TEXT NOT NULL
);

INSERT INTO fee_detail (org_code, diagnosis_code, drug_code, settle_status, amount, settle_date, patient_id)
SELECT
    CASE WHEN i % 20 < 11 THEN 'ORG000'
         ELSE 'ORG' || lpad(((i % 11) + 1)::text, 3, '0') END,
    'D' || lpad(((i % 30) + 1)::text, 3, '0'),
    'G' || lpad(((i % 50) + 1)::text, 3, '0'),
    CASE i % 10 WHEN 0 THEN 'REJECTED' WHEN 1 THEN 'PENDING' WHEN 2 THEN 'PENDING' ELSE 'SETTLED' END,
    round(((i * 7919) % 50000 + 100) / 100.0, 2),
    DATE '2025-07-01' + (i % 365),
    'P' || lpad((i % 5000)::text, 6, '0')
FROM generate_series(1, 24000) i;

CREATE INDEX idx_fee_detail_org  ON fee_detail (org_code);
CREATE INDEX idx_fee_detail_date ON fee_detail (settle_date);

-- 正确预聚合（与明细一致）
CREATE TABLE fee_daily_org (
    settle_date DATE NOT NULL,
    month       TEXT NOT NULL,          -- YYYY-MM
    org_code    TEXT NOT NULL,
    cnt         BIGINT NOT NULL,
    amount      NUMERIC(16,2) NOT NULL,
    PRIMARY KEY (settle_date, org_code)
);

INSERT INTO fee_daily_org (settle_date, month, org_code, cnt, amount)
SELECT settle_date, to_char(settle_date, 'YYYY-MM'), org_code, COUNT(*), SUM(amount)
FROM fee_detail
GROUP BY settle_date, org_code;

-- 含缺陷的预聚合：ETL 把机构合并映射（ORG002→ORG001）错误地回溯到全部历史月份，
-- 导致机构级分组与明细不符，但总金额不变 —— 用于“总金额相同而机构分组不同”阻断场景。
CREATE TABLE fee_daily_org_stale (
    settle_date DATE NOT NULL,
    month       TEXT NOT NULL,
    org_code    TEXT NOT NULL,
    cnt         BIGINT NOT NULL,
    amount      NUMERIC(16,2) NOT NULL,
    PRIMARY KEY (settle_date, org_code)
);

INSERT INTO fee_daily_org_stale (settle_date, month, org_code, cnt, amount)
SELECT settle_date, to_char(settle_date, 'YYYY-MM'),
       CASE WHEN org_code = 'ORG002' THEN 'ORG001' ELSE org_code END,
       COUNT(*), SUM(amount)
FROM fee_detail
GROUP BY settle_date, CASE WHEN org_code = 'ORG002' THEN 'ORG001' ELSE org_code END;

-- 测试账号（README 中有对照表）
INSERT INTO app_user (username, token, role, org_code, display_name) VALUES
 ('audit01',  'tok-audit01',  'AUDIT',  'ORG001', '稽核员-杭州一院'),
 ('audit02',  'tok-audit02',  'AUDIT',  'ORG002', '稽核员-杭州中医院'),
 ('drg01',    'tok-drg01',    'DRG',    NULL,     '病种付费专员-省级'),
 ('budget01', 'tok-budget01', 'BUDGET', NULL,     '预算分析员-省级');

-- 权限策略 v1（默认生效）：稽核限定 3 家机构，病种/预算全省
INSERT INTO permission_policy (family, version, rules, status) VALUES
 ('fee-query', 1,
  '[{"role":"AUDIT","allowedOrgs":["ORG001","ORG002","ORG003"]},
    {"role":"DRG","allowedOrgs":"*"},
    {"role":"BUDGET","allowedOrgs":"*"}]'::jsonb,
  'ACTIVE');

ANALYZE fee_detail;
ANALYZE fee_daily_org;
