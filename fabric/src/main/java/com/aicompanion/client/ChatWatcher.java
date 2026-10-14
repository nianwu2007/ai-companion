package com.aicompanion.client;

import com.aicompanion.config.AiConfig;
import com.aicompanion.engine.PlayerEngine;
import com.aicompanion.llm.CompanionBrain;
import com.aicompanion.voice.Voice;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 聊天监听：把聊天栏里的话接给 CompanionBrain。
 *
 * 两条入口——
 *   onDisplayed()    聊天栏显示了什么（进服玩家说的话，还有服务端回显）
 *   onPlayerSent()   你自己敲出去的话（本地单机时服务器不一定回显）
 * 服务端回显会和"自己刚发的"重叠，用 3 秒窗口去重。
 *
 * 回话门规：默认只理 @AI 开头的消息（走 DeepSeek）；
 * 想让同伴接住所有闲聊，把 config 里 llm.respondToAllChat 打开（走本地 Ollama，不花钱）。
 */
public final class ChatWatcher {

    private ChatWatcher() {}

    private static final Pattern PLAYER_LINE = Pattern.compile("^<([^>]{1,32})>\\s?(.*)$");

    private static String lastSent = "";
    private static long lastSentAt = 0L;
    /** 防回声：同伴自己说的话别再喂回去。 */
    private static String lastCompanion = "";

    public static void onPlayerSent(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        lastSent = content.trim();
        lastSentAt = System.currentTimeMillis();
        dispatch(MinecraftClient.getInstance().getSession().getUsername(), lastSent, true);
    }

    public static void onDisplayed(String line) {
        if (line == null || line.isBlank()) {
            return;
        }
        if (!lastCompanion.isEmpty() && line.contains(lastCompanion)) {
            return; // 自己的回声
        }
        String sender = "";
        String text = line.trim();
        Matcher m = PLAYER_LINE.matcher(text);
        if (m.matches()) {
            sender = m.group(1);
            text = m.group(2).trim();
        } else {
            return; // 系统提示（加入/退出/成就）不搭理
        }
        if (sender.equals(MinecraftClient.getInstance().getSession().getUsername())) {
            boolean echo = text.equals(lastSent) && System.currentTimeMillis() - lastSentAt < 3000L;
            if (echo) {
                return; // 自己刚发的那句，onPlayerSent 已经处理过了
            }
        }
        dispatch(sender, text, false);
    }

    /** 同伴说出去的话（记一笔，用来防回声 + 让状态快照知道）。 */
    public static void noteCompanionLine(String text) {
        if (text != null && !text.isBlank()) {
            lastCompanion = text.trim();
        }
    }

    private static void dispatch(String sender, String message, boolean mine) {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        boolean cloud = CompanionBrain.isCloudMessage(message);
        if (!cloud && !c.respondToAllChat && !mine) {
            return; // 别人的闲聊，除非开了 respondToAllChat，否则不插嘴
        }
        if (!cloud && !c.respondToAllChat && mine) {
            return; // 你自己开的闲聊，也要 respondToAllChat 才接
        }
        CompanionBrain.handle(sender, message, ChatWatcher::speak, result -> {
            if (!result.actions().isEmpty()) {
                PlayerEngine.INSTANCE.submitAll(result.actions());
            }
        });
    }

    private static void speak(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        noteCompanionLine(text);
        MinecraftClient mc = MinecraftClient.getInstance();
        String name = AiConfig.INSTANCE.companion.name;
        if (mc.inGameHud != null) {
            Text line = Text.literal("<" + name + "> " + text);
            mc.inGameHud.getChatHud().addMessage(line);
        }
        if (AiConfig.INSTANCE.tts.enabled) {
            Voice.speakAsync(text);
        }
    }
}
