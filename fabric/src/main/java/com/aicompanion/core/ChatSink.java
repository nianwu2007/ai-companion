package com.aicompanion.core;

/** Where the AI's words go. Implemented by the client-side chat layer. */
@FunctionalInterface
public interface ChatSink {
    void send(String text);
}
