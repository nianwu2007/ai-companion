# MaidAI Bridge

客户端侧本地 HTTP 控制/感知桥，把 Minecraft 暴露给本机 AI 代理。
**端口 `127.0.0.1:8790`（仅本机回环，不对外开放）。**

- 版本：`1.0.0` / MC `1.20.1` / Forge `47.3.33`（`clientSideOnly=true`，服务端自动跳过）
- 不依赖任何其它 mod（TLM / Baritone 都只是"目标"，不是编译依赖）
- 零 token、零外部网络：所有逻辑在本机

---

## 为什么是这个形状

TLM（女仆 mod）的 LLM 是**客户端拉取式**：只在玩家跟女仆交互时才发请求。
它没法主动推动游戏，也没法回答"我现在在哪、周围有什么怪"。

这个 mod 补上缺失的两半：

| 能力 | 端点 | 用途 |
| --- | --- | --- |
| 感知 | `GET /state` | 玩家坐标/血量/饱食度/维度、世界时间天气、**附近的 TLM 女仆**（名字/距离/血量）、**最近的敌对生物数量与距离** |
| 听觉 | `GET /chatlog` | 环形缓冲 200 条：游戏内收到的聊天 + 我们发出去的聊天 |
| 说话 | `POST /chat` `POST /echo` | 发游戏内聊天 / 指令；`/echo` 是纯本地打印（不联网、不会被服务器看到） |
| 走路 | `POST /goto` `POST /follow` `POST /baritone` `POST /stop` | 直通 Baritone |
| 情报 | `GET /mods?q=` | 列出已装载的 mod（id/名称/版本） |

## 安装

1. 把 `maidai_bridge-1.0.0.jar` 丢进整合包的 `mods/` 目录。
2. 重启游戏。
3. 验证：`curl http://127.0.0.1:8790/ping` → `{"ok":true,"mod":"maidai_bridge",...}`

> 单机窗口失焦时游戏会暂停 → mod 默认把 `pauseOnLostFocus` 关掉（可在配置里改回）。
> 这是自动化能跑起来的前提。

## 配置

可选文件：`.minecraft/config/maidai_bridge.json`（版本隔离时在实例的 `config/` 下）

```json
{
  "port": 8790,
  "token": "",
  "disablePauseOnLostFocus": true
}
```

`token` 非空时，请求必须带 `X-Token: <token>` 头。

## 端点速查

```bash
# 感知
curl http://127.0.0.1:8790/state
curl http://127.0.0.1:8790/chatlog
curl "http://127.0.0.1:8790/mods?q=baritone"

# 说话
curl -X POST http://127.0.0.1:8790/chat -d '{"text":"主人，我回来了"}'
curl -X POST http://127.0.0.1:8790/cmd  -d '{"text":"/time set day"}'
curl -X POST http://127.0.0.1:8790/echo -d '{"text":"[小怜] 我在这儿呢"}'

# 走路（Baritone）
curl -X POST http://127.0.0.1:8790/goto   -d '{"x":120,"y":64,"z":-330}'
curl -X POST http://127.0.0.1:8790/follow -d '{"player":"Steve"}'
curl -X POST http://127.0.0.1:8790/baritone -d '{"cmd":"mine diamond_ore"}'
curl -X POST http://127.0.0.1:8790/stop
```

`/baritone` 接受任何 Baritone 指令（不带 `#`）：`goto` `path` `mine` `follow` `come` `explore` `farm` `build` `stop` `cancel` `sel` `thisway` `tunnel` `goal` `proc` 等。

## `/state` 返回示例

```json
{"ok":true,"inGame":true,"screen":"none",
 "mods":{"baritone":true,"tlm":true,"kubejs":true},
 "player":{"name":"Steve","x":118.5,"y":64.0,"z":-329.4,"yaw":-90.0,"health":20.0,
           "maxHealth":20.0,"food":18,"xpLevel":3,"onGround":true,"sneaking":false,
           "blockX":118,"blockY":64,"blockZ":-330,"dimension":"minecraft:overworld"},
 "world":{"dayTime":6000,"gameTime":6000,"raining":false,"thundering":false},
 "maids":[{"name":"小怜","custom":true,"type":"EntityMaid","x":117.2,"y":64.0,"z":-328.1,
           "distance":2.1,"health":20.0}],
 "threat":{"hostiles":2,"nearestHostile":11.7}}
```

## 设计边界

- **不碰 TLM 内部**：mod 不导入 TLM 类（避免版本耦合）。女仆编排仍走 MaidAI 网关的 `director.json`（台词层 + 动作层，7 个 TLM 工具）。本 mod 只负责 TLM 做不到的：**主动感知 + 主动行动**。
- **不注册按键/不劫持输入**：所有动作通过发聊天/指令实现，等价于玩家自己在聊天框敲 —— 可被服务器/日志审计，行为可解释。
- `clientSideOnly=true`：服务端不会有这个 mod，多人服务器上它只是本地辅助。
