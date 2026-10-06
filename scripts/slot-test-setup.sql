-- 号源并发防超卖测试 · 数据准备
--
-- 配套：scripts/slot-race-test.ps1（并发发起）、scripts/slot-test-cleanup.sql（现场还原）
-- 结论：docs/review/2026-10-06-concurrency-verification.md
--
-- 用法（在仓库根执行）：
--   Get-Content scripts/slot-test-setup.sql -Raw | docker exec -i hospital-postgres psql -U postgres -d hospital -v ON_ERROR_STOP=1 -f -
-- 执行后记下输出的 NEW_SLOT id，作为 slot-race-test.ps1 的 -SlotId。

-- 1) 新号源：今天 AM，容量 2，已约 0
--    注意 book() 会拒绝 exam_date < today 的号源（isBefore(today)），今天可用
INSERT INTO booking.slot (package_id, exam_date, period, capacity, booked)
VALUES (1, CURRENT_DATE, 'AM', 2, 0);

-- 2) 12 个压测患者（username = 手机号，与 JWT sub 的约定一致：JWT sub=phone → /me 按 username 反查患者）
INSERT INTO patient.patient (name, gender, birthday, phone, username)
SELECT '压测患者' || lpad(i::text, 2, '0'), 'M', DATE '1990-01-01',
       '139000000' || lpad(i::text, 2, '0'), '139000000' || lpad(i::text, 2, '0')
FROM generate_series(1, 12) AS i;

-- 3) 对应登录账号。口令哈希沿用种子账号（13800000000 所属行）；本测试用自签 JWT，不依赖登录
INSERT INTO platform.sys_user (phone, password, name, status)
SELECT p.phone, (SELECT password FROM platform.sys_user WHERE phone = '13700000000'), p.name, 'ACTIVE'
FROM patient.patient p WHERE p.username LIKE '139000000%';

-- 4) 绑定 PATIENT 角色（book 端点要求 patient:booking 权限，角色经 role_authority 映射到该权限）
INSERT INTO platform.sys_user_role (user_id, role_code)
SELECT u.id, 'PATIENT' FROM platform.sys_user u WHERE u.phone LIKE '139000000%';

-- 5) 回读：NEW_SLOT 的 id 即后续 -SlotId
SELECT 'NEW_SLOT' AS k, id::text AS v
FROM booking.slot WHERE exam_date = CURRENT_DATE AND period = 'AM' AND package_id = 1;

SELECT 'PATIENT' AS k, id::text || '|' || username AS v
FROM patient.patient WHERE username LIKE '139000000%' ORDER BY id;
