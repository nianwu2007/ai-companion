package com.aicompanion.llm;

import com.aicompanion.util.Json;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 与 OpenAI 兼容协议相关的小工具：请求体拼装 + 回复抽取。 */
public final class Llm {

    private Llm() {}

    /** 一条消息。 */
    public record Msg(String role, String content) {
        public static Msg system(String c) {
            return new Msg("system", c);
        }

        public static Msg user(String c) {
            return new Msg("user", c);
        }

        public static Msg assistant(String c) {
            return new Msg("assistant", c);
        }
    }

    /** 拼 OpenAI 兼容的 /v1/chat/completions 请求体。 */
    public static String buildBody(String model, List<Msg> messages, double temperature, int maxTokens,
                                   boolean jsonMode) {
        StringBuilder sb = new StringBuilder();
        sb.append('[');
        for (int i = 0; i < messages.size(); i++) {
            Msg m = messages.get(i);
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Json.obj(
                    "role", Json.quote(m.role()),
                    "content", Json.quote(m.content())));
        }
        sb.append(']');

        StringBuilder body = new StringBuilder();
        body.append('{');
        body.append(Json.pair("model", Json.quote(model))).append(',');
        body.append(Json.pair("messages", sb.toString())).append(',');
        body.append(Json.pair("stream", "false")).append(',');
        body.append(Json.pair("temperature", Json.n(temperature))).append(',');
        if (maxTokens > 0) {
            body.append(Json.pair("max_tokens", Json.n(maxTokens))).append(',');
        }
        if (jsonMode) {
            // Ollama 认这个字段；DeepSeek 认 response_format
            body.append(Json.pair("format", Json.quote("json"))).append(',');
            body.append(Json.pair("response_format", Json.obj("type", Json.quote("json_object")))).append(',');
        }
        body.append(Json.pair("keep_alive", Json.quote("30m")));
        body.append('}');
        return body.toString();
    }

    /** 从 OpenAI 兼容响应里取正文。 */
    public static String extractContent(String responseJson) {
        if (responseJson == null || responseJson.isEmpty()) {
            return "";
        }
        try {
            String content = Json.strPath(responseJson, "choices", "0", "message", "content");
            if (content != null && !content.isEmpty()) {
                return content.trim();
            }
        } catch (RuntimeException ignored) {
            // 落到下面的兜底
        }
        // Ollama 原生 /api/chat 的兜底
        try {
            String content = Json.strPath(responseJson, "message", "content");
            if (content != null && !content.isEmpty()) {
                return content.trim();
            }
        } catch (RuntimeException ignored) {
            // 再落到下面
        }
        try {
            String content = Json.strPath(responseJson, "response");
            if (content != null) {
                return content.trim();
            }
        } catch (RuntimeException ignored) {
            // 放弃
        }
        return "";
    }

    /** 从回复里抠出第一段 JSON 对象（本地小模型常带前后废话或 ``` 围栏）。 */
    public static String firstJsonObject(String text) {
        if (text == null) {
            return "";
        }
        String s = text.trim();
        int fence = s.indexOf("```");
        if (fence >= 0) {
            int nl = s.indexOf('\n', fence);
            int endFence = s.indexOf("```", fence + 3);
            if (nl > 0 && endFence > nl) {
                s = s.substring(nl + 1, endFence).trim();
            }
        }
        int start = s.indexOf('{');
        if (start < 0) {
            return "";
        }
        int depth = 0;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (esc) {
                    esc = false;
                } else if (c == '\\') {
                    esc = true;
                } else if (c == '"') {
                    inStr = false;
                }
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return s.substring(start, i + 1);
                }
            }
        }
        return "";
    }

    /** 解析 {"say": "...", "actions": [...]} 里的 actions 数组。 */
    public static List<Action> parseActions(String json) {
        List<Action> out = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return out;
        }
        List<Object> raw;
        try {
            raw = Json.parseArray(Json.strPath(json, "actions"));
        } catch (RuntimeException e) {
            raw = List.of();
        }
        if (raw == null) {
            return out;
        }
        for (Object o : raw) {
            if (!(o instanceof Map<?, ?> map)) {
                continue;
            }
            out.add(Action.fromMap(map));
        }
        return out;
    }

    /** 取出 say 字段（没有就整段当说话内容）。 */
    public static String extractSay(String json, String fallback) {
        try {
            String say = Json.str(json, "say");
            if (say != null && !say.isBlank()) {
                return say.trim();
            }
        } catch (RuntimeException ignored) {
            // 用兜底
        }
        return fallback == null ? "" : fallback.trim();
    }

    /** 列表转成可读文本（塞进 prompt 用）。 */
    public static String join(List<String> items, String sep) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(sep);
            }
            sb.append(items.get(i));
        }
        return sb.toString();
    }
}
