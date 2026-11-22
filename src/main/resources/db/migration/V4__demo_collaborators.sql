-- =====================================================================
-- V4  调度演示协作账号：同角色多位申请人，用于“相同指纹合并 + 并发取消”
--     与“权限不同的同 SQL”样例。省级 DRG 账号，授权范围一致 → 可共享合并任务。
-- =====================================================================
INSERT INTO app_user (username, token, role, org_code, display_name) VALUES
 ('drg02', 'tok-drg02', 'DRG', NULL, '病种付费专员-省级2'),
 ('drg03', 'tok-drg03', 'DRG', NULL, '病种付费专员-省级3')
ON CONFLICT (username) DO NOTHING;
