package com.aicompanion.llm;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一条行动指令 —— LLM 输出的结构化动作，由 PlayerEngine 在主线程执行。
 *
 * 支持的 type：
 *   goto    x z        走到坐标（y 可选，自动选落脚点）
 *   mine    x y z      挖掉这个方块（count 次）
 *   place   x y z      在坐标放物品（item = minecraft:xxx）
 *   build   item       造房子（item 写 cottage 木屋 / villa 别墅）
 *   follow             跟随玩家
 *   stop               停下（清空队列 + 关跟随）
 *   jump               跳一下
 *   hold    item       把物品换到手上
 *   drop    slot count 丢掉背包格子里的东西
 *   use                右键使用 / 吃 / 放
 *   attack             左键打一下（面前的目标）
 *   wait    ticks      歇 N tick
 *   say                由外层单独处理，不在这里
 */
public record Action(String type, double x, double y, double z, String item, int count, int ticks) {

    public static Action of(String type) {
        return new Action(type, 0, 0, 0, "", 1, 20);
    }

    public static Action gotoXZ(int x, int z) {
        return new Action("goto", x, 0, z, "", 1, 20);
    }

    public static Action mine(int x, int y, int z) {
        return new Action("mine", x, y, z, "", 1, 20);
    }

    public static Action place(int x, int y, int z, String item) {
        return new Action("place", x, y, z, item, 1, 20);
    }

    public static Action hold(String item) {
        return new Action("hold", 0, 0, 0, item, 1, 20);
    }

    public static Action wait(int ticks) {
        return new Action("wait", 0, 0, 0, "", 1, ticks);
    }

    /** 从 LLM 给的 JSON 对象里宽容地读动作（字段名大小写、数值/字符串都容忍）。 */
    @SuppressWarnings("unchecked")
    public static Action fromMap(Map<?, ?> map) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : map.entrySet()) {
            m.put(String.valueOf(e.getKey()).toLowerCase(Locale.ROOT), e.getValue());
        }
        String type = str(m.get("type"));
        if (type.isEmpty()) {
            type = str(m.get("action"));
        }
        // 有些人会把动作写成 {"goto": {...}} 或 "goto"
        if (type.isEmpty() && m.size() == 1) {
            type = str(m.keySet().iterator().next());
        }
        type = type.toLowerCase(Locale.ROOT).trim();

        double x = num(m.get("x"), 0);
        double y = num(m.get("y"), num(m.get("dy"), 0));
        double z = num(m.get("z"), 0);
        String item = str(m.get("item"));
        if (item.isEmpty()) {
            item = str(m.get("block"));
        }
        int count = (int) num(m.get("count"), num(m.get("times"), 1));
        int ticks = (int) num(m.get("ticks"), num(m.get("time"), 20));

        // 容错：模型常写 "goto(100,64)" 这种，或者 type 就是整句
        if (type.contains(" ") || type.contains("(")) {
            String t = type.replace('(', ' ').replace(')', ' ').replace(',', ' ').trim();
            String[] parts = t.split("\\s+");
            if (parts.length > 0) {
                type = parts[0];
                if (x == 0 && parts.length > 1) {
                    x = num(parts[1], 0);
                }
                if (z == 0 && parts.length > 2) {
                    z = num(parts[2], 0);
                }
            }
        }
        switch (type) {
            case "walk", "move", "go", "path", "moveto" -> type = "goto";
            case "dig", "break", "breakblock" -> type = "mine";
            case "put", "setblock", "build" -> type = "place";
            case "equip", "switch", "select" -> type = "hold";
            case "toss", "throw" -> type = "drop";
            case "sleep", "idle", "pause" -> type = "wait";
            default -> {
                // 保持原样
            }
        }
        return new Action(type, x, y, z, item, Math.max(1, count), Math.max(1, ticks));
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o).trim();
    }

    private static double num(Object o, double def) {
        if (o instanceof Number n) {
            return n.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(o).trim());
        } catch (RuntimeException e) {
            return def;
        }
    }

    @Override
    public String toString() {
        return switch (type) {
            case "goto" -> "goto " + (int) x + " " + (int) z;
            case "mine" -> "mine " + (int) x + " " + (int) y + " " + (int) z;
            case "place" -> "place " + (int) x + " " + (int) y + " " + (int) z + " " + item;
            case "hold" -> "hold " + item;
            case "wait" -> "wait " + ticks + "t";
            default -> type;
        };
    }
}
