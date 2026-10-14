# Forge 线：maidai_bridge 本机数据桥

这条线只做一件事：把运行中的游戏状态开成本机 HTTP 接口，让外部 AI、脚本、或你自己在终端里读和写。

它自己不思考，也不连任何云端。所有接口只绑定 `127.0.0.1`。

## 环境

| 项 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Forge | 47.x |
| 侧别 | 纯客户端，`clientSideOnly = true` |

## 装

把 `dist/maidai_bridge-1.0.0.jar` 丢进 `.minecraft/mods/`，启动游戏。
默认监听 `127.0.0.1:8790`。

## 接口

```
GET  /ping                  存活探测
GET  /health                同上，带更多信息
GET  /state                 玩家 / 世界 / 女仆 / 威胁 快照
GET  /chatlog               最近的聊天记录环形缓冲
POST /chat   {"text": "..."}    发言，开头带 / 则当命令执行
POST /cmd    {"text": "/..."}   直接执行命令
```

`/state` 返回的东西大致长这样：

```json
{
  "inGame": true,
  "screen": "PauseScreen",
  "player": { "name": "FXY", "x": -708.31, "y": 68.0, "z": 367.81,
              "health": 24, "maxHealth": 24, "food": 20, "level": 28,
              "dimension": "minecraft:overworld" },
  "maids":   { "count": 2, "nearest": 112.0 },
  "threat":  { "hostiles": 50, "nearest": 46.46 }
}
```

在终端里读一次：

```bash
curl -s http://127.0.0.1:8790/state
```

让游戏里的角色说句话：

```bash
curl -s -X POST http://127.0.0.1:8790/chat \
     -H "Content-Type: application/json" \
     -d '{"text":"我回来了"}'
```

## 自己编译

```bash
cd forge
./gradlew build
```

产物在 `build/libs/maidai_bridge-1.0.0.jar`。构建用的是 Forge MDK 模板，第一次编译会下载
Forge 与映射文件，慢是正常的。

## 和女仆 mod 的关系

`maids.count` 与 `maids.nearest` 读的是 Touhou Little Maid 的女仆实体。没装那个 mod 时，
这两项会是 0 和 `-1`，其余功能照常。
