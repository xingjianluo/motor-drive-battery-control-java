# 电驱动与电池功率协同控制子系统

在进程内建模 **电池 / 逆变器 / 电机**，对多控制入口的功率请求进行统一受理、限流仲裁、
预留记账与故障处置。所有并发入口在同一协调器锁内串行化，依据**同一份账本与冻结容量快照**
做出一致、可解释的判定；所有状态变化落事件日志，重放后账本与故障状态完全可复现。

## 1. 模块结构

```
device/    电池(Battery)、逆变器(Inverter)、电机(Motor)：规格 + 实时遥测 + 物理剩余
model/     PowerRequest/PowerDecision、RequestDecision、RejectReason、SensorReading
ledger/    PowerLedger（预留/撤销/结算账本）、Reservation、事件投影
fault/     FaultManager（故障状态机）、FaultType、FaultState
event/     DomainEvent（预留/撤销/结算/故障事件）、EventStore（内存/文件）、编解码
service/   PowerCoordinationService（协调器，唯一并发入口）、SystemSnapshot
controller/PowerControlController（REST 外观）
config/    默认物理规格 Bean 与可配置参数
```

## 2. 功率仲裁规则

请求 `PowerRequest(requestId, sourceId, requestedPowerW, durationMs)` 在协调器锁内按序判定：

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
- 停机安全储备：进入 SHUTDOWN 冻结一次，恢复时释放一次。

## 3. 故障状态机

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

## 4. 并发一致性与可复现性

- `PowerCoordinationService` 用**单一内置锁**串行化：传感刷新、故障上报、请求、撤销、
  结算、查询。请求、撤销与结算无论来自多少控制入口，都对同一份 `PowerLedger` 生效。
- 撤销/结算幂等：对不存在或已终止（RELEASED/SETTLED）的预留返回显式结果码
  （`ALREADY_RELEASED` / `ALREADY_SETTLED` / `NOT_FOUND`），**不产生重复事件、不重复释放**。
- 每次状态变化追加一条 `DomainEvent` 到 `EventStore`：
  - `InMemoryEventStore`：默认，进程内；
  - `FileEventStore`：配置 `power.control.event-log-path` 后使用，append 即刷盘。
- 启动（或重放）时按序号重放全部事件，账本冻结、物理剩余（仅结算事件实扣）、
  故障闭锁状态与安全储备完全重建，**进程重启后结果可复现**。

## 5. REST 接口

| 方法/路径 | 说明 |
|-----------|------|
| `POST /api/power/requests` | 受理功率请求，返回 ACCEPTED/CLIPPED/REJECTED + 原因 |
| `POST /api/power/requests/{id}/release?sourceId=` | 撤销（幂等） |
| `POST /api/power/requests/{id}/settle` | 按 actualConsumedWh 结算 |
| `POST /api/power/sensors` | 上报传感器帧，自动评估阈值故障 |
| `POST /api/power/faults/{type}` | 显式故障上报 |
| `DELETE /api/power/faults/{type}` | 显式清除（携带健康读数做迟滞判定） |
| `GET  /api/power/status` | 系统快照：状态、能量账本、活动故障与预留 |

## 6. 验证方法

```bash
mvn test          # 全部 18 个测试（单元 + HTTP 集成）
```

测试覆盖与对应类：

- **超额请求与裁剪**（`PowerArbitrationTest`，6 例）：全额受理、逆变器硬上限拒绝、
  容量裁剪、电流裁剪（原因与容量裁剪可区分）、重复 requestId、累计授予不超冻结容量。
- **重复故障上报与恢复**（`FaultStateMachineTest`，6 例）：过流停机且重复上报仅扣一次、
  停机自动释放预留并拒绝请求、过温降级与迟滞恢复、欠压恢复裕量、无效传感器自动停机、
  恢复前不自愈。
- **并发撤销/结算与重放**（`ConcurrentLedgerTest`，4 例）：32 线程并发请求无超额且能量守恒、
  并发重复撤销无双重释放/无泄漏、并发重复结算物理能量仅扣一次、
  基于文件事件日志重放复现容量账本与故障状态。
- **HTTP 端到端**（`PowerControlApiTest`，HTTP 端到端）。

测试日志通过 SLF4J 打印到控制台，包含：
**输入**（请求/传感/故障字段）、**判定依据**（各约束功率、绑定上限、降级系数）、
**状态迁移流水**（RUNNING→DERATED→SHUTDOWN、能量冻结/释放/实扣、事件版本号）。
可在 surefire 报告或控制台按 `[请求]/[仲裁]/[故障]/[撤销]/[结算]/[重放]` 前缀检索。

### 配置落盘重放

```properties
power.control.event-log-path=./data/power-events.log
```

配置后运行产生的事件逐行持久化；删除其它状态、仅保留日志再启动，系统会重放并恢复到
与停机前一致的物理剩余、冻结账本与故障状态。
