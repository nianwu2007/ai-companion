package com.maidai.bridge;

import com.mojang.logging.LogUtils;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Local-only HTTP control surface.
 *
 *   GET  /ping | /health        liveness
 *   GET  /state                 player / world / maids / threat snapshot
 *   GET  /mods?q=               loaded mod list
 *   GET  /chatlog               ring buffer of chat lines (in + out)
 *   POST /chat   {"text":...}   send chat (leading "/" becomes a command)
 *   POST /cmd    {"text":"/..."} send a command
 *   POST /echo   {"text":...}   print a client-side chat line
 *   POST /baritone {"cmd":"goto 100 64 200"}   raw Baritone command (without '#')
 *   POST /goto   {"x":..,"y":..,"z":..}        shorthand for "#goto"
 *   POST /follow {"player":"name"}             shorthand for "#follow player"
 *   POST /stop                                 shorthand for "#stop"
 */
public final class HttpApi {

    private static final Logger LOG = LogUtils.getLogger();
    private static final int DEFAULT_PORT = 8790;
    private static final int CLIENT_TIMEOUT_S = 5;

    private final HttpServer server;
    private final int port;
    private final String token;
    private final boolean disablePauseOnLostFocus;

    public HttpApi() throws IOException {
        int cfgPort = DEFAULT_PORT;
        String cfgToken = "";
        boolean cfgNoPause = true;

        Path cfg = FMLPaths.CONFIGDIR.get().resolve("maidai_bridge.json");
        if (Files.isRegularFile(cfg)) {
            try {
                String s = Files.readString(cfg, StandardCharsets.UTF_8);
                Integer p = Json.intField(s, "port");
                if (p != null && p > 0 && p < 65536) cfgPort = p;
                String t = Json.strArg(s, "token");
                if (t != null) cfgToken = t;
                Boolean np = Json.boolField(s, "disablePauseOnLostFocus");
                if (np != null) cfgNoPause = np;
            } catch (Throwable t) {
                LOG.warn("[MaidBridge] bad config {}, using defaults", cfg, t);
            }
        }

        this.port = cfgPort;
        this.token = cfgToken == null ? "" : cfgToken;
        this.disablePauseOnLostFocus = cfgNoPause;
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", this.port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        this.server.createContext("/", this::dispatch);
    }

    public int port() {
        return port;
    }

    public void start() {
        server.start();
        if (disablePauseOnLostFocus) {
            // Automation dies if the integrated server pauses whenever the window loses focus.
            Minecraft.getInstance().execute(() -> {
                try {
                    Minecraft.getInstance().options.pauseOnLostFocus = false;
                } catch (Throwable ignored) {
                }
            });
        }
    }

    private void dispatch(HttpExchange ex) {
        String path = ex.getRequestURI().getPath();
        try {
            if (!token.isEmpty()) {
                String got = ex.getRequestHeaders().getFirst("X-Token");
                if (!token.equals(got)) {
                    json(ex, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                    return;
                }
            }

            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            switch (path) {
                case "/ping", "/health" -> json(ex, 200,
                        "{\"ok\":true,\"mod\":\"maidai_bridge\",\"version\":\"" + MaidBridgeMod.VERSION
                                + "\",\"port\":" + port + "}");

                case "/state" -> json(ex, 200, onClient(GameState::dump));

                case "/mods" -> {
                    String q = ex.getRequestURI().getQuery();
                    String needle = "";
                    if (q != null) {
                        int i = q.indexOf("q=");
                        if (i >= 0) needle = q.substring(i + 2);
                    }
                    json(ex, 200, GameState.mods(needle));
                }

                case "/chatlog" -> json(ex, 200, ChatLog.dump());

                case "/chat", "/cmd" -> {
                    String text = Json.strArg(body, "text");
                    if (text.isEmpty()) {
                        json(ex, 400, "{\"ok\":false,\"error\":\"missing text\"}");
                        return;
                    }
                    json(ex, 200, onClient(() -> sendChat(text)));
                }

                case "/echo" -> {
                    String text = Json.strArg(body, "text");
                    json(ex, 200, onClient(() -> echo(text)));
                }

                case "/baritone" -> {
                    String raw = Json.strArg(body, "cmd");
                    if (raw.isEmpty()) raw = Json.strArg(body, "command");
                    if (raw.isEmpty()) {
                        json(ex, 400, "{\"ok\":false,\"error\":\"missing cmd\"}");
                        return;
                    }
                    if (raw.startsWith("#")) raw = raw.substring(1);
                    String finalRaw = raw;
                    json(ex, 200, onClient(() -> sendChat("#" + finalRaw)));
                }

                case "/goto" -> {
                    Double x = Json.numArg(body, "x");
                    Double y = Json.numArg(body, "y");
                    Double z = Json.numArg(body, "z");
                    if (x == null || z == null) {
                        json(ex, 400, "{\"ok\":false,\"error\":\"need x and z (y optional)\"}");
                        return;
                    }
                    Minecraft mc = Minecraft.getInstance();
                    double gy = y != null ? y : (mc.player != null ? mc.player.getY() : 64);
                    String cmd = "#goto " + Json.n(x) + " " + Json.n(gy) + " " + Json.n(z);
                    json(ex, 200, onClient(() -> sendChat(cmd)));
                }

                case "/follow" -> {
                    String who = Json.strArg(body, "player");
                    if (who.isEmpty()) who = Json.strArg(body, "name");
                    if (who.isEmpty()) {
                        json(ex, 400, "{\"ok\":false,\"error\":\"missing player\"}");
                        return;
                    }
                    String cmd = "#follow player " + who;
                    json(ex, 200, onClient(() -> sendChat(cmd)));
                }

                case "/stop" -> json(ex, 200, onClient(() -> sendChat("#stop")));

                default -> json(ex, 404, "{\"ok\":false,\"error\":\"no route: " + Json.esc(path) + "\"}");
            }
        } catch (Throwable t) {
            LOG.warn("[MaidBridge] request {} failed", path, t);
            try {
                json(ex, 500, "{\"ok\":false,\"error\":\"" + Json.esc(String.valueOf(t)) + "\"}");
            } catch (IOException ignored) {
            }
        } finally {
            ex.close();
        }
    }

    /** Run a task on the game (client) thread and wait for its JSON result. */
    private static String onClient(Callable<String> task) throws Exception {
        Minecraft mc = Minecraft.getInstance();
        CompletableFuture<String> f = new CompletableFuture<>();
        mc.execute(() -> {
            try {
                f.complete(task.call());
            } catch (Throwable t) {
                f.completeExceptionally(t);
            }
        });
        return f.get(CLIENT_TIMEOUT_S, TimeUnit.SECONDS);
    }

    private static String sendChat(String text) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || p.connection == null) {
            return "{\"ok\":false,\"error\":\"not in a world\"}";
        }
        if (text.startsWith("/")) {
            p.connection.sendCommand(text.substring(1));
        } else {
            p.connection.sendChat(text);
        }
        ChatLog.add("me", text, true);
        return "{\"ok\":true,\"sent\":\"" + Json.esc(text) + "\"}";
    }

    private static String echo(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gui == null || mc.gui.getChat() == null) {
            return "{\"ok\":false,\"error\":\"chat gui unavailable\"}";
        }
        mc.gui.getChat().addMessage(Component.literal(text));
        ChatLog.add("echo", text, false);
        return "{\"ok\":true}";
    }

    private static void json(HttpExchange ex, int code, String body) throws IOException {
        byte[] out = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }
}
