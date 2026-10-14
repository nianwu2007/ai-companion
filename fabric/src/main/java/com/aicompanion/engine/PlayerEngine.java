package com.aicompanion.engine;

import com.aicompanion.config.AiConfig;
import com.aicompanion.core.Async;
import com.aicompanion.core.ChatSink;
import com.aicompanion.core.State;
import com.aicompanion.entity.AICompanionEntity;
import com.aicompanion.llm.Action;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * 身体。
 *
 * 设计取舍（重要）：只有驱动**本地真实玩家**，挖矿/放置/攻击才会被服务端认可；
 * 所以我们不另造一个假玩家去动，而是"借用"本机玩家的身体。
 * AICompanionEntity 是同伴的可视化载体（有个实体的样子、能显示名字、有物品栏数据），
 * PlayerEngine 才是把手脚接上去的那层。
 *
 * 一切都在客户端 tick 里推进（主线程），HTTP 与 Baritone 寻路在自己的线程上跑完再回来：
 * 主线程永不阻塞 —— tick 里只做 O(1) 的事，路径搜索被切成每 tick 300 个节点的片。
 */
public final class PlayerEngine implements State.Provider {

    public static final PlayerEngine INSTANCE = new PlayerEngine();

    private PlayerEngine() {}

    // ---------------------------------------------------------- 移动意图（给 InputMixin 读）

    /** 本 tick 想让玩家怎么走；null = 不动手，交还给键盘。 */
    public static final class MoveIntent {
        public float forward;
        public float strafe;
        public boolean jump;
        public boolean sneak;
    }

    private static MoveIntent intent;

    /** 供 InputMixin 调用：拿到本 tick 的移动意图（没有就返回 null）。 */
    public static MoveIntent currentIntent() {
        return intent;
    }

    private static void want(float forward, float strafe, boolean jump, boolean sneak) {
        MoveIntent mi = intent;
        if (mi == null) {
            mi = new MoveIntent();
            intent = mi;
        }
        mi.forward = forward;
        mi.strafe = strafe;
        mi.jump = jump;
        mi.sneak = sneak;
    }

    /** 给 Step 用：本 tick 的移动意图（下一 tick 会被键盘/引擎重写）。 */
    public static void pushIntent(float forward, float strafe, boolean jump, boolean sneak) {
        want(forward, strafe, jump, sneak);
    }

    public static void clearIntent() {
        release();
    }

    private static void release() {
        intent = null;
    }

    // ---------------------------------------------------------------- 队伍

    private final Deque<Action> queue = new ArrayDeque<>();
    private Action current;
    private int ticksInAction;
    private boolean reached;
    private final List<BlockPos> waypoints = new ArrayList<>();
    private int waypointIndex;
    private BlockPos mineTarget;
    private Entity attackTarget;
    private String lastNote = "";
    private boolean baritoneOwns;

    private ChatSink chat = t -> {};
    private Consumer<String> speech = t -> {};

    public void setOutput(ChatSink chatSink, Consumer<String> speechSink) {
        if (chatSink != null) {
            this.chat = chatSink;
        }
        if (speechSink != null) {
            this.speech = speechSink;
        }
    }

    // ---------------------------------------------------------------- 排队

    /** 新动作插队（会打断当前动作）。 */
    public void submit(Action a) {
        if (a == null || a.type() == null) {
            return;
        }
        synchronized (queue) {
            queue.clear();
            queue.addLast(a);
        }
    }

    public void submitAll(List<Action> actions) {
        if (actions == null || actions.isEmpty()) {
            return;
        }
        synchronized (queue) {
            queue.clear();
            queue.addAll(actions);
        }
    }

    public void append(Action a) {
        if (a != null) {
            synchronized (queue) {
                queue.addLast(a);
            }
        }
    }

    public boolean busy() {
        return current != null || !queue.isEmpty();
    }

    private final List<Action> finished = new ArrayList<>();

    /** 取走已结束动作的说明（给大脑回话用）。 */
    public List<String> drainNotes() {
        List<String> out = new ArrayList<>(finished.size());
        for (Action a : finished) {
            out.add(a.type());
        }
        finished.clear();
        return out;
    }

    // ---------------------------------------------------------------- tick

    public void tick(MinecraftClient mc) {
        if (mc == null || mc.player == null || mc.world == null || mc.isPaused()) {
            release();
            return;
        }
        ClientPlayerEntity p = mc.player;
        ticksInAction++;

        if (current == null) {
            synchronized (queue) {
                current = queue.pollFirst();
            }
            ticksInAction = 0;
            reached = false;
            baritoneOwns = false;
            mineTarget = null;
            attackTarget = null;
            waypoints.clear();
            waypointIndex = 0;
            if (current == null) {
                idle(mc, p);
                return;
            }
            begin(mc, p, current);
        }

        try {
            switch (current.type()) {
                case "goto" -> doGoto(mc, p, current);
                case "mine" -> doMine(mc, p, current);
                case "place" -> doPlace(mc, p, current);
                case "follow" -> doFollow(mc, p, current);
                case "hold" -> doHold(mc, p, current);
                case "drop" -> doDrop(mc, p, current);
                case "attack" -> doAttack(mc, p, current);
                case "use" -> doUse(mc, p, current);
                case "jump" -> doJump(mc, p, current);
                case "wait" -> doWait(mc, p, current);
                case "say" -> doSay(mc, p, current);
                case "sit" -> doSit(mc, p, current);
                case "stand" -> doStand(mc, p, current);
                case "stop" -> doStop(mc, p, current);
                default -> done("未知动作 " + current.type());
            }
        } catch (RuntimeException e) {
            // 世界在动，偶发空指针不该把游戏搞崩
            done("动作出错 " + e.getClass().getSimpleName());
        }

        if (ticksInAction > 20 * 60 * 3) { // 三分钟没干完就放弃，别卡死
            done(current.type() + " 超时");
        }
    }

    private void begin(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        if (baritoneWanted(a) && BaritoneBridge.available()) {
            boolean ok = switch (a.type()) {
                case "goto" -> a.y() > 0 ? BaritoneBridge.gotoXYZ((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()))
                        : BaritoneBridge.gotoXZ((int) Math.floor(a.x()), (int) Math.floor(a.z()));
                case "mine" -> BaritoneBridge.mine((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()));
                case "follow" -> BaritoneBridge.follow(mc.player, (int) Math.max(2, AiConfig.INSTANCE.companion.followRadius));
                default -> false;
            };
            baritoneOwns = ok;
            if (ok) {
                lastNote = "Baritone 接管 " + a.type();
            }
        }
        switch (a.type()) {
            case "mine" -> mineTarget = new BlockPos((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()));
            case "attack" -> attackTarget = nearestEntity(mc, a.item());
            case "hold" -> selectItem(p, a.item());
            default -> { }
        }
    }

    /** 哪些动作交给 Baritone 更划算。 */
    private static boolean baritoneWanted(Action a) {
        return switch (a.type()) {
            case "goto", "mine", "follow" -> true;
            default -> false;
        };
    }

    // ---------------------------------------------------------------- 原地待命

    private void idle(MinecraftClient mc, ClientPlayerEntity p) {
        release();
        int radius = (int) AiConfig.INSTANCE.companion.followRadius;
        if (radius <= 0) {
            return;
        }
        // 主人走远了就自己跟上
        Vec3d anchor = FollowTarget.get(mc);
        if (anchor == null) {
            return;
        }
        double d = p.getPos().distanceTo(anchor);
        if (d > radius + 2) {
            Step.walkTo(p, new Vec3d(anchor.x, anchor.y, anchor.z), true);
        } else if (d > radius + 0.5) {
            Step.walkTo(p, new Vec3d(anchor.x, anchor.y, anchor.z), false);
        }
    }

    // ---------------------------------------------------------------- 各动作

    private void doGoto(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        // 优先让小怜（女仆）自己寻路走过去
        Entity e = CompanionHandle.get(mc);
        if (e instanceof AICompanionEntity c) {
            c.navigateTo(new BlockPos((int) Math.floor(a.x()),
                    (int) Math.floor(a.y() > 0 ? a.y() : p.getY()),
                    (int) Math.floor(a.z())));
            done("让小怜走到 " + (int) a.x() + " " + (int) a.z());
            return;
        }
        Vec3d target = new Vec3d(Math.floor(a.x()) + 0.5, a.y() > 0 ? a.y() : p.getY(), Math.floor(a.z()) + 0.5);
        if (baritoneOwns) {
            if (!BaritoneBridge.isPathing()) {
                done("走到 " + fmt(target));
            }
            return;
        }
        if (!ensurePath(mc, p, target)) {
            return;
        }
        followPath(mc, p, target);
    }

    private void doMine(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        if (mineTarget == null) {
            done("没锁定方块");
            return;
        }
        if (baritoneOwns) {
            // Baritone 负责走过去挖，挖掉了就算完
            if (mc.world.getBlockState(mineTarget).isAir() && !BaritoneBridge.isPathing()) {
                done("挖掉了 " + fmt(mineTarget));
            }
            return;
        }
        double reach = AiConfig.INSTANCE.engine.reach;
        Vec3d center = Vec3d.ofCenter(mineTarget);
        if (p.getEyePos().distanceTo(center) > reach - 0.3) {
            if (!ensurePath(mc, p, center)) {
                return;
            }
            followPath(mc, p, center);
            return;
        }
        release();
        Step.look(p, center);
        if (BaritoneBridge.available()) {
            BaritoneBridge.stop();
        }
        // 每 tick 推一次破坏进度，服务端按真实工具速度推进
        if (mc.interactionManager != null) {
            mc.interactionManager.updateBlockBreakingProgress(mineTarget, Direction.UP);
            p.swingHand(Hand.MAIN_HAND);
        }
        if (mc.world.getBlockState(mineTarget).isAir()) {
            done("挖掉了 " + fmt(mineTarget));
        }
    }

    private void doPlace(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        BlockPos target = new BlockPos((int) Math.floor(a.x()), (int) Math.floor(a.y()), (int) Math.floor(a.z()));
        double reach = AiConfig.INSTANCE.engine.reach;
        Vec3d center = Vec3d.ofCenter(target);
        if (p.getEyePos().distanceTo(center) > reach - 0.5) {
            if (!ensurePath(mc, p, center)) {
                return;
            }
            followPath(mc, p, center);
            return;
        }
        release();
        BlockPos neighbor = null;
        Direction face = null;
        for (Direction d : Direction.values()) {
            BlockPos n = target.offset(d);
            if (!mc.world.getBlockState(n).isAir()) {
                neighbor = n;
                face = d.getOpposite();
                break;
            }
        }
        if (neighbor == null || face == null) {
            done("那块地方周围没落脚点，放不了");
            return;
        }
        if (mc.interactionManager != null) {
            Step.look(p, center);
            Vec3d hit = Vec3d.ofCenter(neighbor).add(Vec3d.of(face.getVector()).multiply(0.5));
            mc.interactionManager.interactBlock(p, Hand.MAIN_HAND,
                    new BlockHitResult(hit, face, neighbor, false));
            p.swingHand(Hand.MAIN_HAND);
        }
        if (!mc.world.getBlockState(target).isAir()) {
            done("放到 " + fmt(target));
        }
    }

    private void doFollow(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        release();
        Vec3d anchor = FollowTarget.get(mc);
        if (anchor == null) {
            done("找不到要跟的人");
            return;
        }
        double keep = a.count() > 0 ? a.count() : Math.max(2, AiConfig.INSTANCE.companion.followRadius);
        double d = p.getPos().distanceTo(anchor);
        if (d > keep + 3) {
            Step.walkTo(p, toGround(anchor), true);
        } else if (d > keep + 0.6) {
            Step.walkTo(p, toGround(anchor), false);
        } else {
            Step.stop(p);
        }
        if (a.ticks() > 0 && ticksInAction > a.ticks()) {
            done("跟完了");
        }
    }

    private void doHold(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        release();
        done(selectItem(p, a.item()) ? "拿起了 " + a.item() : "背包里没有 " + a.item());
    }

    private void doDrop(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        release();
        if (!a.item().isBlank() && !selectItem(p, a.item())) {
            done("背包里没有 " + a.item());
            return;
        }
        p.dropSelectedItem(a.count() <= 0 || a.count() >= 64);
        done("丢掉了 " + (a.item().isBlank() ? "手上的东西" : a.item()));
    }

    private void doAttack(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        if (attackTarget == null || !attackTarget.isAlive()) {
            attackTarget = nearestEntity(mc, a.item());
        }
        if (attackTarget == null) {
            done("附近没有目标");
            return;
        }
        Vec3d center = attackTarget.getPos().add(0, attackTarget.getHeight() * 0.5, 0);
        double reach = AiConfig.INSTANCE.engine.reach;
        if (p.getEyePos().distanceTo(center) > reach - 0.5) {
            if (!ensurePath(mc, p, center)) {
                return;
            }
            followPath(mc, p, center);
            return;
        }
        release();
        Step.look(p, center);
        if (mc.interactionManager != null) {
            mc.interactionManager.attackEntity(p, attackTarget);
            p.swingHand(Hand.MAIN_HAND);
        }
        if (!attackTarget.isAlive()) {
            done("打倒了目标");
        }
    }

    private void doUse(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        release();
        if (!a.item().isBlank()) {
            selectItem(p, a.item());
        }
        if (mc.interactionManager != null) {
            mc.interactionManager.interactItem(p, Hand.MAIN_HAND);
        }
        p.swingHand(Hand.MAIN_HAND);
        done("用了一下" + (a.item().isBlank() ? "手上的东西" : " " + a.item()));
    }

    private void doJump(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        want(0f, 0f, true, false);
        if (ticksInAction > 3) {
            release();
            done("跳了");
        }
    }

    private void doWait(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        release();
        int want = a.ticks() > 0 ? a.ticks() : 20;
        if (ticksInAction >= want) {
            done("等了 " + (want / 20.0) + " 秒");
        }
    }

    private void doSay(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        release();
        String text = a.item() == null || a.item().isBlank() ? "..." : a.item();
        chat.send(text);
        speech.accept(text);
        done("说了话");
    }

    private void doStop(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        stopAll();
        done("停下了");
    }

    /** 让小怜坐下。 */
    private void doSit(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        Entity e = CompanionHandle.get(mc);
        if (e instanceof AICompanionEntity c) {
            c.setSitting(true);
            done("坐下了");
        } else {
            done("没找到小怜");
        }
    }

    /** 让小怜站起来。 */
    private void doStand(MinecraftClient mc, ClientPlayerEntity p, Action a) {
        Entity e = CompanionHandle.get(mc);
        if (e instanceof AICompanionEntity c) {
            c.setSitting(false);
            done("站起来了");
        } else {
            done("没找到小怜");
        }
    }

    // ---------------------------------------------------------------- 跟人

    private static Vec3d toGround(Vec3d v) {
        return new Vec3d(v.x, v.y, v.z);
    }

    /** 跟谁：优先同伴实体旁边，没有就跟主人。 */
    static final class FollowTarget {
        static Vec3d get(MinecraftClient mc) {
            if (mc.player == null) {
                return null;
            }
            Entity companion = CompanionHandle.get(mc);
            if (companion != null) {
                return mc.player.getPos();
            }
            return mc.player.getPos();
        }
    }

    // ---------------------------------------------------------------- 路径

    private boolean ensurePath(MinecraftClient mc, ClientPlayerEntity p, Vec3d target) {
        if (!waypoints.isEmpty() && waypointIndex < waypoints.size() - 1) {
            return true;
        }
        BlockPos start = p.getBlockPos();
        BlockPos goal = BlockPos.ofFloored(target);
        List<BlockPos> nodes = AStar.find(mc.world, start, goal, AiConfig.INSTANCE.engine.pathNodeLimit, 400);
        if (nodes == null || nodes.size() < 2) {
            waypoints.clear();
            waypointIndex = 0;
            // 找不到路就直线硬冲，卡住也算努力过
            want(1f, 0f, false, false);
            Step.look(p, target);
            if (p.getPos().distanceTo(target) < 2.5) {
                done("到了");
            }
            return false;
        }
        waypoints.clear();
        waypoints.addAll(nodes);
        waypointIndex = 1;
        lastNote = "寻路 " + nodes.size() + " 格";
        return true;
    }

    private void followPath(MinecraftClient mc, ClientPlayerEntity p, Vec3d finalTarget) {
        if (waypoints.isEmpty()) {
            return;
        }
        Vec3d wp = Vec3d.ofCenter(waypoints.get(Math.min(waypointIndex, waypoints.size() - 1)));
        if (p.getPos().distanceTo(wp) < 1.2 && waypointIndex < waypoints.size() - 1) {
            waypointIndex++;
            wp = Vec3d.ofCenter(waypoints.get(waypointIndex));
        }
        if (p.getPos().distanceTo(finalTarget) < 1.5) {
            Step.stop(p);
            release();
            done("到了 " + fmt(finalTarget));
            return;
        }
        boolean sprint = p.getPos().distanceTo(finalTarget) > 8;
        Step.walkTo(p, wp, sprint);
    }

    // ---------------------------------------------------------------- 小工具

    private boolean selectItem(ClientPlayerEntity p, String item) {
        if (item == null || item.isBlank()) {
            return false;
        }
        String want = item.toLowerCase(Locale.ROOT);
        for (int i = 0; i < 9; i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty()) {
                continue;
            }
            if (matches(s, want)) {
                p.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    private static boolean matches(ItemStack s, String want) {
        Identifier id = Registries.ITEM.getId(s.getItem());
        String path = id.getPath();
        return path.equals(want) || path.contains(want) || id.toString().equals(want)
                || (want.equals("pickaxe") && path.endsWith("_pickaxe"))
                || (want.equals("sword") && path.endsWith("_sword"))
                || (want.equals("axe") && path.endsWith("_axe"))
                || (want.equals("shovel") && path.endsWith("_shovel"))
                || (want.equals("food") && s.isFood());
    }

    /** 找最近的目标实体：a.item() 给名字就按名字找，否则找最近的怪。 */
    private Entity nearestEntity(MinecraftClient mc, String name) {
        if (mc.world == null || mc.player == null) {
            return null;
        }
        Entity best = null;
        double bestD = Double.MAX_VALUE;
        String want = name == null ? "" : name.toLowerCase(Locale.ROOT);
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player || !(e instanceof LivingEntity) || !e.isAlive()) {
                continue;
            }
            if (!want.isBlank()) {
                String type = Registries.ENTITY_TYPE.getId(e.getType()).getPath();
                if (!type.contains(want)) {
                    continue;
                }
            } else if (!(e instanceof net.minecraft.entity.mob.HostileEntity)
                    && !(e instanceof net.minecraft.entity.passive.PassiveEntity)) {
                continue;
            }
            double d = e.distanceTo(mc.player);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        return best;
    }

    private static String fmt(Vec3d v) {
        return String.format(Locale.ROOT, "(%.0f, %.0f)", v.x, v.z);
    }

    private static String fmt(BlockPos v) {
        return "(" + v.getX() + ", " + v.getY() + ", " + v.getZ() + ")";
    }

    private void done(String note) {
        if (current != null) {
            finished.add(current);
        }
        lastNote = note;
        current = null;
        ticksInAction = 0;
        waypoints.clear();
        mineTarget = null;
        release();
    }

    public void stopAll() {
        synchronized (queue) {
            queue.clear();
        }
        current = null;
        waypoints.clear();
        mineTarget = null;
        attackTarget = null;
        release();
        BaritoneBridge.stop();
    }

    public String lastNote() {
        return lastNote;
    }

    // ---------------------------------------------------------------- 状态出口

    @Override
    public String snapshot() {
        MinecraftClient mc = MinecraftClient.getInstance();
        StringBuilder sb = new StringBuilder(160);
        sb.append("{");
        if (mc != null && mc.player != null) {
            Vec3d pos = mc.player.getPos();
            sb.append("\"x\":").append((int) pos.x).append(',');
            sb.append("\"y\":").append((int) pos.y).append(',');
            sb.append("\"z\":").append((int) pos.z).append(',');
            sb.append("\"hp\":").append((int) mc.player.getHealth()).append(',');
            sb.append("\"held\":\"").append(heldName(mc.player)).append("\",");
        }
        sb.append("\"busy\":").append(busy()).append(',');
        sb.append("\"doing\":\"").append(current == null ? "idle" : current.type()).append("\",");
        sb.append("\"note\":\"").append(escape(lastNote)).append("\",");
        sb.append("\"baritone\":\"").append(BaritoneBridge.available() ? "ready" : "off").append("\"");
        sb.append("}");
        return sb.toString();
    }

    private static String heldName(ClientPlayerEntity p) {
        ItemStack s = p.getMainHandStack();
        return s.isEmpty() ? "空手" : Registries.ITEM.getId(s.getItem()).getPath();
    }

    private static String escape(String s) {
        return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** 把引擎挂进全局状态，方便大脑/外部端口读。 */
    public void install() {
        State.setProvider(this);
    }

    /** 一次性把背包里可用的东西列出来（给 LLM 看家底）。 */
    public static String inventorySummary(ClientPlayerEntity p) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < p.getInventory().size(); i++) {
            ItemStack s = p.getInventory().getStack(i);
            if (s.isEmpty() || s.getItem() == Items.AIR) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(Registries.ITEM.getId(s.getItem()).getPath()).append("x").append(s.getCount());
            if (sb.length() > 240) {
                sb.append("...");
                break;
            }
        }
        return sb.length() == 0 ? "空" : sb.toString();
    }

    /** 异步兜个底：让外部（HTTP/桥）也能排队动作，而不只走主线程调用方。 */
    public void submitAsync(Action a) {
        Async.run(() -> submit(a));
    }

    public Deque<Action> pending() {
        synchronized (queue) {
            return new ArrayDeque<>(queue);
        }
    }

    static ClientWorld worldOf(MinecraftClient mc) {
        return mc.world;
    }
}
