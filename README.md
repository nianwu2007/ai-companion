# AI Companion · 会自己动手的 Minecraft AI 同伴

「小怜」——一个活在 Minecraft 里的玩家模型实体。她会跟着你走、自己算路、挖方块、放方块，
会听你说话，也会开口回你。整套链路零云端成本：思考用本地 Ollama，耳朵是 Vosk，嗓子是 Edge TTS 或 Kokoro。
只有你特意加上 `@AI` 前缀的那一句，才会送去云端大脑。

她穿着随包附带的女仆装，一身酒狐配色：金橙长发、白围裙、深蓝黑裙、红肩饰、白长袜。
头顶另有一对狐耳、脑后垂一束长发、身后拖一条三节大尾巴，耳朵和后发跟着头转，尾巴一直在轻摇。
想让她换成别的样子，改 `config/aicompanion.json` 里的 `companion.texture` 即可；
狐狸零件在 `fabric/src/main/java/com/aicompanion/client/render/CompanionFoxModel.java`
与 `CompanionFoxFeature.java`，贴图是 `assets/aicompanion/textures/entity/fox_parts.png`。

---

## 这个仓库里有什么

| 目录 | 是什么 | 目标环境 |
| --- | --- | --- |
| `fabric/` | **主线**：AI 玩家同伴完整源码。玩家模型实体、物品栏、挖掘与放置、A* 与 Baritone 双寻路、LLM 编排、语音进出、Hermes 桥、本机控制端点 | Fabric 1.20.1，客户端 |
| `forge/` | **桥接**：`maidai_bridge` 本机 HTTP 桥。把玩家坐标、世界状态、女仆列表、威胁信息与聊天记录开成只监听 127.0.0.1 的接口，供外部 AI 或脚本读取和发言 | Forge 1.20.1，客户端 |
| `dist/` | 已经编译好的两个 jar，直接丢进 `mods/` 就能用 | — |
| `tools/` | `chat-in` 中文直送小窗：不在游戏里打字，也能把中文发进聊天 | Windows |
| `docs/` | 安装、配置、模型、用法、中文输入五份说明 | — |

---

## 三十秒装好

1. 装好 Minecraft 1.20.1 与对应的 Fabric Loader，并把 [Fabric API](https://modrinth.com/mod/fabric-api) 放进 `mods/`。
2. 把 `dist/ai-companion-1.0.0.jar` 丢进同一个 `mods/`。
3. 起一个本地 Ollama，拉一个模型，例如 `ollama pull qwen3:8b`。
4. 启动游戏。第一次进世界会生成 `config/aicompanion.json`，把所有可调项都写清楚了。
5. 按 `B` 把小怜叫出来。她会出现你身旁，头顶写着名字。

想让她听懂你说话，再去 `docs/local-models.md` 把 Vosk 中文模型和 TTS 服务拉起来。
不想在游戏里跟输入法较劲，双击 `tools/chat-in.bat`，浮窗里打中文，回车就发进聊天——
细节见 `docs/chat-input.md`。

---

## 她怎么思考

```
你的聊天
   │
   ├── 带 @AI 前缀 ──→ 云端 DeepSeek ──┐
   │                  15 秒节流一次    │
   └── 其他情况  ──→ 本地 Ollama ─────┤
                                     ↓
                            同伴大脑 CompanionBrain
                                     ↓
                     说一句 / 或者执行一条动作 JSON
                                     ↓
                     跟随 · 走到 · 挖 · 放 · 攻击 · 停
```

- **行动**走本地：`http://127.0.0.1:11434/v1/chat/completions`，模型可随便换。
- **聊天**走云端：`https://api.deepseek.com/v1/chat/completions`，Key 只从环境变量 `DEEPSEEK_API_KEY` 读，绝不写进任何文件。
- 所有 HTTP 与寻路都在工作线程上跑，回到主线程才碰游戏，所以卡顿与掉帧不会发生在你身上。
- 默认只有 `@AI` 开头的话才接。想让小怜接住所有闲聊，把配置里 `llm.respondToAllChat` 打开，那也只烧本地电。

---

## 她会做的事

| 动作 | 含义 |
| --- | --- |
| `goto x z` | 算路走过去 |
| `follow` | 跟着你 |
| `mine x y z` | 挖掉那个方块 |
| `place x y z item` | 把东西放下去 |
| `hold item` | 切换到手上的物品 |
| `drop item` | 丢出去 |
| `attack` | 打最近的怪 |
| `wait 20t` | 等一会儿 |
| `stop` | 立刻停手 |
| `say 文本` | 说一句 |

模型可以只回一句话，也可以回一行 JSON 让她动手。两者都认。

---

## 快捷键

| 按键 | 作用 |
| --- | --- |
| `V` | 按住说话，松开识别 |
| `B` | 叫出或收起小怜 |
| `N` | 立刻停止当前动作 |
| `M` | 打出当前状态 |

键位在 `选项 → 控制` 里可以改。

---

## 本机控制端点

小怜在 `127.0.0.1:8791` 开了一个控制口，方便脚本或别的 AI 驱动她：

```
GET  /ping  /health  /state
POST /chat  {"msg": "@AI 去那边挖点石头", "from": "player"}
POST /cmd   {"cmd": "goto", "x": 10, "z": -5}
POST /bare  {"path": "...", "body": "..."}
```

只绑定本机回环地址，外网碰不到。

---

## 自己编译

需要 JDK 17。

```bash
cd fabric
./gradlew build          # 产物在 build/libs/ai-companion-1.0.0.jar

cd ../forge
./gradlew build          # 产物在 build/libs/maidai_bridge-1.0.0.jar
```

工程用的 Gradle Wrapper 是 8.8，Fabric Loom 是 1.6.12，两边版本互相迁就过，别随手升级其中一个。
依赖里默认填了国内 Maven 镜像，网通的地方可以换回官方源。

---

## 目录结构

```
fabric/src/main/java/com/aicompanion/
  entity/     玩家模型实体，带自己的物品栏
  engine/     玩家引擎、A* 寻路、步进执行、Baritone 桥
  llm/        协议层、传输层、编排大脑、动作定义
  voice/      Vosk 语音输入与 TTS 输出
  client/     按键、聊天监听、渲染
  bridge/     Hermes Agent 桥，三种模式 cli / http / off
  api/        本机控制端点
  mixin/      聊天栏与网络包的低层挂点
```

---

## 许可

MIT，见 `LICENSE`。

第三方组件：Fabric Loader 与 Fabric API、Forge MDK、以及可选接入的 Baritone / Automatone / Vosk / Kokoro，各自遵循原项目许可。
