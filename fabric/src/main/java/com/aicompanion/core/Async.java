package com.aicompanion.core;

import net.minecraft.client.MinecraftClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 异步基础设施 —— 全局唯一约定：
 *   1) 所有 HTTP / 寻路 / 文件 IO 一律丢到后台线程池；
 *   2) 所有回写游戏世界（实体、物品栏、聊天）一律 MinecraftClient.execute() 回主线程。
 * 这样就不会出现"网络卡住整个游戏"的老问题。
 */
public final class Async {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "AICompanion-Worker-" + SEQ.incrementAndGet());
        t.setDaemon(true);
        return t;
    };

    /** 后台工作线程池：HTTP、寻路计算、音频解码都在这里。 */
    public static final ExecutorService POOL = Executors.newFixedThreadPool(4, FACTORY);

    private static final List<Delayed> DELAYED = new ArrayList<>();
    private static long tickCounter = 0L;

    private static final class Delayed {
        final long due;
        final Runnable task;

        Delayed(long due, Runnable task) {
            this.due = due;
            this.task = task;
        }
    }

    private Async() {}

    /** 丢到后台线程。 */
    public static void run(Runnable task) {
        POOL.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                com.aicompanion.AiCompanionMod.LOG.warn("[AICompanion] 后台任务异常: {}", t.toString());
            }
        });
    }

    /** 后台计算，返回 future 给主线程接。 */
    public static <T> CompletableFuture<T> supply(Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, POOL);
    }

    /** 下一帧回主线程执行（安全碰游戏对象）。 */
    public static void main(Runnable task) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        client.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                com.aicompanion.AiCompanionMod.LOG.warn("[AICompanion] 主线程任务异常: {}", t.toString());
            }
        });
    }

    /** 若干 tick 之后回主线程执行（走路、挖掘这类节流用）。 */
    public static void later(long ticks, Runnable task) {
        synchronized (DELAYED) {
            DELAYED.add(new Delayed(tickCounter + Math.max(0, ticks), task));
        }
    }

    /** 由客户端 tick 事件每帧调用。 */
    public static void tick() {
        tickCounter++;
        List<Delayed> ready = null;
        synchronized (DELAYED) {
            if (!DELAYED.isEmpty()) {
                for (int i = DELAYED.size() - 1; i >= 0; i--) {
                    if (DELAYED.get(i).due <= tickCounter) {
                        if (ready == null) {
                            ready = new ArrayList<>();
                        }
                        ready.add(DELAYED.remove(i));
                    }
                }
            }
        }
        if (ready != null) {
            // 反序取出来的，翻回去保持提交顺序
            for (int i = ready.size() - 1; i >= 0; i--) {
                Delayed d = ready.get(i);
                try {
                    d.task.run();
                } catch (Throwable t) {
                    com.aicompanion.AiCompanionMod.LOG.warn("[AICompanion] 延迟任务异常: {}", t.toString());
                }
            }
        }
    }

    // ---------------------------------------------------------------- HTTP

    /** POST JSON，返回响应体字符串（HTTP>=400 时 future 以 RuntimeException 结束）。 */
    public static CompletableFuture<String> postJson(String url, String body, String bearer, int timeoutSeconds) {
        return supply(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(url, "POST", bearer, timeoutSeconds);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
                int code = conn.getResponseCode();
                String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                if (code >= 400) {
                    throw new IOException("HTTP " + code + " <- " + url + " :: " + head(text, 300));
                }
                return text;
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /** POST JSON，返回原始字节（TTS 音频用）。 */
    public static CompletableFuture<byte[]> postBytes(String url, String body, String bearer, int timeoutSeconds) {
        return supply(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(url, "POST", bearer, timeoutSeconds);
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(payload.length);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload);
                }
                int code = conn.getResponseCode();
                if (code >= 400) {
                    throw new IOException("HTTP " + code + " <- " + url + " :: " + head(readAll(conn.getErrorStream()), 300));
                }
                try (InputStream is = conn.getInputStream()) {
                    return readBytes(is);
                }
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /** GET，返回响应体字符串。 */
    public static CompletableFuture<String> get(String url, String bearer, int timeoutSeconds) {
        return supply(() -> {
            HttpURLConnection conn = null;
            try {
                conn = open(url, "GET", bearer, timeoutSeconds);
                int code = conn.getResponseCode();
                String text = readAll(code >= 400 ? conn.getErrorStream() : conn.getInputStream());
                if (code >= 400) {
                    throw new IOException("HTTP " + code + " <- " + url + " :: " + head(text, 300));
                }
                return text;
            } catch (IOException e) {
                throw new RuntimeException(e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
    }

    /** 本机服务探活（例如 Ollama 起了没）。 */
    public static boolean ping(String url, int timeoutSeconds) {
        HttpURLConnection conn = null;
        try {
            conn = open(url, "GET", null, timeoutSeconds);
            return conn.getResponseCode() > 0;
        } catch (IOException e) {
            return false;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    // 内网地址不走代理（走代理反而连不上本地 Ollama / MaidAI）
    private static boolean isLocal(String url) {
        String u = url.toLowerCase();
        return u.contains("//127.0.0.1") || u.contains("//localhost") || u.contains("//[::1]");
    }

    private static HttpURLConnection open(String url, String method, String bearer, int timeoutSeconds) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(8000);
        conn.setReadTimeout(Math.max(5, timeoutSeconds) * 1000);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", "AICompanion/1.0 (Minecraft)");
        if (!"GET".equals(method)) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        }
        if (bearer != null && !bearer.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + bearer);
        }
        return conn;
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) {
            return "";
        }
        try (InputStream in = is) {
            return new String(readBytes(in), StandardCharsets.UTF_8);
        }
    }

    private static byte[] readBytes(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = is.read(buf)) > 0) {
            bos.write(buf, 0, n);
        }
        return bos.toByteArray();
    }

    private static String head(String s, int len) {
        if (s == null) {
            return "(空)";
        }
        return s.length() <= len ? s : s.substring(0, len) + "...";
    }
}
