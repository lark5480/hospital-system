# 号源并发防超卖:实测验证报告(2026-10-06)

> **性质**:一次**运行期验证**记录,不是代码审查。回答两个问题:
> (1) `BookingService.book()` 的容量守卫在真实并发下会不会超卖;
> (2) 第 223-226 行注释自述的 R-25 竞态窗口是否真实存在。
> **配套脚本**:`scripts/slot-test-setup.sql`、`scripts/slot-race-test.ps1`、`scripts/slot-test-cleanup.sql`。
> **环境**:Windows 11 / 8 核 32G;4 个中间件容器化(PG16 / Redis / MinIO / RabbitMQ)+ `hospital-core` 跑宿主机(:8101)。

## 结论速览

| # | 命题 | 裁定 |
|---|---|---|
| 1 | 不同患者并发抢同一号源,不会超卖 | **成立**(12 并发抢容量 2,恰好 2 成功) |
| 2 | 同一患者并发双写会绕过 R-25 重复校验 | **复现**(2 并发 → 2 条预约,1 人占 2 个号源) |

## 1. 测法要点

### 1.1 为什么必须用多个患者

`book()` 有 R-25 重复预约幂等校验(同一患者 + 同一号源直接拒绝)。**单个患者制造不出并发**——只有不同患者之间才只剩容量守卫这一条防线。用单个患者测出来的"不超卖"是被重复校验顺带挡住的,证明不了容量守卫。

### 1.2 为什么不用登录接口取 token

登录链路有验证码、强制改密、连续失败 5 次锁定 15 分钟三重摩擦,会把测试变量从"并发占号"污染成"登录状态机"。本项目 JWT 自管(HS256,claim = `sub` / `roles` / `authorities` / `iatMs`,见 `JwtTokenService.issue`),故以固定 `APP_JWT_SECRET` 启动 core 后**自行签发 token**。

### 1.3 同步起跑

所有请求忙等到同一时刻再发(`ForEach-Object -Parallel` + 共享 `fireAt`),最大化真实竞争窗口,避免因逐个发请求而把并发退化成串行。

## 2. 结果 A:不同患者并发抢号 → 无超卖

12 个不同患者,同刻抢容量 2 的号源:

| 指标 | 实测 |
|---|---|
| HTTP 200(占号成功) | 2 |
| HTTP 409(号源已满) | 10 |
| `booking.slot.booked` | 2(= capacity) |
| 该号源预约行 | 2 条 / 2 个不同患者 |

**定性**:`SlotMapper.incrementBooked` 的 `UPDATE ... WHERE booked < capacity` 由数据库行锁串行化,返回 0 即满号回滚。**容量这一层既不需要乐观锁版本字段,也不需要分布式锁**——"DB 层原子占号杜绝超卖"在真实并发下成立。

## 3. 结果 B:同一患者并发双写 → 复现 R-25 的已知竞态

`BookingService` 第 223-226 行注释自述:

> 说明:这是应用层校验,并发双写下仍有竞态窗口(两个请求可同时通过校验)。彻底解决需要数据库侧"部分唯一索引"(`booking.appointment(patient_id, slot_id) WHERE status='BOOKED'`)+ 存量重复数据清洗;但 `spring.sql.init.mode=always` 每次启动都跑 `schema.sql`,若存量已有重复行,`CREATE UNIQUE INDEX` 会直接让应用启动失败,故本期不做,列为 P3。

实测:同一患者对同一号源并发 2 个请求 → **两个都返回 200**,DB 终态 `appt_rows=2 / distinct_patients=1`,即**一人占掉 2 个号源**并落 2 条预约。

**定性**:这是**已被代码注释声明的已知边界**,不是新发现。本次的价值在于把两条防线**分开验证**:

| 防线 | 实现层 | 并发下是否成立 |
|---|---|---|
| 容量守卫 | DB(`WHERE booked < capacity`) | 成立 |
| 重复预约守卫(R-25) | 应用层(`selectCount` 通过后 `insert`) | **有窗口** |

按注释所述上"部分唯一索引"前,需先解决 `spring.sql.init.mode=always` 与存量重复数据的冲突(否则脏库启动即失败)。**本次不动代码,只留证据**;R-25 仍按 P3 处理。

## 4. 复现

```powershell
# 1) 中间件 + core(密钥须 ≥32 字节且不命中弱密钥名单)
docker compose up -d
$env:APP_JWT_SECRET = '<your-32-byte-secret>'
java -jar hospital-core/target/hospital-core-0.0.1-SNAPSHOT.jar

# 2) 造数据：新建容量 2 的号源 + 12 个患者,记下输出的 NEW_SLOT id
Get-Content scripts/slot-test-setup.sql -Raw | docker exec -i hospital-postgres psql -U postgres -d hospital -v ON_ERROR_STOP=1 -f -

# 3) 结果 A：12 个不同患者并发抢号
pwsh -File scripts/slot-race-test.ps1 -SlotId <NEW_SLOT id> -Capacity 2 -Patients 12

# 4) 结果 B：同一患者并发双写(需另找一个干净号源)
pwsh -File scripts/slot-race-test.ps1 -SlotId <clean slot id> -Patients 2 -SamePatientSameSlot

# 5) 现场还原
Get-Content scripts/slot-test-cleanup.sql -Raw | docker exec -i hospital-postgres psql -U postgres -d hospital -v ON_ERROR_STOP=1 -f -
```

## 5. 未覆盖与解释边界

- **取消预约的并发释放**(`SlotMapper.releaseBookedBatch`):未测。
- **`-SamePatientSameSlot` 未复现时不能据此认为竞态不存在**:窗口命中依赖两个请求的 `selectCount` 落在彼此的 `insert` 提交之前,属概率事件;一次未命中只说明该次未命中。
- 本报告只覆盖**单实例** `hospital-core`。多实例下容量守卫仍由数据库保证,但 R-25 的窗口同样存在且不因实例数增加而消失。
