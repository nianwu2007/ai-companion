package com.aicompanion.bridge;

import com.aicompanion.config.AiConfig;
import com.aicompanion.core.Async;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Hermes Agent 桥 —— 把玩家的对话转发给本机 Hermes 大脑，再把回复回写到游戏聊天里。
 *
 * 两种模式（config.bridge.hermesMode）：
 *   cli  —— 起一次性进程：hermes -z "问题"，读 stdout（默认；完全本地，零 token 走本机模型）
 *   http —— POST 到 config.bridge.hermesUrl（你自己起的中转服务）
 *   off  —— 关掉
 *
 * 注意：hermes 的 -p 是 profile，一次性问答必须用 -z/--oneshot，别写错。
 */
public final class HermesBridge {

    private HermesBridge() {}

    /** 转发一条消息，异步返回 Hermes 的回答。 */
    public static CompletableFuture<String> ask(String message) {
        AiConfig.Bridge cfg = AiConfig.INSTANCE.bridge;
        String mode = cfg.hermesMode == null ? "off" : cfg.hermesMode.trim().toLowerCase();
        return switch (mode) {
            case "cli" -> Async.supply(() -> runCli(cfg, message));
            case "http" -> Async
                    .postJson(cfg.hermesUrl,
                            "{\"message\":" + com.aicompanion.util.Json.quote(message) + "}",
                            null, cfg.timeoutSeconds)
                    .thenApply(body -> {
                        try {
                            String reply = com.aicompanion.util.Json.str(body, "reply");
                            if (reply.isEmpty()) {
                                reply = com.aicompanion.util.Json.str(body, "text");
                            }
                            if (reply.isEmpty()) {
                                reply = com.aicompanion.util.Json.strPath(body, "choices", "0", "message", "content");
                            }
                            return reply;
                        } catch (RuntimeException e) {
                            return body;
                        }
                    });
            default -> CompletableFuture.completedFuture("");
        };
    }

    // ------------------------------------------------------------------ CLI

    private static String runCli(AiConfig.Bridge cfg, String message) {
        String template = cfg.hermesCommand == null || cfg.hermesCommand.isBlank()
                ? "hermes -z {msg}"
                : cfg.hermesCommand;

        List<String> args = new ArrayList<>();
        for (String tok : tokenize(template)) {
            if (tok.contains("{msg}")) {
                args.add(tok.replace("{msg}", message));
            } else {
                args.add(tok);
            }
        }
        if (!template.contains("{msg}")) {
            args.add(message); // 模板里没占位符就把消息挂最后
        }
        if (args.isEmpty()) {
            return "";
        }

        // Windows 上 hermes 往往是 .exe/.cmd 控制台脚本，Java 不认 PATHEXT，得自己解析
        String exe = resolveExecutable(args.get(0));
        if (exe == null) {
            return "(找不到 hermes 可执行文件，请在 config/aicompanion.json 的 bridge.hermesCommand 里写绝对路径)";
        }
        args.set(0, exe);

        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectErrorStream(true);
        File work = cfg.hermesWorkdir == null || cfg.hermesWorkdir.isBlank()
                ? null
                : new File(cfg.hermesWorkdir);
        if (work != null && work.isDirectory()) {
            pb.directory(work);
        }
        // Hermes 那边用不用本机模型由它自己的 config 决定，这里只保证不被上游代理污染
        pb.environment().putIfAbsent("NO_COLOR", "1");

        Process proc = null;
        try {
            proc = pb.start();
            StringBuilder out = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    out.append(line).append('\n');
                }
            }
            if (!proc.waitFor(Math.max(10, cfg.timeoutSeconds), TimeUnit.SECONDS)) {
                proc.destroyForcibly();
                return "(Hermes 超时了，先不等它了)";
            }
            String text = out.toString().trim();
            return text.isEmpty() ? "(Hermes 没说话)" : text;
        } catch (IOException e) {
            return "(连不上 Hermes: " + e.getMessage() + ")";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (proc != null) {
                proc.destroyForcibly();
            }
            return "(被打断了)";
        }
    }

    /** 支持引号的简单分词（够用：hermes -z "你好"）。 */
    static List<String> tokenize(String s) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    cur.append(c);
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (cur.length() > 0) {
                    out.add(cur.toString());
                    cur.setLength(0);
                }
            } else {
                cur.append(c);
            }
        }
        if (cur.length() > 0) {
            out.add(cur.toString());
        }
        return out;
    }

    /**
     * 找出可执行文件的真实路径。先在 PATH 里按 Windows 的扩展名顺序找，
     * 再退回到「直接交给系统」（返回原名）。
     */
    static String resolveExecutable(String token) {
        File direct = new File(token);
        if (direct.isAbsolute() || token.contains("/") || token.contains("\\")) {
            return direct.isFile() ? direct.getAbsolutePath() : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return token;
        }
        String[] exts = {"", ".exe", ".cmd", ".bat", ".sh"};
        for (String ext : exts) {
            for (String dir : path.split(File.pathSeparator)) {
                if (dir.isBlank()) {
                    continue;
                }
                try {
                    Path candidate = Path.of(dir, token + ext);
                    if (Files.isRegularFile(candidate)) {
                        return candidate.toString();
                    }
                } catch (RuntimeException ignored) {
                    // 非法路径段，跳过
                }
            }
        }
        return token; // 交给 ProcessBuilder 自己碰运气
    }
}
