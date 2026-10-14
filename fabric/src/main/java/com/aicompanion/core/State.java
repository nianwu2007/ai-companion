package com.aicompanion.core;

/**
 * 全局状态出口：让 llm 层能读到「同伴现在在哪、在干嘛」，而不必反向依赖 engine 包。
 * PlayerEngine 每次 tick 刷新这里，CompanionBrain 拼 prompt 时取用。
 */
public final class State {

    private State() {}

    /** 谁来提供状态快照。 */
    public interface Provider {
        String snapshot();
    }

    private static volatile Provider provider = () -> "";
    private static volatile String cached = "";

    public static void setProvider(Provider p) {
        provider = p == null ? () -> "" : p;
    }

    /** 现取一份状态文本（一行行人话，直接塞进 prompt）。 */
    public static String snapshot() {
        try {
            String s = provider.snapshot();
            cached = s == null ? "" : s;
            return cached;
        } catch (RuntimeException e) {
            return cached;
        }
    }
}
