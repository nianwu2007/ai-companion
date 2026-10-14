# 用法

## 跟她说话

游戏聊天栏里直接打字，或者按 `V` 按住说话。

- **普通聊天**：默认她不插嘴，除非你把 `llm.respondToAllChat` 打开。开了之后走本地模型，不花钱。
- **`@AI` 开头**：这一句会送去云端大脑，回得更聪明。同一个 15 秒窗口里只送一次，避免刷屏和烧钱。
- **私聊**：别人对她说话时，聊天栏里 `<玩家名> 内容` 这种格式也会被她读到。

示例：

```
@AI 帮我在脚下放个火把
@AI 去前面那棵树那边
@AI 我这次带的装备够打末影龙吗
```

## 让她动手

模型可以回一句话，也可以回一行动作 JSON。你也可以直接下令，不经过模型：

```
@AI {"action":"goto","x":100,"z":-40}
@AI {"action":"mine","x":100,"y":64,"z":-40}
@AI {"action":"follow"}
@AI {"action":"stop"}
```

完整的动作表：

| 动作 | 参数 | 效果 |
| --- | --- | --- |
| `goto` | `x` `z` | 算路走过去，会避障 |
| `follow` | 无 | 跟着你，距离由 `companion.followRadius` 决定 |
| `mine` | `x` `y` `z` | 走到位并挖掉该方块 |
| `place` | `x` `y` `z` `item` | 放置物品 |
| `hold` | `item` | 切换到该物品 |
| `drop` | `item` | 丢出物品 |
| `use` | 无 | 使用手上的东西 |
| `attack` | 无 | 攻击最近的敌对生物 |
| `jump` | 无 | 跳一下 |
| `wait` | `ticks` | 等待，例如 `{"action":"wait","ticks":40}` |
| `stop` | 无 | 立刻清空当前意图 |
| `say` | `text` | 说一句 |

别名也认：`walk` / `move` / `go` 都等于 `goto`，`dig` / `break` 等于 `mine`，
`put` / `setblock` / `build` 等于 `place`。

## 快捷键

| 按键 | 作用 |
| --- | --- |
| `V` | 按住说话，松开识别 |
| `B` | 叫出或收起小怜 |
| `N` | 立刻停止当前动作 |
| `M` | 输出当前状态 |

都在 `选项 → 控制 → AI Companion` 里改。

## 用脚本驱动她

`127.0.0.1:8791` 上的控制端点，适合接自动化：

```bash
# 活着吗
curl -s http://127.0.0.1:8791/ping

# 当前状态：位置、目标、手上的东西、在做什么
curl -s http://127.0.0.1:8791/state

# 当聊天用，走完整大脑链路，认 @AI 前缀
curl -s -X POST http://127.0.0.1:8791/chat \
     -H "Content-Type: application/json" \
     -d '{"msg":"@AI 帮我看看周围有没有怪","from":"player"}'

# 跳过模型，直接下一条动作
curl -s -X POST http://127.0.0.1:8791/cmd \
     -H "Content-Type: application/json" \
     -d '{"cmd":"goto","x":120,"z":-60}'
```

## 接上 Hermes Agent

`bridge.hermesMode` 有三个选择：

| 模式 | 行为 |
| --- | --- |
| `off` | 断开，游戏里的 AI 全靠本地模型与云端大脑 |
| `cli` | 每次提问起一个 Hermes 一次性进程，默认 `hermes -z {msg}` |
| `http` | 把请求转发到你自己搭的中转，默认 `http://127.0.0.1:8792/chat` |

`cli` 模式下 `bridge.hermesWorkdir` 留空则用游戏目录，填别的目录可以让 Hermes 在指定工作区里干活。
`bridge.forwardChat` 打开后，游戏里的聊天也会转发过去。

## 行为习惯

- 她不会抢你的东西。物品栏是独立的。
- 她不会自己乱跑，`follow` 与 `goto` 之外的时间都在原地待命。
- 按 `N` 永远能让她停下来，这条优先于任何动作。
- 寻路在后台线程算，算不完也不会卡住游戏。
