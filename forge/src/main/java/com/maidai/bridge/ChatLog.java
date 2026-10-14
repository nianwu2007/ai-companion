package com.maidai.bridge;

import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Ring buffer of chat lines (incoming game/maid chat + outgoing messages we inject),
 * so the local agent can "hear" the conversation it is steering.
 */
@Mod.EventBusSubscriber(modid = MaidBridgeMod.MODID, value = Dist.CLIENT, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChatLog {

    private static final int MAX = 200;
    private static final Deque<Entry> LOG = new ArrayDeque<>();
    private static final AtomicLong SEQ = new AtomicLong();

    private ChatLog() {
    }

    private record Entry(long id, String who, String text, boolean out, long t) {
    }

    public static synchronized void add(String who, String text, boolean outgoing) {
        if (text == null || text.isEmpty()) return;
        LOG.addLast(new Entry(SEQ.incrementAndGet(), who, text, outgoing, System.currentTimeMillis()));
        while (LOG.size() > MAX) LOG.removeFirst();
    }

    @SubscribeEvent
    public static void onChat(ClientChatReceivedEvent event) {
        try {
            add("game", event.getMessage().getString(), false);
        } catch (Throwable ignored) {
            // never let chat logging break the client
        }
    }

    public static synchronized String dump() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"ok\":true,\"count\":").append(LOG.size()).append(",\"messages\":[");
        boolean first = true;
        for (Entry e : LOG) {
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"id\":").append(e.id)
                    .append(",\"who\":\"").append(Json.esc(e.who))
                    .append("\",\"text\":\"").append(Json.esc(e.text))
                    .append("\",\"out\":").append(e.out)
                    .append(",\"t\":").append(e.t)
                    .append('}');
        }
        return sb.append("]}").toString();
    }
}
