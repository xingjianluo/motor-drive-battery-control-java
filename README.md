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
mvn test          # 全部 31 个测试（单元 + HTTP 集成）
```

### 7.1 用例之间如何保证互不影响（测试隔离）

每个用例都必须满足“不管谁先谁后、单独跑、重复跑、打乱顺序一起跑，结果一致”，
本仓库从三个层面保证，而不是靠“碰巧的执行顺序”：

1. **进程内单元/并发用例（`tests` 包中除 `PowerControlApiTest` 外的全部类）**：
   每个用例在方法内部通过 `TestSystems.newService(...)` **现场 new 一套全新系统**
   （全新电池/逆变器/电机物理模型、全新协调器、全新内存事件日志、全新逻辑时钟）。
   用例之间不共享任何静态/实例可变状态；时钟用 `MutableTimeSource` 手动推进，
   到期/续期/回收用例**不依赖真实墙钟与 sleep**，因此在任何机器、任何负载、
   任何执行顺序下结果都确定可复现。

2. **HTTP 端到端用例（`PowerControlApiTest`）**：Spring 的 `@SpringBootTest` 默认在
   同一类内复用同一个 ApplicationContext（单例协调器 + 单例电池 + 单例事件日志），
   这正是“先跑带租约用例、再跑完整流程用例，后者查到的剩余容量少一截”的根因。
   现该类标注：

   ```java
   @DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
   ```

   每个用例执行后**强制销毁并重建整个 ApplicationContext**（协调器、电池物理模型、
   事件日志、后台回收线程全部重来）。此外每个用例入口先做**前置断言**
   （`assertPristineSystem()`）：物理剩余=40000Wh、冻结=0、活动预留=0、账本版本=0。
   一旦读到任何别的用例（或本用例上一次重复执行）留下的状态，用例立即失败，
   把“串味”从隐式问题变成显式红灯。

3. **随机但可复现的执行顺序（`src/test/resources/junit-platform.properties`）**：

   ```properties
   junit.jupiter.testclass.order.default=org.junit.jupiter.api.ClassOrderer$Random
   junit.jupiter.testmethod.order.default=org.junit.jupiter.api.MethodOrderer$Random
   junit.jupiter.execution.order.random.seed=20261005
   junit.jupiter.execution.parallel.enabled=false
   ```

   默认即对测试类、测试方法做**固定种子的随机洗牌**：顺序被打乱（强迫用例独立），
   但种子固定、任何人任何时刻 `mvn test` 拿到的顺序完全相同（可复现）。
   框架层不并行——并发语义由并发用例内部的真实多线程闸口验证，避免框架并行
   额外引入共享态。文件落盘的事件日志用例统一写 JUnit `@TempDir`，跑完即删，
   不会在工作目录留下 `data/*.log` 污染下一次运行。

### 7.2 怎么验证“顺序无关 / 随机顺序 / 重复跑”

```bash
# 1) 默认：固定种子洗牌，重复任意次结果一致
mvn test

# 2) 换不同随机种子（系统属性优先级高于 properties 文件），交叉/打乱顺序反复验证
mvn test -Djunit.jupiter.execution.order.random.seed=1
mvn test -Djunit.jupiter.execution.order.random.seed=42
mvn test -Djunit.jupiter.execution.order.random.seed=31337

# 3) 显式指定两个端到端用例的先后顺序（双向都必须过）
mvn test -Dtest='PowerControlApiTest#leaseRequestAndRenewalWorkOverHttp,PowerControlApiTest#fullFlowOverHttp'
mvn test -Dtest='PowerControlApiTest#fullFlowOverHttp,PowerControlApiTest#leaseRequestAndRenewalWorkOverHttp'

# 4) 单个用例单独跑 / 连续重复跑（不得读到自己上次留下的状态）
mvn test -Dtest='PowerControlApiTest#fullFlowOverHttp'
mvn test -Dtest='PowerControlApiTest#fullFlowOverHttp'

# 5) 只跑并发/租约相关用例
mvn test -Dtest='ConcurrentLedgerTest,LeaseManagerTest'
```

> 说明：时间/到期/回收/续期相关用例全部基于 `MutableTimeSource` 手动推进逻辑时钟，
> 唯一使用真实等待的后台回收用例只等待“回收线程被调度”（最多真实等 3 秒），
> **不等待租约到期**，因此不存在靠长时间真实等待碰运气的情况。

### 7.3 测试覆盖清单

- **超额请求与裁剪**（`PowerArbitrationTest`，6 例）：全额受理、逆变器硬上限拒绝、
  容量裁剪、电流裁剪（原因与容量裁剪可区分）、重复 requestId、累计授予不超冻结容量。
- **重复故障上报与恢复**（`FaultStateMachineTest`，6 例）：过流停机且重复上报仅扣一次、
  停机自动释放预留并拒绝请求、过温降级与迟滞恢复、欠压恢复裕量、无效传感器自动停机、
  恢复前不自愈。
- **并发撤销/结算与重放**（`ConcurrentLedgerTest`，4 例）：
  1. **32 线程 × 20 请求并发受理（真受理、真裁剪、真冻结，非恒真断言）**：
     每笔 3000W（高于 1000W 最小执行门槛；旧用例 500W 时全部被“低于最小门槛”拒绝，
     受理与冻结恒为 0，断言恒成立）。日志逐行给出
     `受理(ACCEPTED)=13 笔 / 裁剪(CLIPPED)=1 笔 / 拒绝(REJECTED)=626 笔（全部容量不足）/
     累计授予=40000Wh / 账本冻结=40000Wh / 物理剩余=40000Wh / 可调度=0Wh`，
     并对这些**确切业务数值**逐一断言：超出容量部分确实被拒、累计授予恰好打满不超额、
     冻结与授予合计一致、可调度不为负（恰好为 0）、能量守恒。笔数与线程交叠顺序无关
     （协调器单锁串行 + 同一份冻结快照）。
  2. 并发重复撤销无双重释放/无泄漏；
  3. 并发重复结算物理能量仅扣一次；
  4. 基于文件事件日志（`@TempDir`）重放复现容量账本与故障状态。
- **预留租约**（`LeaseManagerTest`，12 例，全部基于可手动推进的 `MutableTimeSource`，
  无真实 sleep）：
  1. 到期自动回收、容量归还、回收幂等；
  2. 任一业务入口在受理前联动回收，释放容量立即可被新请求全额使用；
  3. 续期只延长有效期、不重复冻结/不改授予、在旧到期点累加；
  4. 过期/撤销/结算/不存在后续期分别返回可区分结果码且永不重新激活；
  5. 不带有效期的请求任意推进时间都不回收，撤销/结算维持旧口径（兼容性）；
  6. 回收容量立即重新可调度，累计授予仍不超容量；
  7. **并发到期 vs 结算/撤销（确定性覆盖三条终结路径，无双重退款）**：
     分四组共 25 笔（X 短租约结算竞争 10 笔、E 短租约只由回收终结 5 笔、
     ZS 长租约确定性结算 5 笔、ZR 长租约确定性撤销 5 笔），24 线程同一闸口并发。
     回收线程由“X 组首笔结算落地”结构性放行（无 sleep），保证 X 组结算与回收
     **真竞争且双方都必得手**；以账本终态统计 `结算 / 撤销 / 回收` 笔数并断言
     每笔恰好终结一次、物理剩余只随真实结算（每笔 400Wh）扣减、能量守恒、无预留泄漏。
     终结分布随真实交叠变化（日志可见 `结算=6~7 撤销=5 回收=13~14`），但所有不变量恒定；
  8. 文件日志重放：有效租约（含续期后的到期时刻）、已回收结果、剩余容量与停机前一致，
     重启后剩余租约到期仍被回收；
  9. 故障停机只释放活动租约、已回收租约不重复释放，停机后新请求仍被拒；
  10. 后台回收器守护线程在逻辑时间到期后自动回收（仅等线程调度，不等租约到期）；
  11. 24 线程并发续期同一预留 480 次：仅延长，冻结/授予/可调度量恒定；
  12. 连续续期不新增容量，占满后新请求仍被拒绝。
- **HTTP 端到端**（`PowerControlApiTest`，2 例，每例重建上下文 + 出厂态前置断言）：
  原有完整流程 + 带租约请求与续期/终止后续期被拒；二者任意先后、任意重复互不影响。

### 7.4 日志怎么看出“覆盖了什么业务、每个用例过没过”

统一的 JUnit 扩展 `support/TestCaseResultLogger` 为每个用例打印：

```
==== 开始用例 [<用例方法名>()] ====
... 用例自身的业务流水 ...
==== 用例 [<用例方法名>()] 结果: 通过 ====   （失败时为：结果: 失败（<异常>））
```

业务流水通过 SLF4J 打印到控制台，surefire 报告
（`target/surefire-reports/TEST-*.xml`）里也有完整留存，包含：
**输入**（请求/传感/故障/租约字段）、**判定依据**（各约束功率、绑定上限、降级系数）、
**状态迁移流水**（RUNNING→DERATED→SHUTDOWN、能量冻结/释放/实扣、租约回收/续期、
事件版本号）、以及并发用例的确切业务计数（受理/裁剪/拒绝笔数、累计授予量、
账本冻结量、物理剩余、可调度量、各终结方式笔数）。可按
`[请求]/[仲裁]/[故障]/[撤销]/[结算]/[续期]/[租约]/[重放]` 与
`==== 用例` 前缀检索。

### 7.5 配置落盘重放与后台回收

```properties
power.control.event-log-path=./data/power-events.log
power.control.lease-reaper-interval-ms=1000   # <=0 关闭后台回收线程
```

配置后运行产生的事件逐行持久化；删除其它状态、仅保留日志再启动，系统会重放并恢复到
与停机前一致的物理剩余、冻结账本、**仍有效租约与已回收结果**以及故障状态。
即使关闭后台回收线程，每个业务入口仍会先执行到期扫描，回收不会缺席。

