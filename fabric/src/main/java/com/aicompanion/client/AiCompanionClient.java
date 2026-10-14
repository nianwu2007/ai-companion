package com.aicompanion.client;

import com.aicompanion.AiCompanionMod;
import com.aicompanion.api.ActionServer;
import com.aicompanion.client.render.CompanionEntityRenderer;
import com.aicompanion.client.render.CompanionFoxModel;
import com.aicompanion.client.render.CompanionModelLayers;
import com.aicompanion.config.AiConfig;
import com.aicompanion.core.Async;
import com.aicompanion.core.State;
import com.aicompanion.engine.BaritoneBridge;
import com.aicompanion.engine.CompanionHandle;
import com.aicompanion.engine.PlayerEngine;
import com.aicompanion.llm.Action;
import com.aicompanion.llm.CompanionBrain;
import com.aicompanion.llm.LlmClient;
import com.aicompanion.registry.ModEntities;
import com.aicompanion.util.Json;
import com.aicompanion.voice.Voice;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * 客户端总装线：把各个零件接起来。
 *
 * 这里只做"接线和按键"，不含任何业务逻辑——
 *   实体渲染 ← CompanionEntityRenderer
 *   身体   ← PlayerEngine（驱动本机玩家）
 *   嘴巴耳朵 ← ChatWatcher → CompanionBrain → Voice
 *   外部遥控 ← ActionServer(127.0.0.1:8791)
 */
public class AiCompanionClient implements ClientModInitializer {

    private static final String CATEGORY = "category.aicompanion";

    private static KeyBinding KEY_TALK;
    private static KeyBinding KEY_TOGGLE;
    private static KeyBinding KEY_STOP;
    private static KeyBinding KEY_STATUS;

    private static boolean announced = false;

    @Override
    public void onInitializeClient() {
        // ---- 渲染注册
        EntityModelLayerRegistry.registerModelLayer(CompanionModelLayers.FOX_PARTS,
                CompanionFoxModel::getTexturedModelData);
        EntityRendererRegistry.register(ModEntities.AI_COMPANION, CompanionEntityRenderer::new);

        // ---- 按键（默认 V 说话、B 召唤/收回、N 停下、M 状态）
        KEY_TALK = bind("talk", GLFW.GLFW_KEY_V);
        KEY_TOGGLE = bind("toggle", GLFW.GLFW_KEY_B);
        KEY_STOP = bind("stop", GLFW.GLFW_KEY_N);
        KEY_STATUS = bind("status", GLFW.GLFW_KEY_M);

        // ---- 把"身体"接上说话/播报出口
        PlayerEngine.INSTANCE.install();
        State.setProvider(PlayerEngine.INSTANCE::snapshot);
        PlayerEngine.INSTANCE.setOutput(AiCompanionClient::say,
                text -> {
                    if (AiConfig.INSTANCE.tts.enabled) {
                        Voice.speakAsync(text);
                    }
                });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> onJoin(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> onLeave(client));

        AiCompanionMod.LOG.info("[AI同伴] 客户端就绪。按键：V 说话 / B 召唤 / N 停下 / M 状态");
    }

    private static KeyBinding bind(String name, int code) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.aicompanion." + name, InputUtil.Type.KEYSYM, code, CATEGORY));
    }

    // ------------------------------------------------------------------ 进服 / 退服

    private void onJoin(MinecraftClient mc) {
        AiConfig cfg = AiConfig.INSTANCE;
        if (mc.runDirectory != null) {
            cfg.llm.gameDir = mc.runDirectory.getAbsolutePath();
        }
        if (cfg.companion.autoSpawn) {
            CompanionHandle.ensure(mc);
        }
        boolean api = ActionServer.start(AiCompanionClient::dispatch);
        if (!announced) {
            announced = true;
            say("老规矩：@AI 开头的我认真想（云端），别的我随口接（本地）。");
            if (api) {
                AiCompanionMod.LOG.info("[AI同伴] 控制端点 http://{}:{}/", cfg.api.bind, cfg.api.port);
            }
            AiCompanionMod.LOG.info("[AI同伴] Ollama={} DeepSeek key={} 语音输入={} 语音输出={}",
                    LlmClient.ollamaAlive() ? "在线" : "离线",
                    LlmClient.hasDeepSeekKey() ? "已配置" : "未配置(只走本地)",
                    cfg.stt.enabled ? "开" : "关", cfg.tts.enabled ? "开" : "关");
        }
    }

    private void onLeave(MinecraftClient mc) {
        CompanionHandle.remove(mc);
        ActionServer.stop();
        announced = false;
    }

    // ------------------------------------------------------------------ 每 tick

    private void onTick(MinecraftClient mc) {
        Async.tick();
        if (mc.player == null || mc.world == null) {
            return;
        }
        if (AiConfig.INSTANCE.companion.autoSpawn) {
            CompanionHandle.ensure(mc);
        }
        CompanionHandle.tick(mc);
        PlayerEngine.INSTANCE.tick(mc);

        while (KEY_TALK.wasPressed()) {
            toggleListening();
        }
        while (KEY_TOGGLE.wasPressed()) {
            toggleCompanion(mc);
        }
        while (KEY_STOP.wasPressed()) {
            stopEverything();
        }
        while (KEY_STATUS.wasPressed()) {
            reportStatus();
        }

        for (String note : PlayerEngine.INSTANCE.drainNotes()) {
            say(note);
        }
    }

    // ------------------------------------------------------------------ 按键动作

    private void toggleListening() {
        AiConfig.Stt cfg = AiConfig.INSTANCE.stt;
        if (Voice.isListening()) {
            Voice.stopListening();
            return;
        }
        if (!Voice.ensureVosk()) {
            say("语音输入还没装好：Vosk 中文模型要放在 " + cfg.voskModelPath + "，然后把 config 里 stt.enabled 打开。");
            return;
        }
        Voice.startListening(text -> {
            say("（听到）" + text);
            ChatWatcher.onPlayerSent(text); // 语音当作你说的话，走同一套大脑
        });
        say("在听……再按一次 V 结束。");
    }

    private void toggleCompanion(MinecraftClient mc) {
        if (CompanionHandle.get(mc) != null) {
            CompanionHandle.remove(mc);
            PlayerEngine.INSTANCE.stopAll();
            say("我先退到一边。");
        } else {
            CompanionHandle.ensure(mc);
            say("我在这儿。");
        }
    }

    private void stopEverything() {
        PlayerEngine.INSTANCE.stopAll();
        BaritoneBridge.stop();
        PlayerEngine.clearIntent();
        say("停手了。");
    }

    private void reportStatus() {
        StringBuilder sb = new StringBuilder("[同伴] ");
        sb.append(CompanionHandle.pos(MinecraftClient.getInstance())).append("｜");
        sb.append(PlayerEngine.INSTANCE.busy() ? "手上有活" : "闲着").append("｜");
        String last = PlayerEngine.INSTANCE.lastNote();
        if (last != null && !last.isBlank()) {
            sb.append(last);
        }
        say(sb.toString());
    }

    // ------------------------------------------------------------------ 聊天栏出口

    private static void say(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        ChatWatcher.noteCompanionLine(text);
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud == null) {
            AiCompanionMod.LOG.info("[AI同伴] {}", text);
            return;
        }
        mc.inGameHud.getChatHud().addMessage(Text.literal("<" + AiConfig.INSTANCE.companion.name + "> " + text));
    }

    // ------------------------------------------------------------------ 本机控制端点

    private static CompletableFuture<String> dispatch(String path, String body) {
        return switch (path) {
            case "/", "/ping" -> CompletableFuture.completedFuture(
                    "{\"ok\":true,\"mod\":\"aicompanion\",\"version\":\"" + AiCompanionMod.VERSION + "\"}");
            case "/health", "/status" -> ActionServer.onMain(AiCompanionClient::healthJson);
            case "/state", "/bare" -> ActionServer.onMain(() -> PlayerEngine.INSTANCE.snapshot());
            case "/chat", "/say" -> chatRequest(body);
            case "/cmd", "/do" -> cmdRequest(body);
            default -> CompletableFuture.completedFuture("{\"ok\":false,\"error\":\"no such path\"}");
        };
    }

    private static String healthJson() {
        AiConfig cfg = AiConfig.INSTANCE;
        MinecraftClient mc = MinecraftClient.getInstance();
        return "{\"ok\":true"
                + ",\"companion\":\"" + Json.quote(cfg.companion.name).replace("\"", "") + "\""
                + ",\"inWorld\":" + (mc.world != null)
                + ",\"companionEntity\":" + (mc.world != null && CompanionHandle.get(mc) != null)
                + ",\"busy\":" + PlayerEngine.INSTANCE.busy()
                + ",\"ollama\":" + LlmClient.ollamaAlive()
                + ",\"deepseekKey\":" + LlmClient.hasDeepSeekKey()
                + ",\"baritone\":" + BaritoneBridge.available()
                + ",\"listening\":" + Voice.isListening()
                + ",\"tts\":\"" + cfg.tts.provider + "\"}";
    }

    private static CompletableFuture<String> chatRequest(String body) {
        String text = extract(body, "message", "text", "say", "content");
        if (text.isBlank()) {
            return CompletableFuture.completedFuture("{\"ok\":false,\"error\":\"empty message\"}");
        }
        return CompanionBrain.handleAsync("外部", text).thenApply(r -> {
            if (!r.actions().isEmpty()) {
                PlayerEngine.INSTANCE.submitAll(r.actions());
            }
            return "{\"ok\":true,\"say\":" + Json.quote(r.say())
                    + ",\"actions\":" + r.actions().size()
                    + ",\"cloud\":" + r.cloud() + "}";
        });
    }

    private static CompletableFuture<String> cmdRequest(String body) {
        Map<String, Object> map;
        try {
            map = Json.parseObject(body);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture("{\"ok\":false,\"error\":\"bad json\"}");
        }
        Action a;
        try {
            a = Action.fromMap(map);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture("{\"ok\":false,\"error\":\"bad action\"}");
        }
        PlayerEngine.INSTANCE.submitAsync(a);
        return CompletableFuture.completedFuture("{\"ok\":true,\"queued\":\"" + a.type() + "\"}");
    }

    /** 从 JSON 体里挑出第一个非空字段；不是 JSON 就把整个 body 当消息。 */
    private static String extract(String body, String... keys) {
        if (body == null) {
            return "";
        }
        String raw = body.trim();
        if (raw.startsWith("{")) {
            try {
                Map<String, Object> map = Json.parseObject(raw);
                for (String k : keys) {
                    Object v = map.get(k);
                    if (v instanceof String s && !s.isBlank()) {
                        return s;
                    }
                }
            } catch (RuntimeException ignored) {
                // 落到下面按纯文本处理
            }
            return "";
        }
        return raw;
    }
}
