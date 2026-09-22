# 电驱动与电池功率协同控制子系统

在 Spring Boot 进程内模拟电池、逆变器与电机模型，提供统一的功率仲裁、限流裁剪、
故障处置状态机与事件溯源账本。多个并发控制入口（REST、扭矩控制循环等）的功率
请求、撤销与结算都在同一把锁、同一份账本上串行生效，结果一致、可解释、可重放。

## 1. 总体结构

```
api/      PowerControlController     REST 并发入口（请求/撤销/结算/故障/快照）
          PowerControlConfiguration  模型与协调器装配，启动时重放事件日志
model/    BatteryModel               SOC、放电电流折算功率、剩余能量
          InverterModel              逆变器最大功率约束
          MotorModel                 转速/温度进程内模型
          * 枚举与记录                OperationalState / FaultType /
                                     RejectionReason / LimitBound / PowerRequest 等
service/  PowerArbiter               纯函数仲裁：拒绝或裁剪，给出可区分原因与边界
          PowerLedger                单一功率账本：预留/撤销/结算，幂等
          FaultManager               故障状态机：去重、迟滞恢复、组合状态
          PowerCoordinator           统一锁：仲裁+账本+事件+模型原子生效
event/    Event(sealed)              6 类可持久化事件
          FileEventLog               JSON Lines 文件日志；InMemoryEventLog（测试）
```

## 2. 功率仲裁规则

默认模型参数：电池 350V / 60Ah / 最大放电电流 300A，逆变器 80kW，初始 SOC=0.9。

**物理功率上限**

| 约束 | 计算方式 | 默认值 |
| --- | --- | --- |
| 电池放电电流 | `U_nom × I_max` | 105kW |
| 电池 SOC | SOC ≥ 0.20 时等于电流功率；SOC < 0.20 线性收敛到 0 | 105kW（高电量段） |
| 逆变器 | 额定最大输出 | 80kW |
| 降级系数 | 由活动故障决定（过温 0.5，传感器无效 0.25，停机 0） | 1.0 |

**裁定顺序（短路，拒绝原因可区分）**

1. 请求非正/非有限值 → 拒绝 `INVALID_REQUEST`
2. 系统 `STOPPED` → 拒绝 `SYSTEM_STOPPED`
3. SOC = 0，剩余容量耗尽 → 拒绝 `BATTERY_DEPLETED`
4. 冻结账本剩余预算为 0 → 拒绝 `BATTERY_BUDGET_EXHAUSTED`
5. 否则受理，授予
   `min(请求功率, 账本剩余预算, min(SOC功率, 电流功率, 逆变器功率) × 降级系数)`；
   授予 < 请求即为**裁剪**，并标注起约束作用的边界
   （`BATTERY_SOC_POWER / BATTERY_CURRENT_POWER / INVERTER_POWER / DERATE_FACTOR / LEDGER_BUDGET`）。

**与冻结剩余容量一致，无瞬时超额**

- 协调器初始化时把总预算冻结为 `min(SOC功率, 逆变器功率)`；
- 预留一旦授予，其功率在结算/撤销前持续占用预算，
  账本恒满足 `总预算 = 已预留 + 剩余`；
- 仲裁授予值不超过同一把锁内读到的账本剩余，因此即使总线上并发涌入请求，
  已预留合计也不会超过冻结预算；
- 结算按实际耗能扣减电池能量后，按结算后的物理上限**重新冻结**总预算
  （若新上限低于已预留量则以已预留量兜底，不追溯裁剪既有预留，但此后不再受理新预留）；
  撤销只释放占用、不扣能量。

## 3. 故障状态机

状态：`RUNNING → DERATED → STOPPED`，组合状态取活动故障中的最严等级，
降级系数取活动故障系数的最小值。

| 故障类型 | 触发条件 | 处置状态 | 降级系数 | 恢复条件（迟滞） |
| --- | --- | --- | --- | --- |
| 过流 OVER_CURRENT | 电流 ≥ 300A | STOPPED | 0 | 电流 ≤ 270A |
| 欠压 UNDER_VOLTAGE | 电压 ≤ 300V | STOPPED | 0 | 电压 ≥ 330V |
| 过温 OVER_TEMPERATURE | 温度 ≥ 90℃ | DERATED | 0.5 | 温度 ≤ 80℃ |
| 传感器无效 SENSOR_INVALID | 读数 NaN/Infinity | DERATED | 0.25 | 上报有效有限读数 |

关键语义：

- **按 `faultKey` 去重**：同一物理故障重复上报标记 `duplicate=true`，
  不切换状态、不追加事件、不触碰账本（不重复扣减可用容量）；
- **不自行复位**：必须显式上报恢复且测量值满足迟滞恢复条件；只清除部分故障时，
  剩余活动故障仍维持更严状态（例如过温+欠压同时存在，只清过温仍为 STOPPED）；
- 未达触发阈值的上报不会建立故障；
- STOPPED 时不受理新功率请求，电机执行空载滑行。

## 4. 并发模型与事件溯源

- `PowerCoordinator` 的请求/撤销/结算/故障/快照全部为 `synchronized` 串行临界区，
  将"仲裁 → 账本变更 → 事件追加 → 电机/电池模型推进"作为原子步骤；
- 预留、撤销、结算按 **requestId 幂等**：重复 requestId 不二次占用，
  重复撤销/结算返回空结果、不产生重复释放或重复扣减，杜绝预留泄漏；
- 每个生效动作先追加事件再改内存态。事件类型：
  `PowerReserved / PowerReleased / PowerSettled / FaultRaised / FaultCleared / StateChanged`；
- `FileEventLog` 以 JSON Lines 落盘（`power.eventlog.path` 配置路径）；
  启动时 `replay()` 按序重放重建账本、SOC 与活动故障（状态由故障重算），
  进程重启或用新实例重放同一日志可复现同一结果，重放本身幂等。

## 5. 验证方法

```bash
mvn test
```

测试覆盖（19 个用例，日志打印输入、判定依据与状态迁移流水，见 surefire 控制台输出）：

| 测试类 | 覆盖点 |
| --- | --- |
| `PowerArbitrationTest` | 超额请求裁剪（逆变器/SOC/电流边界）、预算冻结占满拒绝、四种拒绝原因区分、连续授予不超额、重复 requestId |
| `FaultStateMachineTest` | 过流停机、同 faultKey 三次重复上报不切换状态/不追加事件/不动账本、过温降级 0.5 与迟滞恢复、多故障部分清除、传感器无效、未达阈值不触发 |
| `ConcurrentLedgerTest` | 16 线程并发请求不超额且账本恒等式成立；撤销/结算竞争与重复操作幂等、无泄漏；8 线程并发结算同一 requestId 能量只扣一次 |
| `EventReplayTest` | 内存日志与临时文件日志重放后，状态、活动故障、SOC、冻结预算、活动预留完全复现；重放后继续工作再重放仍一致 |
| `PowerControlApiIT` | REST 端到端：裁剪→预算拒绝→故障停机→停机拒绝→撤销幂等→一致快照 |

手工验证可启动应用后调用：

```bash
curl -X POST localhost:8080/api/power/requests -H 'Content-Type: application/json' \
  -d '{"requestId":"r1","source":"rest","requestedWatts":120000,"requestedAtNanos":1}'
curl localhost:8080/api/power/snapshot
curl -X POST localhost:8080/api/power/requests/r1/release
curl -X POST localhost:8080/api/power/faults -H 'Content-Type: application/json' \
  -d '{"reportId":"f1","faultKey":"oc-1","type":"OVER_CURRENT","measuredValue":320,"reportedAtNanos":2}'
curl -X POST localhost:8080/api/power/faults/oc-1/clear -H 'Content-Type: application/json' \
  -d '{"measuredValue":260}'
```
