# 本地模型与服务

小怜的耳朵、嗓子和思考都不出这台机器。想让全链路跑起来，把下面几样备齐。

## 一、Ollama：她的思考

装好 Ollama 后拉一个模型：

```bash
ollama pull qwen3:8b
ollama pull huihui_ai/qwen3-abliterated:8b     # 说话更放得开
ollama pull llama3.2                            # 轻量，慢机器也能跑
```

然后按你拉到的名字改 `config/aicompanion.json`：

```json
"ollamaModel": "qwen3:8b"
```

模型名写错是最常见的不出声原因，先在终端确认一遍：

```bash
curl -s http://127.0.0.1:11434/api/tags
```

想让别的机器上的 Ollama 也能用，启动前设：

```
OLLAMA_HOST=0.0.0.0:11434
```

显存吃紧时压低上下文：

```
OLLAMA_CONTEXT_LENGTH=2048
OLLAMA_FLASH_ATTENTION=1
OLLAMA_KV_CACHE_TYPE=q8_0
```

**关于工具调用**：8B 级的小模型在需要输出结构化动作时经常给出格式不对的东西。
如果她只说不动手，优先换大一点的模型，或者把动作交给带工具能力的云端模型。

## 二、Vosk：她的耳朵

下载中文小模型 `vosk-model-small-cn-0.22`，约 42 MB，解压得到一个文件夹。

```bash
# 解压后目录结构形如
# D:/AI_Models/vosk/vosk-model-small-cn-0.22/
#   am/  conf/  graph/  ivector/  README
```

配置里指过去，然后打开开关：

```json
"stt": { "enabled": true, "voskModelPath": "D:/AI_Models/vosk/vosk-model-small-cn-0.22" }
```

输入采样率固定 16000。默认按住 ` 键说话，松开就识别。

## 三、TTS：她的嗓子

两条路，选一条即可，配置里 `tts.provider` 决定走哪条。

### 方案 A：Edge TTS，最省事

```bash
pip install edge-tts
```

起一个小服务把 `POST /tts` 转成 `edge-tts` 命令行调用，监听 `127.0.0.1:8890`，
配置里保持：

```json
"tts": { "provider": "edge", "edgeVoice": "zh-CN-XiaoxiaoNeural" }
```

`edgeVoice` 可以换 `zh-CN-XiaoyiNeural`、`zh-CN-YunxiNeural` 等。
这条路要联网，但完全免费。

### 方案 B：Kokoro，纯离线

本地起一个兼容 OpenAI 语音接口的服务，监听 `127.0.0.1:8880`，配置改成：

```json
"tts": { "provider": "kokoro", "kokoroVoice": "zf_xiaobei" }
```

好处是断网也能出声，代价是要先下模型、占一点显存。

## 四、可选：Baritone / Automatone

装了它们，`engine.preferBaritone = true` 时小怜会用 Baritone 的路由代替内置 A*，
走远路明显更聪明，会自己搭桥、绕水。没装也不会报错，自动退回内置寻路。

## 五、一张检查表

```bash
curl -s http://127.0.0.1:11434/api/tags      # 思考：模型列表
ls 你的vosk模型目录                            # 耳朵：目录在不在
curl -s http://127.0.0.1:8890/health         # 嗓子：服务活着吗
```

三样都通，小怜就是完整形态。
