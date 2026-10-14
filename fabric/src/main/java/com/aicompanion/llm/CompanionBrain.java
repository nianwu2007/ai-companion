package com.aicompanion.llm;

import com.aicompanion.config.AiConfig;
import com.aicompanion.core.Async;
import com.aicompanion.core.State;
import com.aicompanion.util.Json;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 大脑路由 —— 决定一条消息走云端还是本地，并把回复拆成「说话 + 动作」。
 *
 * 规则来自需求：
 *   · 只有 @AI 开头的消息才走 DeepSeek（云端聊天），并受 15 秒节流
 *   · 其它一切（含行动规划）走本地 Ollama
 *   · 节流窗口内的 @AI 消息自动降级到本地
 *
 * 全程异步：返回 CompletableFuture，结果已在后台线程，回主线程由调用方负责。
 */
public final class CompanionBrain {

    private CompanionBrain() {}

    /** 一次处理的结果。 */
    public record Result(String say, List<Action> actions, boolean cloud) {
        public boolean hasSay() {
            return say != null && !say.isBlank();
        }
    }

    /** 说话出口：客户端聊天栏 + TTS。 */
    @FunctionalInterface
    public interface Speaker {
        void say(String text);
    }

    private static final Deque<Llm.Msg> HISTORY = new ArrayDeque<>();
    private static long lastCloudAt = 0L;
    private static final Object LOCK = new Object();

    /** 行动规划的 system prompt：只认 JSON，别啰嗦。 */
    private static final String ACTION_SYSTEM = """
            你是一个 Minecraft 玩家同伴的「行动大脑」。根据玩家的话和当前状态，决定下一步做什么。
            只输出一个 JSON 对象，不要解释、不要 markdown 代码块，格式：
            {"say":"对玩家说的一句中文（可空）","actions":[{"type":"...","x":0,"y":0,"z":0,"item":"...","count":0,"ticks":0}]}
            actions 可用类型：
            goto(x,z) 走到坐标 | mine(x,y,z) 挖方块 | place(x,y,z,item) 放方块 | follow 跟着玩家
            stop 停下 | jump 跳 | hold(item) 换手持 | drop(count) 丢东西 | use 用一下手上物品
            attack 打最近的怪 | wait(ticks) 等一会儿 | say 只说一句话（不写进 actions，写进 say 字段）
            坐标只能用状态里给出的真实坐标；不确定就 follow 或 wait。最多 3 个动作，按顺序执行。
            """;

    public static boolean isCloudMessage(String message) {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        String p = c.aiPrefix == null || c.aiPrefix.isBlank() ? "@AI" : c.aiPrefix;
        return message != null && message.trim().regionMatches(true, 0, p, 0, p.length());
    }

    /** 去掉开头的 @AI 前缀。 */
    public static String stripPrefix(String message) {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        String p = c.aiPrefix == null || c.aiPrefix.isBlank() ? "@AI" : c.aiPrefix;
        String s = message == null ? "" : message.trim();
        if (s.regionMatches(true, 0, p, 0, p.length())) {
            s = s.substring(p.length()).trim();
        }
        return s;
    }

    /** 节流判定：true = 云端额度可用（同时占坑）。 */
    private static boolean takeCloudSlot() {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (now - lastCloudAt < Math.max(0, c.throttleSeconds) * 1000L) {
                return false;
            }
            lastCloudAt = now;
            return true;
        }
    }

    /**
     * 异步处理一条玩家消息。返回的 future 完成在后台线程；
     * 调用方用 Async.main(...) 回主线程做聊天栏/TTS/动作派发。
     */
    public static CompletableFuture<Result> handleAsync(String sender, String rawMessage) {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        boolean wantsCloud = isCloudMessage(rawMessage);
        String text = stripPrefix(rawMessage);
        if (text.isBlank()) {
            text = "你好？";
        }

        final String userText = text;
        boolean useCloud = wantsCloud && c.allowDeepSeek && takeCloudSlot() && LlmClient.hasDeepSeekKey();
        if (useCloud) {
            List<Llm.Msg> msgs = buildChatMessages(sender, userText);
            return LlmClient.askDeepSeek(msgs).handle((reply, err) -> {
                if (err != null || reply == null || reply.isBlank()) {
                    // 云端挂了就退回本地，别让玩家等
                    return new Result(localFallback(sender, userText), List.of(), false);
                }
                // 云端回复同样按 JSON 解析：拆出「说话」和「动作」。
                // 以前这里直接 new Result(reply, List.of(), true)，动作恒为空 → 小怜只会把 JSON 念在聊天栏里。
                String json = Llm.firstJsonObject(reply);
                List<Action> actions = new ArrayList<>();
                String say = reply;
                if (json != null && !json.isBlank()) {
                    List<Action> parsed = Llm.parseActions(json);
                    String s = Llm.extractSay(json, "");
                    if (s.isBlank()) {
                        try {
                            s = Json.str(json, "text");   // 兼容旧版单数格式 {"action":"say","text":"…"}
                        } catch (RuntimeException ignored) {
                            s = "";
                        }
                    }
                    if (s == null) {
                        s = "";
                    }
                    if (!parsed.isEmpty() || !s.isBlank()) {
                        actions.addAll(parsed);
                        say = s;
                    }
                }
                if (actions.size() > 6) {
                    actions = new ArrayList<>(actions.subList(0, 6));
                }
                remember(Llm.Msg.user(userText));
                if (!say.isBlank()) {
                    remember(Llm.Msg.assistant(say));
                }
                return new Result(say, actions, true);
            });
        }

        // 本地：既说话也出动作
        return planLocally(sender, text);
    }

    // ---------------------------------------------------------------- 本地行动规划

    private static CompletableFuture<Result> planLocally(String sender, String text) {
        String user = "当前状态：\n" + State.snapshot() + "\n\n玩家" + sender + "说：" + text;
        return LlmClient.askOllama(ACTION_SYSTEM, user, true).handle((reply, err) -> {
            if (err != null || reply == null || reply.isBlank()) {
                return new Result("(本地模型没响应，检查 Ollama 是否在 " + AiConfig.INSTANCE.llm.ollamaUrl + " 上跑着)", List.of(), false);
            }
            String json = Llm.firstJsonObject(reply);
            List<Action> actions = new ArrayList<>();
            String say = reply;
            if (json != null) {
                actions.addAll(Llm.parseActions(json));
                say = Llm.extractSay(json, "");
                if (say.isBlank()) {
                    say = "";
                }
            }
            synchronized (LOCK) {
                if (actions.size() > 6) {
                    actions = new ArrayList<>(actions.subList(0, 6));
                }
            }
            remember(Llm.Msg.user(text));
            if (!say.isBlank()) {
                remember(Llm.Msg.assistant(say));
            }
            return new Result(say, actions, false);
        });
    }

    /** 云端不可用时的本地兜底回答。 */
    private static String localFallback(String sender, String text) {
        try {
            String reply = LlmClient.askOllama(
                    "你是主人身边的狐娘女仆小怜，用中文口语短句回答，一两句就够，不要加括号里的动作描写。",
                    "玩家" + sender + "说：" + text, false).get();
            return reply == null || reply.isBlank() ? "……我在。" : reply;
        } catch (Exception e) {
            return "……我在。";
        }
    }

    // ---------------------------------------------------------------- 对话历史

    private static List<Llm.Msg> buildChatMessages(String sender, String text) {
        List<Llm.Msg> msgs = new ArrayList<>();
        msgs.add(Llm.Msg.system(AiConfig.INSTANCE.llm.systemPrompt));
        synchronized (LOCK) {
            msgs.addAll(HISTORY);
        }
        msgs.add(Llm.Msg.user("玩家" + sender + "说：" + text));
        return msgs;
    }

    private static void remember(Llm.Msg msg) {
        AiConfig.Llm c = AiConfig.INSTANCE.llm;
        int keep = Math.max(2, c.historyTurns) * 2;
        synchronized (LOCK) {
            HISTORY.addLast(msg);
            while (HISTORY.size() > keep) {
                HISTORY.removeFirst();
            }
        }
    }

    public static void clearHistory() {
        synchronized (LOCK) {
            HISTORY.clear();
        }
    }

    /** 供设置/调试：把回复里说出来的部分播出去（聊天栏 + TTS）。 */
    public static void speak(Speaker speaker, Result result) {
        if (speaker != null && result.hasSay()) {
            speaker.say(result.say);
        }
    }

    /** 主线程友好入口：处理完自动把话交给 speaker，把动作交给 actions 消费者。 */
    public static void handle(String sender, String rawMessage, Speaker speaker, java.util.function.Consumer<Result> after) {
        handleAsync(sender, rawMessage).thenAccept(r -> Async.main(() -> {
            if (speaker != null && r.hasSay()) {
                speaker.say(r.say);
            }
            if (after != null) {
                after.accept(r);
            }
        }));
    }
}
