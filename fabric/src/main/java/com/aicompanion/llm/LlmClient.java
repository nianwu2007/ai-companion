package com.aicompanion.llm;

import com.aicompanion.config.AiConfig;
import com.aicompanion.core.Async;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 两个大脑的 HTTP 出口：
 *   行动大脑 —— 本地 Ollama（llama3.2），OpenAI 兼容 /v1/chat/completions
 *   聊天大脑 —— DeepSeek 云端（deepseek-chat），key 从环境变量 DEEPSEEK_API_KEY 读，绝不落盘到游戏目录
 *
 * 所有请求都扔到 Async 线程池，主线程不阻塞。
 */
public final class LlmClient {

    private LlmClient() {}

    // ------------------------------------------------------------ 本地 Ollama（行动）

    /** 问本地 Ollama。requireJson=true 时请求 JSON 模式（动作规划用）。 */
    public static CompletableFuture<String> askOllama(List<Llm.Msg> messages, boolean requireJson) {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        String body = Llm.buildBody(c.ollamaModel, messages, c.temperature,
                requireJson ? Math.max(256, c.maxTokens) : c.maxTokens, requireJson);
        String bearer = c.ollamaKey == null || c.ollamaKey.isBlank() ? "ollama" : c.ollamaKey;
        return Async.postJson(c.ollamaUrl, body, bearer, c.requestTimeoutSeconds)
                .thenApply(Llm::extractContent);
    }

    /** 便捷入口：system + user 两条消息。 */
    public static CompletableFuture<String> askOllama(String system, String user, boolean requireJson) {
        List<Llm.Msg> msgs = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            msgs.add(Llm.Msg.system(system));
        }
        msgs.add(Llm.Msg.user(user));
        return askOllama(msgs, requireJson);
    }

    // ------------------------------------------------------------ 云端 DeepSeek（聊天）

    /** 问 DeepSeek。没配 key 时立刻返回一句提醒，不发起请求。 */
    public static CompletableFuture<String> askDeepSeek(List<Llm.Msg> messages) {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        String key = deepseekKey();
        if (key.isBlank()) {
            return CompletableFuture.completedFuture(
                    "(没读到 " + c.deepseekKeyEnv + "，云端聊天先关着；设好环境变量或 config/aicompanion.key 再重启)");
        }
        String body = Llm.buildBody(c.deepseekModel, messages, c.temperature, Math.max(256, c.maxTokens), false);
        return Async.postJson(c.deepseekUrl, body, key, c.requestTimeoutSeconds)
                .thenApply(Llm::extractContent);
    }

    public static CompletableFuture<String> askDeepSeek(String system, String user) {
        List<Llm.Msg> msgs = new ArrayList<>();
        if (system != null && !system.isBlank()) {
            msgs.add(Llm.Msg.system(system));
        }
        msgs.add(Llm.Msg.user(user));
        return askDeepSeek(msgs);
    }

    // ------------------------------------------------------------ key 解析

    private static String cachedKey;

    /**
     * DeepSeek key 的查找顺序（都不落盘到 mod 目录）：
     *   1) 环境变量（config 里的 deepseekKeyEnv，默认 DEEPSEEK_API_KEY）
     *   2) %LOCALAPPDATA%/hermes/.env 里的同名项（本机 Hermes 已有，可直接复用）
     *   3) 游戏目录 config/aicompanion.key（自己手写一行，gitignore 级别的东西）
     */
    public static String deepseekKey() {
        if (cachedKey != null) {
            return cachedKey;
        }
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        String name = c.deepseekKeyEnv == null || c.deepseekKeyEnv.isBlank() ? "DEEPSEEK_API_KEY" : c.deepseekKeyEnv;

        String v = System.getenv(name);
        if (v != null && !v.isBlank()) {
            cachedKey = v.trim();
            return cachedKey;
        }

        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            String fromEnvFile = readKeyFromEnvFile(Path.of(localAppData, "hermes", ".env"), name);
            if (!fromEnvFile.isBlank()) {
                cachedKey = fromEnvFile;
                return cachedKey;
            }
        }

        try {
            Path f = Path.of(c.gameDir, "config", "aicompanion.key");
            if (Files.isRegularFile(f)) {
                for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                    line = line.trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }
                    cachedKey = line.startsWith(name + "=") ? line.substring(name.length() + 1).trim() : line;
                    return cachedKey;
                }
            }
        } catch (IOException | RuntimeException ignored) {
            // 读不到就当没配
        }

        cachedKey = "";
        return cachedKey;
    }

    private static String readKeyFromEnvFile(Path file, String name) {
        try {
            if (!Files.isRegularFile(file)) {
                return "";
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                if (line.startsWith("export ")) {
                    line = line.substring(7).trim();
                }
                int eq = line.indexOf('=');
                if (eq <= 0 || !line.substring(0, eq).trim().equals(name)) {
                    continue;
                }
                String val = line.substring(eq + 1).trim();
                if (val.length() >= 2
                        && ((val.startsWith("\"") && val.endsWith("\"")) || (val.startsWith("'") && val.endsWith("'")))) {
                    val = val.substring(1, val.length() - 1);
                }
                return val;
            }
        } catch (IOException | RuntimeException ignored) {
            // 忽略
        }
        return "";
    }

    /** 重启后想重新读 key 就调它（配置面板用）。 */
    public static void invalidateKey() {
        cachedKey = null;
    }

    public static boolean hasDeepSeekKey() {
        return !deepseekKey().isBlank();
    }

    /** 本机 Ollama 是否活着（设置界面/启动自检用）。 */
    public static boolean ollamaAlive() {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        String url = c.ollamaUrl;
        int idx = url.indexOf("/v1/");
        String base = idx > 0 ? url.substring(0, idx) : url;
        return Async.ping(base + "/api/tags", 3);
    }
}
