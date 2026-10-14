package com.aicompanion.api;

import com.aicompanion.config.AiConfig;
import com.aicompanion.core.Async;
import com.aicompanion.util.Json;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 本机控制端口（默认 127.0.0.1:8791）—— 让外部脚本/其他 LLM 指挥同伴。
 *
 *   GET  /ping /health /state
 *   POST /chat {"msg":"@AI 你好","from":"player"}   → 走完整大脑链路
 *   POST /cmd  {"cmd":"goto","x":10,"z":-5}         → 直接下一条动作
 *   POST /bare {"path":"...","body":"..."}          → 原样转发给上层 Dispatcher
 *
 * 真正干活必须回主线程，所以这里只做 I/O，业务交给 Dispatcher。
 */
public final class ActionServer {

    private ActionServer() {}

    /** 主线程执行器：拿到请求，返回一根 future。 */
    @FunctionalInterface
    public interface Dispatcher {
        CompletableFuture<String> dispatch(String path, String body);
    }

    private static HttpServer server;
    private static Dispatcher dispatcher;

    public static synchronized boolean start(Dispatcher d) {
        AiConfig.Api cfg = AiConfig.INSTANCE.api;
        if (!cfg.enabled) {
            return false;
        }
        if (server != null) {
            return true;
        }
        dispatcher = d;
        try {
            server = HttpServer.create(new InetSocketAddress(cfg.bind, cfg.port), 0);
            server.createContext("/", ActionServer::route);
            server.setExecutor(Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "AICompanion-Api");
                t.setDaemon(true);
                return t;
            }));
            server.start();
            return true;
        } catch (IOException e) {
            System.err.println("[aicompanion] 控制端口起不来: " + e.getMessage());
            server = null;
            return false;
        }
    }

    public static synchronized void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    // ------------------------------------------------------------------ 路由

    private static void route(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            String body = readBody(ex);
            if (!tokenOk(ex)) {
                write(ex, 403, "{\"error\":\"bad token\"}");
                return;
            }
            switch (path) {
                case "/ping", "/health" -> write(ex, 200, "{\"ok\":true,\"who\":\"aicompanion\"}");
                case "/state" -> write(ex, 200, Json.obj(
                        Json.pair("state", Json.quote(com.aicompanion.core.State.snapshot()))));
                default -> {
                    Dispatcher d = dispatcher;
                    if (d == null) {
                        write(ex, 503, "{\"error\":\"dispatcher not ready\"}");
                        return;
                    }
                    CompletableFuture<String> fut = d.dispatch(path, body);
                    if (fut == null) {
                        write(ex, 503, "{\"error\":\"no handler\"}");
                        return;
                    }
                    try {
                        String out = fut.get(180, TimeUnit.SECONDS);
                        write(ex, 200, out == null || out.isBlank() ? "{\"ok\":true}" : out);
                    } catch (Exception e) {
                        write(ex, 500, Json.obj(Json.pair("error", Json.quote(String.valueOf(e.getMessage())))));
                    }
                }
            }
        } catch (RuntimeException e) {
            try {
                write(ex, 500, Json.obj(Json.pair("error", Json.quote(String.valueOf(e.getMessage())))));
            } catch (IOException ignored) {
                // 连接已断
            }
        } finally {
            ex.close();
        }
    }

    private static boolean tokenOk(HttpExchange ex) {
        String want = AiConfig.INSTANCE.api.token;
        if (want == null || want.isBlank()) {
            return true;
        }
        String got = ex.getRequestHeaders().getFirst("X-Token");
        if (got == null) {
            got = ex.getRequestHeaders().getFirst("Authorization");
        }
        return want.equals(got);
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream in = ex.getRequestBody()) {
            byte[] buf = in.readAllBytes();
            return new String(buf, StandardCharsets.UTF_8);
        }
    }

    private static void write(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    /** 便捷：把 dispatch 的活包成主线程任务。 */
    public static CompletableFuture<String> onMain(java.util.function.Supplier<String> work) {
        CompletableFuture<String> fut = new CompletableFuture<>();
        Async.main(() -> {
            try {
                fut.complete(work.get());
            } catch (Throwable t) {
                fut.completeExceptionally(t);
            }
        });
        return fut;
    }
}
