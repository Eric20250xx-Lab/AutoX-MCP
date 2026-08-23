# Guardian 与 MCP 可靠性边界

本文记录 `7.2.1-eric.15` 的当前能力、2026-08-23 实机故障结论和后续取舍。它描述的是
恢复能力，不构成“接收器永远在线”的保证。

## 结论

`.15` 已能处理脚本退出、局部卡死、MCP HTTP 引擎失效、重启/解锁后的恢复，以及关键时刻的
定时唤醒；但它仍不能从应用内部恢复“整个 AutoX 包（同一 UID）被系统挂起/冻结”的情况。
屏幕亮着只表示显示子系统处于交互状态，不表示主进程、脚本引擎、网络请求或 Relay 心跳仍在运行。

若目标是无人值守，下一项真正增加可靠性的工作应放在 AutoX 之外：使用独立供电的 USB ADB
监督器（树莓派、迷你主机或旧电脑）检查设备和进程，必要时唤醒设备并重新打开 AutoX。继续在
同一 UID 内叠加更多 Guardian 线程、Service 或短周期触摸任务，无法消除整包冻结这一共同故障点。

## 进程与职责

| 位置 | 当前职责 | 能处理的故障 | 不能证明或恢复的情况 |
| --- | --- | --- | --- |
| AutoX 主进程 | `ScriptGuardianService`、Guardian 启动的守护脚本/接收器、进程探针、MCP 前台服务与 Ktor HTTP 引擎、设置和诊断 | 守护脚本退出；MCP HTTP 引擎局部失效 | 主进程自身被挂起；整个包被冻结 |
| `:script` 脚本进程 | 运行其他 AutoX 脚本；Guardian 会查询并停止同一路径的远端重复实例 | 避免主、脚本进程同时处理同一接收任务 | 它不是 Guardian 默认启动接收器的独立监督器 |
| `:guardian` 独立前台进程 | 用 Messenger 探测主进程和 Guardian 心跳状态；请求精确闹钟恢复 | 主进程失联但该进程仍获得调度 | 系统冻结整个 UID、force-stop 或设备网络整体失效 |
| 外部 Relay | 接收手机心跳、下发命令、判定网页在线状态 | 发现端到端心跳中断 | 单靠最后一次心跳无法判断手机中断后的内部状态 |

MCP 与业务接收器是两条不同链路。`/healthz` 只检查主进程中的 Ktor 引擎；它不执行
Accessibility 操作，也不能证明守护脚本、手机到 Relay 的网络链路或云端命令接收正常。

## `.15` 已实现的恢复能力

- Guardian 以前台 Service 运行，并通过 `START_STICKY`、启动/首次解锁、应用更新和时间变化等
  入口恢复配置；脚本异常退出后按 5、10、20、40、60 秒退避重启，健康运行 2 分钟后重置退避。
- 守护脚本启动后 30 秒未出现首个本地心跳，或空闲状态 45 秒未续报，会被视为失活并重建。
  `BUSY` 超过 5 分钟只报警，不在业务执行中强制重启。
- `:guardian` 进程插电时每 20 秒、未插电时每 60 秒探测主进程；连续失败后通过
  `setExactAndAllowWhileIdle` 请求主进程验证和恢复，而不是直接杀进程。
- 每日预热按 Asia/Shanghai 时间使用 Exact Alarm；错过时间可做补偿性恢复。它只唤醒并核对
  Guardian，不执行考勤等业务动作。
- Guardian 使用 CPU wake lock；用户选择“插电时保持屏幕常亮”后，检测到熄屏会最多尝试两次
  点亮。无密码锁时可借助 Accessibility 关闭非安全锁屏；安全锁屏不会被绕过。
- MCP 暴露 `/healthz`，每 20 秒做一次本机探测；连续两次失败时只重启 Ktor 引擎，10 分钟内最多
  重启 3 次，避免无界重启。
- 运行诊断区分主进程、Guardian、watchdog、MCP、屏幕/充电和恢复原因，供手机 Agent 上报。

这些机制均不保证从 Android 的 **force-stop** 恢复。force-stop 后必须由用户或包外监督器重新
启动应用。Exact Alarm 是关键时段的恢复入口，不是持续在线租约；应用被 force-stop 后，既有闹钟
也不会成为可靠的自启动手段。

## 2026-08-23 realme 故障

### 已观察事实

- 手机 Agent `1.6.0` 最后一次成功心跳为 2026-08-23 15:31:38（Asia/Shanghai），监控在
  15:33:32 判定离线，之后数小时没有自行恢复。
- 最后一份收到的运行快照显示：进程 epoch 未变化、Guardian 为 `IDLE`、watchdog 为
  `HEALTHY` 且 misses 为 0、MCP 为 `HEALTHY`（generation 1、failures 0）、蜂窝网络已验证、
  Relay 连续失败为 0、Accessibility 可用。
- 同一时段 Xiaomi 仍能续报，说明 Worker/D1 与网页的公共云端链路没有整体中断。
- Hamibot 最初仍可用，随后也显示离线。

### 证据边界与推断

上述绿色快照只证明 **最后一次成功上传之前** 的状态；沉默开始后手机无法继续上传诊断，因此
不能把最后快照当作故障发生后的状态。现有证据可以排除云端全局故障，但还不能在以下两种原因间
作出确定区分：

1. Android/realme 将 AutoX 的整个 UID 或多个后台应用挂起；
2. 手机自身的蜂窝数据路径在该时刻失效，而屏幕仍保持点亮。

Hamibot 后来也离线，使“系统级后台限制或手机网络共同故障”更可信，但它不是单独的定论。要取得
故障后的直接证据，诊断者必须位于 AutoX 包外，或设备必须恢复网络后保留可读取的系统日志。

## 上游评估

- MCP 作者仓库 [`eness-1/AutoX`](https://github.com/eness-1/AutoX) 在
  [`v7.2.1-mcp-debug...setup-v7`](https://github.com/eness-1/AutoX/compare/v7.2.1-mcp-debug...setup-v7)
  之后的改动主要是移除调试依赖和合入 `cancel_job` 修复，没有新增后台保活或整包冻结恢复。
- 官方 [`aiselp/AutoX v7.2.3`](https://github.com/aiselp/AutoX/releases/tag/v7.2.3) 带来跨进程
  Accessibility 代理、脚本 Service 生命周期修复、后台启动时的临时前台 Service，以及内存清理等
  邻近改进；这些内容有助于减少局部故障，但不包含持久 Guardian、Relay 重连、系统 Push、包外
  监督器或 Android freezer 恢复。
- 官方仍有与目标机现象相邻的未解决报告：
  [OnePlus 息屏后定时任务无法启动（#211）](https://github.com/aiselp/AutoX/issues/211)、
  [关闭电池限制并使用前台服务后脚本仍暂停（#130）](https://github.com/aiselp/AutoX/issues/130)、
  [v7.2.3 在 Xiaomi 上的 Accessibility 回归（#199）](https://github.com/aiselp/AutoX/issues/199)，
  以及[定时任务偶发按 UTC 运行（#195）](https://github.com/aiselp/AutoX/issues/195)。

因此不应为了后台在线问题整体合并 v7.2.3。后续只有在本仓库出现对应证据时，才定向回移脚本退出
内存清理、跨进程 Accessibility AIDL 或便于外部监督器启动脚本的入口，并分别做真机回归。

## 后续优先级

1. **包外 USB ADB 监督器**：以独立设备检查 `adb` 连接、AutoX 进程和 Relay 心跳；确认失败后
   唤醒设备、重开 AutoX 并重新验活。这是当前唯一能避开同 UID 共同故障点的低成本方向。
2. **保留 Exact Alarm 关键窗口**：继续在上班、下班前预热并验证，但把它视为降低固定时点失效
   概率的措施，不承诺全天在线。
3. **按证据做定向修复**：只有日志明确显示 Ktor、脚本引擎、Accessibility 或 Relay 重连的某一层
   单独失败时，才改对应层。
4. **停止无界叠加同 UID 保活**：轻微触摸、更多线程或更多同包 Service 会增加耗电和维护成本，
   但在整包冻结时一起停止，不能作为下一阶段主方案。
