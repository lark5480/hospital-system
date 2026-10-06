-- 号源并发防超卖测试 · 现场还原
--
-- 配套：scripts/slot-test-setup.sql（数据准备）、scripts/slot-race-test.ps1（并发发起）
-- 顺序：先删引用方（预约 → 角色 → 账号 → 患者），再复位号源。
--
-- 用法（在仓库根执行）：
--   Get-Content scripts/slot-test-cleanup.sql -Raw | docker exec -i hospital-postgres psql -U postgres -d hospital -v ON_ERROR_STOP=1 -f -
--
-- 说明：清理目标按「今天 AM、package 1」定位，因此不需要知道 setup 新建号源的具体 id。
--       若你的环境里今天还有 PM 号源或其它 package，请按实际 id 调整下面的条件。

-- 1) 删除本测试产生的预约（两个号源都要清：应用启动时 schema.sql 生成的 + setup 新建的）
DELETE FROM booking.appointment
 WHERE slot_id IN (SELECT id FROM booking.slot
                    WHERE exam_date = CURRENT_DATE AND period = 'AM' AND package_id = 1);

-- 2) 复位号源占用计数（号源本身保留：应用启动时 schema.sql 会按当天生成，属正常数据）
UPDATE booking.slot SET booked = 0
 WHERE exam_date = CURRENT_DATE AND period = 'AM' AND package_id = 1;

-- 3) 删除测试账号与患者
DELETE FROM platform.sys_user_role
 WHERE user_id IN (SELECT id FROM platform.sys_user WHERE phone LIKE '139000000%');
DELETE FROM platform.sys_user  WHERE phone LIKE '139000000%';
DELETE FROM patient.patient    WHERE username LIKE '139000000%';

-- 4) 回读确认（三项都应为 0 / 复位后的容量）
SELECT 'LEFTOVER_PATIENTS' AS k, COUNT(*)::text AS v FROM patient.patient WHERE username LIKE '139000000%';
SELECT 'LEFTOVER_USERS'    AS k, COUNT(*)::text AS v FROM platform.sys_user WHERE phone LIKE '139000000%';
SELECT 'SLOT_STATE'        AS k, (id::text || ' capacity=' || capacity || ' booked=' || booked) AS v
  FROM booking.slot WHERE exam_date = CURRENT_DATE AND period = 'AM' AND package_id = 1;
