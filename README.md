# 电驱动与电池功率协同控制子系统

在进程内建模 **电池 / 逆变器 / 电机**，对多控制入口的功率请求进行统一受理、限流仲裁、
预留记账、**预留租约（有效期/自动回收/续期）** 与故障处置。所有并发入口在同一协调器锁内
串行化，依据**同一份账本与冻结容量快照**做出一致、可解释的判定；所有状态变化落事件日志，
重放后账本（含未到期租约与已发生的回收结果）与故障状态完全可复现。

## 1. 模块结构

```
device/    电池(Battery)、逆变器(Inverter)、电机(Motor)：规格 + 实时遥测 + 物理剩余
model/     PowerRequest/PowerDecision、RequestDecision、RejectReason、SensorReading
ledger/    PowerLedger（预留/撤销/结算/续期/过期回收账本）、Reservation、事件投影
fault/     FaultManager（故障状态机）、FaultType、FaultState
event/     DomainEvent（预留/撤销/结算/续期/过期/故障事件）、EventStore（内存/文件）、编解码
time/      TimeSource 逻辑时钟（生产墙钟；测试可手动推进，过期行为无需真实 sleep）
service/   PowerCoordinationService（协调器，唯一并发入口 + 后台租约回收器）、SystemSnapshot
controller/PowerControlController（REST 外观）
config/    默认物理规格 Bean 与可配置参数
```

## 2. 功率仲裁规则

请求 `PowerRequest(requestId, sourceId, requestedPowerW, durationMs, leaseMs?)` 在协调器锁内按序判定
（`leaseMs` 为可选租约有效期，详见第 3 节）：

1. `requestId` 已存在 → **拒绝** `DUPLICATE_REQUEST_ID`（幂等保护）。
2. 系统状态 = `SHUTDOWN` → **拒绝** `SYSTEM_SHUTDOWN`。
3. 任一传感器读数无效 → **拒绝** `SENSOR_DATA_INVALID`。
4. `requestedPowerW > 逆变器额定功率(80kW)` → **拒绝** `INVERTER_HARDWARE_LIMIT`
   （硬件硬上限，**只拒绝、不裁剪**）。
5. 在冻结容量快照上计算各约束功率：
   - 容量折算：`可调度能量Wh × 3,600,000 / durationMs`
   - 电流折算：`电池当前电压 × 最大放电电流`（350V×300A）
   - 逆变器额定：80kW
   - 故障系数：RUNNING=1，DERATED=0.5，SHUTDOWN=0

   取最小值 × 故障系数 = **绑定上限**，并记录触顶约束（原因可区分）：
   `INSUFFICIENT_REMAINING_CAPACITY` / `BATTERY_DISCHARGE_CURRENT_LIMIT` / `SYSTEM_DERATED`。
6. `授予 = min(请求, 绑定上限)`：
   - 授予 < 最小可执行功率(1000W) → **拒绝**（主因为容量或电流约束）；
   - 授予 = 请求 → `ACCEPTED`；授予 < 请求 → `CLIPPED`，原因集合标明被哪些约束裁剪。
7. 受理后立即在账本**冻结** `授予功率 × 时长` 的能量。由于冻结与判定在同一把锁内完成，
   下一个并发入口看到的可调度能量已扣除本次冻结，**总线上不会形成瞬时超额**。

### 能量账本口径（守恒）

```
物理剩余 remainingWh = 可调度 availableWh + 预留冻结 frozenWh + 安全储备 safetyReserveWh
```

- 预留（reserve）：只在账本冻结能量，**不动物理剩余**；
- 撤销（release）：释放冻结，物理剩余不变（能量本未实耗）；
- 结算（settle）：按**实际耗能**扣减物理剩余，冻结同步解除；实际耗能不得超过预留；
- 过期回收（expire）：租约到期未结算/未撤销时**自动**释放冻结，口径与撤销一致（物理剩余不变）；
- 停机安全储备：进入 SHUTDOWN 冻结一次，恢复时释放一次。

## 3. 预留租约（有效期 / 自动回收 / 续期）

针对“控制入口异常退出，或下发后迟迟不结算，预留长期挂账、容量被占住”的问题，
受理请求时可携带有效期 `leaseMs`（毫秒，可选）：

- `leaseMs` 缺省 / 为 null / ≤0：**不带有效期**，预留一直挂账到撤销或结算
  （与旧版本行为完全一致）。
- `leaseMs > 0`：自**受理时刻**起 `leaseMs` 后为到期时刻（`expiresAtEpochMs`），
  记录在 `ReservationReserved` 事件与账本投影中。

### 3.1 到期自动回收

- 到期判定基于**逻辑时钟** `TimeSource`（生产为墙钟；测试用可手动推进的 `MutableTimeSource`，
  无需真实 sleep 即可稳定复现）。
- 每个业务入口（请求 / 撤销 / 结算 / 续期 / 传感帧 / 故障上报 / 状态查询）在持锁后
  **第一件事**就是统一扫描并回收所有已到期预留；另有一个后台守护线程
  （`power.control.lease-reaper-interval-ms`，默认 1000ms；≤0 关闭）周期性回收。
  因此控制入口崩溃后，只要任一入口活动或后台周期到达，冻结容量都会**无需人工介入**
  回到可调度池。
- 回收追加 `ReservationExpired` 事件，预留进入终止态 `EXPIRED`，不再出现在活动预留中，
  `frozenWh` 立即减少、`availableWh` 立即恢复；**物理剩余不变**（能量从未实耗）。
- 回收**幂等**：只对仍处于 `RESERVED` 的预留生效，终止态永不二次回收，不会重复释放。

### 3.2 续期规则与边界

`POST /api/power/requests/{id}/renew {"sourceId": ..., "extendMs": ...}` 在有效期内延长租约：

- 新到期时刻 = `max(当前时刻, 旧到期时刻) + extendMs`（算术饱和，不会溢出）。
  在旧有效期内续期是在旧到期点上累加；即使当前时刻已越过旧到期点（但尚未被入口回收），
  也只会先回收并返回 `ALREADY_EXPIRED`，**绝不重新激活**。
- 续期**只延长有效期**：不重复冻结能量、不改变已授予功率、不新增任何可调度容量。
  每次成功续期追加一条 `ReservationRenewed` 事件。
- 结果码彼此明确可区分：

  | 情形 | 返回 | 是否重新激活 |
  |------|------|--------------|
  | 活动预留续期成功 | `RENEWED` | —— |
  | 预留不存在 / sourceId 不符 | `NOT_FOUND` | 否 |
  | 已撤销 | `ALREADY_RELEASED` | 否 |
  | 已结算 | `ALREADY_SETTLED` | 否 |
  | 已过期回收 | `ALREADY_EXPIRED` | 否 |

  对过期/已结束预留的撤销与结算同样返回 `ALREADY_EXPIRED` / `ALREADY_RELEASED` /
  `ALREADY_SETTLED`，回收后的预留不能再被结算扣减能量。
- 不带有效期的预留也可调用续期：返回 `RENEWED` 但到期时刻保持 `NEVER_EXPIRES`，
  不产生事件、不动账（语义与旧版本一致）。

### 3.3 与并发 / 故障 / 重放的同一账本口径

- 回收与请求、撤销、结算、续期、故障停机都在协调器**同一把内置锁**内串行，
  且都基于同一冻结快照；因此无论时间上怎么交叠：
  - 一笔预留**恰好以一种方式终结**（结算 / 撤销 / 过期），不重复释放、不重复扣能量；
  - 不会留下预留泄漏（`frozenWh` 与活动预留最终归零）；
  - 累计授予能量绝不超过可用容量，回收释放的容量可立即被新请求重新调度。
- 进入 SHUTDOWN 时只释放仍活动的预留；此前已被 `EXPIRED` 的不会被重复释放。
- `ReservationRenewed` / `ReservationExpired` 进入同一条事件日志：
  重启重放后，物理剩余（仅结算实扣）、冻结量、**仍有效的租约及其（可能被续期过的）
  到期时刻**、**已经发生的回收结果**都与停机前一致；重放后逻辑时钟继续走动，
  剩余租约到期时仍会被正常回收。旧版日志（RESERVED 行无到期字段）按“永不过期”解析，
  向前兼容。

## 4. 故障状态机

```
                 过温(唯一活动故障)
  RUNNING ───────────────────────▶ DERATED
     ▲  ◀─────────────────────────── │
     │ 过温消除(满足迟滞且显式清除)     │
     │  过流 / 欠压 / 传感器无效         │
     └──────────────┐          ┌───────┘
                    ▼          ▼
                   SHUTDOWN（闭锁）
```

| 故障 | 触发阈值（默认） | 目标态 | 恢复条件（迟滞，且必须**显式清除**） |
|------|----------------|--------|-----------------------------------|
| OVER_CURRENT 过流 | 电机电流 > 250A | SHUTDOWN | 电流 ≤ 225A（90%） |
| UNDER_VOLTAGE 欠压 | 电池电压 < 300V | SHUTDOWN | 电压 ≥ 310V（+10V 裕量） |
| SENSOR_INVALID 传感器无效 | 读数 valid=false | SHUTDOWN | 重新收到有效读数 |
| OVER_TEMPERATURE 过温 | 电池>55℃ / 逆变器>65℃ / 电机>70℃ | DERATED | 电池≤45℃ 且 逆变器≤55℃ 且 电机≤60℃ |

关键保证：

- **重复上报幂等**：同一故障在已闭锁时重复触发，只记录输入事件，**不重复迁移状态、
  不重复冻结/扣减可用容量**。
- **恢复前不自愈**：即使传感器读数恢复正常，未满足迟滞条件或未显式清除前，
  绝不自行回到 RUNNING。
- **进入 SHUTDOWN 自动释放全部活动预留**（避免预留泄漏，总线立即零输出），
  并冻结一次停机安全储备。
- DERATED 态仍可服务请求，但功率上限乘以降级系数（默认 0.5）。

故障可由传感器帧自动评估（`POST /api/power/sensors`），也可显式上报
（`POST /api/power/faults/{type}`）/ 显式清除（`DELETE /api/power/faults/{type}`）。

## 5. 并发一致性与可复现性

- `PowerCoordinationService` 用**单一内置锁**串行化：传感刷新、故障上报、请求、撤销、
  结算、续期、过期回收与查询。无论来自多少控制入口，都对同一份 `PowerLedger` 生效。
- 撤销/结算幂等：对不存在或已终止（RELEASED/SETTLED/EXPIRED）的预留返回显式结果码
  （`ALREADY_RELEASED` / `ALREADY_SETTLED` / `ALREADY_EXPIRED` / `NOT_FOUND`），
  **不产生重复事件、不重复释放、不重复扣减**。
- 每次状态变化追加一条 `DomainEvent` 到 `EventStore`：
  - `InMemoryEventStore`：默认，进程内；
  - `FileEventStore`：配置 `power.control.event-log-path` 后使用，append 即刷盘。
- 启动（或重放）时按序号重放全部事件，账本冻结（含租约到期时刻）、物理剩余
  （仅结算事件实扣）、故障闭锁状态与安全储备完全重建，**进程重启后结果可复现**。

## 6. REST 接口

| 方法/路径 | 说明 |
|-----------|------|
| `POST /api/power/requests` | 受理功率请求（body 可带 `leaseMs` 有效期），返回 ACCEPTED/CLIPPED/REJECTED + 原因 |
| `POST /api/power/requests/{id}/release?sourceId=` | 撤销（幂等；过期预留返回 `ALREADY_EXPIRED`） |
| `POST /api/power/requests/{id}/settle` | 按 actualConsumedWh 结算（过期预留返回 `ALREADY_EXPIRED`） |
| `POST /api/power/requests/{id}/renew` | 续期 `{sourceId, extendMs}`，只延长有效期 |
| `POST /api/power/sensors` | 上报传感器帧，自动评估阈值故障（入口先回收到期租约） |
| `POST /api/power/faults/{type}` | 显式故障上报 |
| `DELETE /api/power/faults/{type}` | 显式清除（携带健康读数做迟滞判定） |
| `GET  /api/power/status` | 系统快照：状态、能量账本、活动故障与预留（含 `expiresAtEpochMs`） |

请求示例（带 10 秒有效期）：

```json
{"requestId": "m-1", "sourceId": "pedal",
 "requestedPowerW": 10000, "durationMs": 3600000, "leaseMs": 10000}
```

续期示例：

```json
{"sourceId": "pedal", "extendMs": 5000}
```

## 7. 验证方法

```bash
mvn test          # 全部 32 个测试（单元 + HTTP 集成），默认按随机顺序执行
```

### 7.1 用例之间如何保证互不影响

隔离原则：**任何用例都不得读到其它用例留下的状态，也不得把状态留给后续用例。**

- **进程内单元/并发/租约用例**（`PowerArbitrationTest`、`FaultStateMachineTest`、
  `ConcurrentLedgerTest`、`LeaseManagerTest`）：每个 `@Test` 方法都通过
  `TestSystems.newService(...)` 自己 new 一套全新系统——满电的新电池（40000Wh）、
  新逆变器/电机、空的事件日志、新账本与新协调器。方法间不共享任何可变对象，
  因此天然与执行顺序无关；重复跑同一个方法也不会撞 requestId 或吃到残留容量。
- **HTTP 端到端用例**（`PowerControlApiTest`）：协调器、电池、事件日志和后台租约回收
  线程都是 Spring 单例且带状态，单测共享同一应用上下文。该类标注
  `@DirtiesContext(classMode = AFTER_EACH_TEST_METHOD)`，**每个用例结束后销毁并重建
  整个应用上下文**：下一个用例拿到的是新满电电池、空事件日志、全新协调器，
  旧的后台回收线程随 `@PreDestroy shutdown()` 停掉。端到端用例因此同样彼此独立，
  “带有效期”用例先跑也不会让“完整流程”用例查到偏小的剩余容量。
- **文件事件日志用例**：统一写入 JUnit `@TempDir` 临时目录，用例结束即删除，
  不会在工程目录或用例之间留下事件文件。
- **时间不碰运气**：所有到期/回收/续期相关用例使用可手动拨的 `MutableTimeSource`，
  到期通过 `clock.advance(...)` 确定性触发，不靠真实 sleep 等待。
  唯一涉及后台线程的用例（`backgroundReaper...`）也是“逻辑时钟已到期后轮询等待
  一个很短的回收周期（最长 3s 超时）”，而不是等待真实租约到期。
- **并发但不引入时间竞争**：`concurrentExpiryVsSettleAndRelease...` 中逻辑时钟只在
  主线程推进；线程池只并发竞争结算/撤销/回收这些业务入口。三类操作谁先拿到锁
  不确定，但“每笔恰好终结一次、终局确定（10 结算 / 10 撤销 / 10 过期回收）、
  物理剩余恒为 36000Wh”的结果与线程调度无关，连跑结果完全一致。

### 7.2 如何验证隔离（不同顺序 / 随机顺序 / 重复跑）

测试默认就按随机顺序执行（类之间、类内方法都洗牌），配置见
[`src/test/resources/junit-platform.properties`](src/test/resources/junit-platform.properties)：

```properties
junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$Random
junit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random
junit.jupiter.execution.classorderer.random.seed=42
junit.jupiter.execution.methodorderer.random.seed=42
```

换种子即可换一种洗牌；两个种子可分别设置。建议至少覆盖类/方法两种顺序都变：

```bash
# 仓库默认种子（可复现的一种随机顺序）
mvn test

# 换任意种子打乱类与方法顺序，多跑几个种子
mvn test -Djunit.jupiter.execution.classorderer.random.seed=100 \
         -Djunit.jupiter.execution.methodorderer.random.seed=2024

# 只跑端到端两个用例，并多换几个种子验证“谁先谁后”都一致
mvn test -Dtest=PowerControlApiTest -Djunit.jupiter.execution.methodorderer.random.seed=1
mvn test -Dtest=PowerControlApiTest -Djunit.jupiter.execution.methodorderer.random.seed=2

# 单个用例重复跑（验证不读/不留状态，不撞 requestId）
for i in $(seq 1 5); do
  mvn test -Dtest=ConcurrentLedgerTest#concurrentRequestsNeverOverCommitFrozenCapacity
done

# 回到声明顺序：把上面两行 order.default 改为
#   org.junit.jupiter.api.ClassOrderer.ClassName
#   org.junit.jupiter.api.MethodOrderer.MethodName
```

判据：**无论种子（顺序）怎么换、单个用例重复多少次，32 个用例必须全部通过，
且依赖业务数值的断言（受理/裁剪/拒绝笔数、冻结量、物理剩余等）每次都打印同一个值。**

### 7.3 测试覆盖

- **超额请求与裁剪**（`PowerArbitrationTest`，6 例）：全额受理、逆变器硬上限拒绝、
  容量裁剪、电流裁剪（原因与容量裁剪可区分）、重复 requestId、累计授予不超冻结容量。
- **重复故障上报与恢复**（`FaultStateMachineTest`，6 例）：过流停机且重复上报仅扣一次、
  停机自动释放预留并拒绝请求、过温降级与迟滞恢复、欠压恢复裕量、无效传感器自动停机、
  恢复前不自愈。
- **并发受理/撤销/结算与重放**（`ConcurrentLedgerTest`，4 例）：
  32 线程 × 20 笔并发受理，在真正“受理并冻结”的前提下验证
  **13 笔全额受理 + 1 笔裁剪（3000W→1000W）+ 626 笔因容量不足拒绝**，
  累计授予=账本冻结=40000Wh、可调度 0（不为负）、能量守恒（请求功率 3000W 必须
  ≥ 最小可执行功率 1000W，旧用例用 500W 会全部被拒、断言恒成立）；
  并发重复撤销无双重释放/无泄漏；并发重复结算物理能量仅扣一次；
  基于文件事件日志重放复现容量账本与故障状态。
- **预留租约**（`LeaseManagerTest`，12 例，全部基于可手动推进的 `MutableTimeSource`，
  无真实 sleep）：
  1. 到期自动回收、容量归还、回收幂等；
  2. 任一业务入口在受理前联动回收，释放容量立即可被新请求全额使用；
  3. 续期只延长有效期、不重复冻结/不改授予、在旧到期点累加；
  4. 过期/撤销/结算/不存在后续期分别返回可区分结果码且永不重新激活；
  5. 不带有效期的请求任意推进时间都不回收，撤销/结算维持旧口径（兼容性）；
  6. 回收容量立即重新可调度，累计授予仍不超容量；
  7. 时钟由主线程确定性越过到期点后，24 线程对每笔重复结算/撤销/回收：
     每笔恰好终结一次，10 笔结算 / 10 笔撤销 / 10 笔过期回收，物理剩余恒为 36000Wh；
  8. 文件日志重放：有效租约（含续期后的到期时刻）、已回收结果、剩余容量与停机前一致，
     重启后剩余租约到期仍被回收；
  9. 故障停机只释放活动租约、已回收租约不重复释放，停机后新请求仍被拒；
  10. 后台回收器守护线程在逻辑时间到期后自动回收（无需人工介入）；
  11. 24 线程并发续期同一预留 480 次：仅延长，冻结/授予/可调度量恒定；
  12. 连续续期不新增容量，占满后新请求仍被拒绝。
- **HTTP 端到端**（`PowerControlApiTest`，3 例，上下文逐例重建、时钟可控）：
  完整流程（传感→受理→硬上限拒绝→结算→状态查询）、带租约受理与续期及终止后续期被拒、
  **租约到期经状态查询入口自动回收、回收后结算被拒且不扣能量、归还容量立即被重新受理**。

### 7.4 日志里能看到什么

- 每个用例执行前后由全局扩展 `support/TestProgressLogger` 统一打印
  `[用例开始] / [用例通过] / [用例失败]` 标记，并带该用例所属**业务域**，
  因此日志可直接看出覆盖了哪些业务、每个用例过没过（失败时附带异常类型与信息）。
- 业务流水通过 SLF4J 打印到控制台，包含：
  **输入**（请求/传感/故障/租约字段）、**判定依据**（各约束功率、绑定上限、降级系数）、
  **状态迁移流水**（RUNNING→DERATED→SHUTDOWN、能量冻结/释放/实扣、租约回收/续期、
  事件版本号）。并发受理用例会明确打印
  `受理(ACCEPTED)=.. 裁剪(CLIPPED)=.. 拒绝(REJECTED)=..` 以及
  `累计授予 / 账本冻结 / 物理剩余 / 可调度 / 安全储备` 的对账行。
  可按 `[用例开始]/[请求]/[仲裁]/[故障]/[撤销]/[结算]/[续期]/[租约]/[重放]` 前缀检索。

### 配置落盘重放与后台回收

```properties
power.control.event-log-path=./data/power-events.log
power.control.lease-reaper-interval-ms=1000   # <=0 关闭后台回收线程
```

配置后运行产生的事件逐行持久化；删除其它状态、仅保留日志再启动，系统会重放并恢复到
与停机前一致的物理剩余、冻结账本、**仍有效租约与已回收结果**以及故障状态。
即使关闭后台回收线程，每个业务入口仍会先执行到期扫描，回收不会缺席。

