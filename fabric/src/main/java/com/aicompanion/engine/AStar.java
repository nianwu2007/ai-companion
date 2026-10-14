package com.aicompanion.engine;

import net.minecraft.block.BlockState;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * 兜底的 A* —— Baritone 不在场时才用。
 *
 * 就在主线程算，但有节点预算（默认一次 400 个），算不完就认输交给"直线硬冲"，
 * 绝不长时间卡住渲染线程。真要长途跋涉，装 Baritone，它才是专业的。
 */
public final class AStar {

    private AStar() {}

    private static final int[][] DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private static final class Node {
        final BlockPos pos;
        double g;
        double f;
        Node parent;

        Node(BlockPos pos) {
            this.pos = pos;
        }
    }

    /**
     * 找一条从 start 到 goal 的可行走路线（含起终点）。
     * 找不到返回 null。y 方向只允许平走、上一格、下一格。
     */
    public static List<BlockPos> find(ClientWorld world, BlockPos start, BlockPos goal, int maxNodes, int maxHorizontal) {
        if (world == null || start == null || goal == null) {
            return null;
        }
        int budget = maxNodes <= 0 ? 4000 : maxNodes;
        double h = start.getSquaredDistance(goal);
        if (h > (double) maxHorizontal * maxHorizontal) {
            return null; // 太远，交给 Baritone
        }
        BlockPos s = ground(world, start);
        BlockPos t = ground(world, goal);
        if (s == null || t == null) {
            return null;
        }
        if (s.equals(t)) {
            return List.of(s);
        }
        Map<BlockPos, Node> open = new HashMap<>();
        Set<BlockPos> closed = new HashSet<>();
        PriorityQueue<Node> queue = new PriorityQueue<>(Comparator.comparingDouble(n -> n.f));
        Node first = new Node(s);
        first.g = 0;
        first.f = Math.sqrt(s.getSquaredDistance(t));
        open.put(s, first);
        queue.add(first);

        int expanded = 0;
        while (!queue.isEmpty() && expanded < budget) {
            Node cur = queue.poll();
            if (!open.containsKey(cur.pos)) {
                continue;
            }
            open.remove(cur.pos);
            closed.add(cur.pos);
            expanded++;
            if (cur.pos.equals(t) || cur.pos.isWithinDistance(t, 1.5)) {
                return rebuild(cur);
            }
            for (int[] d : DIRS) {
                BlockPos cand = new BlockPos(cur.pos.getX() + d[0], cur.pos.getY(), cur.pos.getZ() + d[1]);
                BlockPos land = land(world, cand, cur.pos);
                if (land == null || closed.contains(land)) {
                    continue;
                }
                boolean diagonal = d[0] != 0 && d[1] != 0;
                if (diagonal && (!walkable(world, cur.pos.add(d[0], 0, 0)) || !walkable(world, cur.pos.add(0, 0, d[1])))) {
                    continue; // 不许贴着墙角穿
                }
                double cost = diagonal ? 1.414 : 1.0;
                cost += Math.abs(land.getY() - cur.pos.getY()) * 0.6; // 上下坡加点代价
                double g = cur.g + cost;
                Node old = open.get(land);
                if (old != null && old.g <= g) {
                    continue;
                }
                Node next = old != null ? old : new Node(land);
                next.parent = cur;
                next.g = g;
                next.f = g + Math.sqrt(land.getSquaredDistance(t));
                open.put(land, next);
                queue.add(next);
            }
        }
        return null;
    }

    private static List<BlockPos> rebuild(Node end) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = end; n != null; n = n.parent) {
            path.add(n.pos);
        }
        Collections.reverse(path);
        return path;
    }

    /** 从 pos 往下找到能站的那一格。 */
    private static BlockPos ground(ClientWorld world, BlockPos pos) {
        if (passable(world, pos) && passable(world, pos.up()) && !passable(world, pos.down())) {
            return pos;
        }
        for (int i = 0; i <= 3; i++) {
            BlockPos p = pos.up(i);
            if (passable(world, p) && passable(world, p.up())) {
                return p;
            }
        }
        return passable(world, pos) ? pos : null;
    }

    /** 目标格能不能站：本格和上一格都通，且脚下有东西。允许往下掉一格。 */
    private static BlockPos land(ClientWorld world, BlockPos pos, BlockPos from) {
        if (standable(world, pos)) {
            return pos;
        }
        BlockPos up = pos.up();
        if (standable(world, up) && passable(world, pos)) {
            return up;
        }
        BlockPos down = pos.down();
        if (standable(world, down) && passable(world, pos) && passable(world, up)) {
            return down;
        }
        return null;
    }

    private static boolean standable(ClientWorld world, BlockPos pos) {
        return passable(world, pos) && passable(world, pos.up()) && !passable(world, pos.down());
    }

    private static boolean walkable(ClientWorld world, BlockPos pos) {
        return passable(world, pos) && passable(world, pos.up());
    }

    private static boolean passable(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) {
            return true;
        }
        if (!state.getFluidState().isEmpty()) {
            return true;
        }
        return !state.blocksMovement();
    }
}
