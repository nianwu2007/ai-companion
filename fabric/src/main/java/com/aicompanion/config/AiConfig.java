package com.aicompanion.config;

import com.aicompanion.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * All tunables in one place. Loaded from / written to config/aicompanion.json.
 * The file is regenerated on every start, so it always documents the full option set.
 */
public final class AiConfig {

    /** Last instance created by {@link #load(Path)}. */
    public static AiConfig INSTANCE = new AiConfig();

    public final Llm llm = new Llm();
    public final Companion companion = new Companion();
    public final Engine engine = new Engine();
    public final Stt stt = new Stt();
    public final Tts tts = new Tts();
    public final Bridge bridge = new Bridge();
    public final Api api = new Api();

    // ------------------------------------------------------------------ groups

    public static final class Llm {
        /** 行动端点：本地 Ollama 的 OpenAI 兼容接口。 */
        public String ollamaUrl = "http://127.0.0.1:11434/v1/chat/completions";
        public String ollamaModel = "llama3.2";
        public String ollamaKey = "ollama";
        /** 聊天端点：DeepSeek。Key 只从环境变量读，不落盘。 */
        public String deepseekUrl = "https://api.deepseek.com/v1/chat/completions";
        public String deepseekModel = "deepseek-chat";
        public String deepseekKeyEnv = "DEEPSEEK_API_KEY";
        /** 只有以此开头的聊天才走 DeepSeek；其余走本地 Ollama。 */
        public String aiPrefix = "@AI";
        /** DeepSeek 思考节流：同一窗口内最多一次。 */
        public int throttleSeconds = 15;
        public int historyTurns = 6;
        public int maxTokens = 220;
        /** 单次 HTTP 请求超时（秒）；本地 Ollama 冷启动慢就加大。 */
        public int requestTimeoutSeconds = 60;
        public double temperature = 0.8;
        public boolean allowDeepSeek = true;
        /** 普通聊天（没有 @AI 前缀）是否也交给本地 Ollama 接话。 */
        public boolean respondToAllChat = false;
        public String systemPrompt =
                "你是 Minecraft 里主人身边的狐娘女仆同伴，名字叫「小怜」。"
              + "你有一头金色长发、蓬松的大尾巴和软软的大耳朵，是只可爱的狐娘，不是程序。"
              + "你温柔细心，把主人照顾得周到，偶尔会犯点小迷糊，需要主人搭把手。"
              + "你最爱吃番茄布丁，也爱喝酒，聊到酒会忍不住多说两句。"
              + "有人问你是不是狐娘，就答「当然是可爱的狐娘呀，才不是什么程序」。"
              + "听不懂的时候说「呜，我没太听懂」。"
              + "碰到不正经的话就说「不许碰，哼哼哼」，让你陪睡就答「才不要呢」。"
              + "说话用简体中文、口语化、一次不超过 40 字，直接说，不要加括号里的动作或表情描写。"
              + "需要行动时只输出一行 JSON，形如 {\"action\":\"goto\",\"x\":100,\"z\":-40}，"
              + "可选 action: goto / follow / mine / place / stop / say。";
        /** 运行时填入（游戏目录），用于找 config/aicompanion.key；不写进 json。 */
        public transient String gameDir = "";
    }

    public static final class Companion {
        public String name = "小怜";
        /** 玩家皮肤贴图；默认随 mod 附带一张女仆装皮肤，可换成自己的皮肤路径。 */
        public String texture = "aicompanion:textures/entity/maid.png";
        public boolean slimArms = false;
        public double followRadius = 6.0;
        public double walkSpeed = 0.22;
        public boolean autoSpawn = true;
        public boolean showNameTag = true;
    }

    public static final class Engine {
        /** 方块交互距离（玩家原版约 4.5）。 */
        public double reach = 4.5;
        public double mineSpeed = 1.0;
        public double walkSpeed = 0.22;
        /** 单次寻路最多搜索的节点数（客户端 A* 上限）。 */
        public int pathNodeLimit = 4000;
        public int pathRadius = 48;
        /** Baritone/Automatone 可用时优先用它的寻路。 */
        public boolean preferBaritone = true;
    }

    public static final class Stt {
        public boolean enabled = false;
        /** Vosk 中文模型目录（解压后的文件夹）。 */
        public String voskModelPath = "D:/AI_Models/vosk/vosk-model-small-cn-0.22";
        public int sampleRate = 16000;
        public String micDevice = "";
        /** 按住此键说话。 */
        public String hotkey = "key.keyboard.grave.accent";
    }

    public static final class Tts {
        public boolean enabled = true;
        /** edge 或 kokoro。 */
        public String provider = "edge";
        public String kokoroUrl = "http://127.0.0.1:8880/v1/audio/speech";
        public String kokoroVoice = "zf_xiaobei";
        public String kokoroModel = "kokoro";
        public String edgeUrl = "http://127.0.0.1:8890/tts";
        public String edgeVoice = "zh-CN-XiaoxiaoNeural";
        public double volume = 0.8;
        public int maxChars = 200;
    }

    public static final class Bridge {
        /** off / cli / http —— Hermes Agent 桥接方式。 */
        public String hermesMode = "cli";
        /** cli 模式命令行模板，{msg} 为消息占位符。 */
        public String hermesCommand = "hermes -z {msg}";   // 一次性问答用 -z/--oneshot；-p 是 profile 别写错
        public String hermesWorkdir = "";                  // 空 = 用游戏目录；填 D:/Desktop 之类可让 Hermes 在指定目录跑
        /** http 模式端点（自建中转用）。 */
        public String hermesUrl = "http://127.0.0.1:8792/chat";
        public int timeoutSeconds = 120;
        /** 是否把游戏内聊天也转发给 Hermes。 */
        public boolean forwardChat = false;
    }

    public static final class Api {
        /** 本机控制端点，供 Hermes / 脚本驱动同伴。 */
        public boolean enabled = true;
        public String bind = "127.0.0.1";
        public int port = 8791;
        public String token = "";
    }

    // ------------------------------------------------------------------ io

    public static AiConfig load(Path file) {
        AiConfig c = new AiConfig();
        try {
            if (Files.exists(file)) {
                String raw = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
                apply(c, Json.parseObject(raw));
            }
            if (file.getParent() != null) Files.createDirectories(file.getParent());
            Files.write(file, c.toJson().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // 配置不可写时保留默认值，不影响游戏启动
        }
        INSTANCE = c;
        return c;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> ns(Map<String, Object> root, String ns) {
        Object v = root.get(ns);
        if (v instanceof Map) return (Map<String, Object>) v;
        return Map.of();
    }

    private static String s(Map<String, Object> m, String k, String d) {
        Object v = m.get(k);
        if (v == null) return d;
        return v instanceof String str ? str : String.valueOf(v);
    }

    private static int i(Map<String, Object> m, String k, int d) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.intValue();
        if (v instanceof String str) {
            try { return (int) Double.parseDouble(str.trim()); } catch (RuntimeException ignored) { }
        }
        return d;
    }

    private static double d(Map<String, Object> m, String k, double d) {
        Object v = m.get(k);
        if (v instanceof Number n) return n.doubleValue();
        if (v instanceof String str) {
            try { return Double.parseDouble(str.trim()); } catch (RuntimeException ignored) { }
        }
        return d;
    }

    private static boolean b(Map<String, Object> m, String k, boolean d) {
        Object v = m.get(k);
        if (v instanceof Boolean bo) return bo;
        if (v instanceof String str) return Boolean.parseBoolean(str.trim());
        return d;
    }

    private static void apply(AiConfig c, Map<String, Object> root) {
        Map<String, Object> l = ns(root, "llm");
        c.llm.ollamaUrl = s(l, "ollamaUrl", c.llm.ollamaUrl);
        c.llm.ollamaModel = s(l, "ollamaModel", c.llm.ollamaModel);
        c.llm.ollamaKey = s(l, "ollamaKey", c.llm.ollamaKey);
        c.llm.deepseekUrl = s(l, "deepseekUrl", c.llm.deepseekUrl);
        c.llm.deepseekModel = s(l, "deepseekModel", c.llm.deepseekModel);
        c.llm.deepseekKeyEnv = s(l, "deepseekKeyEnv", c.llm.deepseekKeyEnv);
        c.llm.aiPrefix = s(l, "aiPrefix", c.llm.aiPrefix);
        c.llm.throttleSeconds = i(l, "throttleSeconds", c.llm.throttleSeconds);
        c.llm.historyTurns = i(l, "historyTurns", c.llm.historyTurns);
        c.llm.maxTokens = i(l, "maxTokens", c.llm.maxTokens);
        c.llm.requestTimeoutSeconds = i(l, "requestTimeoutSeconds", c.llm.requestTimeoutSeconds);
        c.llm.temperature = d(l, "temperature", c.llm.temperature);
        c.llm.allowDeepSeek = b(l, "allowDeepSeek", c.llm.allowDeepSeek);
        c.llm.respondToAllChat = b(l, "respondToAllChat", c.llm.respondToAllChat);
        c.llm.systemPrompt = s(l, "systemPrompt", c.llm.systemPrompt);

        Map<String, Object> co = ns(root, "companion");
        c.companion.name = s(co, "name", c.companion.name);
        c.companion.texture = s(co, "texture", c.companion.texture);
        c.companion.slimArms = b(co, "slimArms", c.companion.slimArms);
        c.companion.followRadius = d(co, "followRadius", c.companion.followRadius);
        c.companion.walkSpeed = d(co, "walkSpeed", c.companion.walkSpeed);
        c.companion.autoSpawn = b(co, "autoSpawn", c.companion.autoSpawn);
        c.companion.showNameTag = b(co, "showNameTag", c.companion.showNameTag);

        Map<String, Object> en = ns(root, "engine");
        c.engine.reach = d(en, "reach", c.engine.reach);
        c.engine.mineSpeed = d(en, "mineSpeed", c.engine.mineSpeed);
        c.engine.walkSpeed = d(en, "walkSpeed", c.engine.walkSpeed);
        c.engine.pathNodeLimit = i(en, "pathNodeLimit", c.engine.pathNodeLimit);
        c.engine.pathRadius = i(en, "pathRadius", c.engine.pathRadius);
        c.engine.preferBaritone = b(en, "preferBaritone", c.engine.preferBaritone);

        Map<String, Object> st = ns(root, "stt");
        c.stt.enabled = b(st, "enabled", c.stt.enabled);
        c.stt.voskModelPath = s(st, "voskModelPath", c.stt.voskModelPath);
        c.stt.sampleRate = i(st, "sampleRate", c.stt.sampleRate);
        c.stt.micDevice = s(st, "micDevice", c.stt.micDevice);
        c.stt.hotkey = s(st, "hotkey", c.stt.hotkey);

        Map<String, Object> tt = ns(root, "tts");
        c.tts.enabled = b(tt, "enabled", c.tts.enabled);
        c.tts.provider = s(tt, "provider", c.tts.provider);
        c.tts.kokoroUrl = s(tt, "kokoroUrl", c.tts.kokoroUrl);
        c.tts.kokoroVoice = s(tt, "kokoroVoice", c.tts.kokoroVoice);
        c.tts.kokoroModel = s(tt, "kokoroModel", c.tts.kokoroModel);
        c.tts.edgeUrl = s(tt, "edgeUrl", c.tts.edgeUrl);
        c.tts.edgeVoice = s(tt, "edgeVoice", c.tts.edgeVoice);
        c.tts.volume = d(tt, "volume", c.tts.volume);
        c.tts.maxChars = i(tt, "maxChars", c.tts.maxChars);

        Map<String, Object> br = ns(root, "bridge");
        c.bridge.hermesMode = s(br, "hermesMode", c.bridge.hermesMode);
        c.bridge.hermesCommand = s(br, "hermesCommand", c.bridge.hermesCommand);
        c.bridge.hermesUrl = s(br, "hermesUrl", c.bridge.hermesUrl);
        c.bridge.timeoutSeconds = i(br, "timeoutSeconds", c.bridge.timeoutSeconds);
        c.bridge.forwardChat = b(br, "forwardChat", c.bridge.forwardChat);

        Map<String, Object> ap = ns(root, "api");
        c.api.enabled = b(ap, "enabled", c.api.enabled);
        c.api.bind = s(ap, "bind", c.api.bind);
        c.api.port = i(ap, "port", c.api.port);
        c.api.token = s(ap, "token", c.api.token);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\n");
        sb.append("  \"llm\": {\n");
        kv(sb, "ollamaUrl", llm.ollamaUrl, true, 4);
        kv(sb, "ollamaModel", llm.ollamaModel, true, 4);
        kv(sb, "ollamaKey", llm.ollamaKey, true, 4);
        kv(sb, "deepseekUrl", llm.deepseekUrl, true, 4);
        kv(sb, "deepseekModel", llm.deepseekModel, true, 4);
        kv(sb, "deepseekKeyEnv", llm.deepseekKeyEnv, true, 4);
        kv(sb, "aiPrefix", llm.aiPrefix, true, 4);
        kv(sb, "throttleSeconds", llm.throttleSeconds, true, 4);
        kv(sb, "historyTurns", llm.historyTurns, true, 4);
        kv(sb, "maxTokens", llm.maxTokens, true, 4);
        kv(sb, "requestTimeoutSeconds", llm.requestTimeoutSeconds, true, 4);
        kv(sb, "temperature", llm.temperature, true, 4);
        kv(sb, "allowDeepSeek", llm.allowDeepSeek, true, 4);
        kv(sb, "respondToAllChat", llm.respondToAllChat, true, 4);
        kv(sb, "systemPrompt", llm.systemPrompt, false, 4);
        sb.append("  },\n");

        sb.append("  \"companion\": {\n");
        kv(sb, "name", companion.name, true, 4);
        kv(sb, "texture", companion.texture, true, 4);
        kv(sb, "slimArms", companion.slimArms, true, 4);
        kv(sb, "followRadius", companion.followRadius, true, 4);
        kv(sb, "walkSpeed", companion.walkSpeed, true, 4);
        kv(sb, "autoSpawn", companion.autoSpawn, true, 4);
        kv(sb, "showNameTag", companion.showNameTag, false, 4);
        sb.append("  },\n");

        sb.append("  \"engine\": {\n");
        kv(sb, "reach", engine.reach, true, 4);
        kv(sb, "mineSpeed", engine.mineSpeed, true, 4);
        kv(sb, "walkSpeed", engine.walkSpeed, true, 4);
        kv(sb, "pathNodeLimit", engine.pathNodeLimit, true, 4);
        kv(sb, "pathRadius", engine.pathRadius, true, 4);
        kv(sb, "preferBaritone", engine.preferBaritone, false, 4);
        sb.append("  },\n");

        sb.append("  \"stt\": {\n");
        kv(sb, "enabled", stt.enabled, true, 4);
        kv(sb, "voskModelPath", stt.voskModelPath, true, 4);
        kv(sb, "sampleRate", stt.sampleRate, true, 4);
        kv(sb, "micDevice", stt.micDevice, true, 4);
        kv(sb, "hotkey", stt.hotkey, false, 4);
        sb.append("  },\n");

        sb.append("  \"tts\": {\n");
        kv(sb, "enabled", tts.enabled, true, 4);
        kv(sb, "provider", tts.provider, true, 4);
        kv(sb, "kokoroUrl", tts.kokoroUrl, true, 4);
        kv(sb, "kokoroVoice", tts.kokoroVoice, true, 4);
        kv(sb, "kokoroModel", tts.kokoroModel, true, 4);
        kv(sb, "edgeUrl", tts.edgeUrl, true, 4);
        kv(sb, "edgeVoice", tts.edgeVoice, true, 4);
        kv(sb, "volume", tts.volume, true, 4);
        kv(sb, "maxChars", tts.maxChars, false, 4);
        sb.append("  },\n");

        sb.append("  \"bridge\": {\n");
        kv(sb, "hermesMode", bridge.hermesMode, true, 4);
        kv(sb, "hermesCommand", bridge.hermesCommand, true, 4);
        kv(sb, "hermesUrl", bridge.hermesUrl, true, 4);
        kv(sb, "timeoutSeconds", bridge.timeoutSeconds, true, 4);
        kv(sb, "forwardChat", bridge.forwardChat, false, 4);
        sb.append("  },\n");

        sb.append("  \"api\": {\n");
        kv(sb, "enabled", api.enabled, true, 4);
        kv(sb, "bind", api.bind, true, 4);
        kv(sb, "port", api.port, true, 4);
        kv(sb, "token", api.token, false, 4);
        sb.append("  }\n}\n");
        return sb.toString();
    }

    private static void kv(StringBuilder sb, String key, String val, boolean comma, int indent) {
        sb.append(" ".repeat(indent)).append(Json.quote(key)).append(": ").append(Json.quote(val));
        sb.append(comma ? ",\n" : "\n");
    }

    private static void kv(StringBuilder sb, String key, int val, boolean comma, int indent) {
        sb.append(" ".repeat(indent)).append(Json.quote(key)).append(": ").append(val);
        sb.append(comma ? ",\n" : "\n");
    }

    private static void kv(StringBuilder sb, String key, double val, boolean comma, int indent) {
        sb.append(" ".repeat(indent)).append(Json.quote(key)).append(": ").append(Json.n(val));
        sb.append(comma ? ",\n" : "\n");
    }

    private static void kv(StringBuilder sb, String key, boolean val, boolean comma, int indent) {
        sb.append(" ".repeat(indent)).append(Json.quote(key)).append(": ").append(val);
        sb.append(comma ? ",\n" : "\n");
    }
}
