-- =====================================================================
-- V5  指纹增加 SQL 模板摘要列：同一模板可能被多次登记（行版本不同），
--     识别“同模板同参数同快照”的兄弟请求必须按模板摘要匹配，而非登记行 ID。
-- =====================================================================
ALTER TABLE query_fingerprint ADD COLUMN template_digest TEXT;
UPDATE query_fingerprint f SET template_digest = r.sql_digest
  FROM query_registration r WHERE r.id = f.registration_id;
ALTER TABLE query_fingerprint ALTER COLUMN template_digest SET NOT NULL;
CREATE INDEX idx_fp_template_params ON query_fingerprint (template_digest, snapshot_id);
