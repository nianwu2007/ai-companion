# Fabric 线：安装与配置

## 环境

| 项 | 版本 |
| --- | --- |
| Minecraft | 1.20.1 |
| Fabric Loader | 0.16.10 或更高，jar 里声明 `>=0.15.0` |
| Fabric API | 0.92.2+1.20.1 附近均可，jar 里声明 `*` |
| Java | 17 |
| 可选 | Baritone、Automatone、Vosk |

## 装

1. 用官方安装器把 Fabric Loader 装到 1.20.1 上。
2. 把 `fabric-api` 和 `dist/ai-companion-1.0.0.jar` 一起丢进 `.minecraft/mods/`。
3. 启动游戏。

mod 是纯客户端 mod，`environment` 写作 `client`，专用服务器上不会加载。

## 配置

第一次启动会生成 `config/aicompanion.json`。这个文件每次启动都会被重新写一遍，等于一份自动更新的选项说明书。
改完再进游戏即可生效。

### 常用项

| 路径 | 默认值 | 说明 |
| --- | --- | --- |
| `llm.ollamaUrl` | `http://127.0.0.1:11434/v1/chat/completions` | 本地行动端点 |
| `llm.ollamaModel` | `llama3.2` | 换成你机器上真实存在的模型名 |
| `llm.deepseekUrl` | `https://api.deepseek.com/v1/chat/completions` | 云端聊天端点 |
| `llm.deepseekKeyEnv` | `DEEPSEEK_API_KEY` | 只读环境变量，Key 不落盘 |
| `llm.aiPrefix` | `@AI` | 只有这个前缀走云端 |
| `llm.throttleSeconds` | `15` | 云端思考的节流窗口 |
| `llm.respondToAllChat` | `false` | 打开后没带前缀的闲聊也让小怜接，走本地 |
| `llm.maxTokens` | `220` | 单次回复上限 |
| `llm.requestTimeoutSeconds` | `60` | 本地模型冷启动慢就加大 |
| `companion.name` | `小怜` | 头顶名字 |
| `companion.texture` | 随包附带的女仆装皮肤 `aicompanion:textures/entity/maid.png` | 可换成任意皮肤贴图资源 |
| `companion.followRadius` | `6.0` | 跟随距离 |
| `companion.autoSpawn` | `true` | 进世界是否自动出现 |
| `engine.reach` | `4.5` | 交互距离 |
| `engine.pathNodeLimit` | `4000` | A* 节点上限 |
| `engine.preferBaritone` | `true` | 装了 Baritone 就优先用它算路 |
| `stt.enabled` | `false` | 语音输入开关 |
| `stt.voskModelPath` | 示例路径 | 改成你自己解压出来的 Vosk 目录 |
| `stt.hotkey` | `` ` `` | 按住说话 |
| `tts.enabled` | `true` | 语音输出开关 |
| `tts.provider` | `edge` | `edge` 或 `kokoro` |
| `bridge.hermesMode` | `cli` | `off` / `cli` / `http` |
| `bridge.hermesCommand` | `hermes -z {msg}` | 一次性问答用 `-z`，`{msg}` 是占位符 |
| `api.port` | `8791` | 本机控制端点端口 |

### 云端 Key

不要把 Key 写进配置文件。设成环境变量就行：

```
setx DEEPSEEK_API_KEY 你的key
```

设完重启游戏。没设也完全没问题，`@AI` 会退回本地模型处理，只是话会笨一点。

## 自己编译

```bash
cd fabric
./gradlew build
```

产物落在 `build/libs/ai-companion-1.0.0.jar`。构建链上锁的是 Gradle 8.8 + Fabric Loom 1.6.12，
这个组合是迁就过的，随便升级 Loom 会要求更高的 Gradle。

`build.gradle` 里默认填了国内 Maven 镜像，网络通畅时可以换回官方源。

## 想装在 Forge 整合包里

理论上可以让 Sinytra Connector 代跑 Fabric mod。本项目未在此路径上做过完整验证，
尤其是客户端 mixin 部分，不保证可用。要在整合包里稳定用，建议单独开一个 Fabric 实例。

## 排错

| 现象 | 原因与处理 |
| --- | --- |
| 进游戏看不到小怜 | 按 `B` 手动召唤；若按了没反应，看日志里有没有 `aicompanion` 的加载记录 |
| 她不说话 | 确认 Ollama 在 `127.0.0.1:11434` 上活着，且 `llm.ollamaModel` 是真实存在的模型名 |
| 说 `@AI` 也没反应 | 检查 `DEEPSEEK_API_KEY` 是否设进环境变量，或把 `llm.allowDeepSeek` 暂时关掉 |
| 她走两步就停 | 抬 `engine.pathNodeLimit` 与 `llm.requestTimeoutSeconds` |
| 语音没声音 | 看 `tts.provider` 指向的服务是否在对应端口上跑着 |

日志里搜 `aicompanion` 能看到全部相关记录。
