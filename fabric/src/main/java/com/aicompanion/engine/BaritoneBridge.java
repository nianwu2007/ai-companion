package com.aicompanion.engine;

import com.aicompanion.config.AiConfig;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Baritone / Automatone 反射桥 —— 零编译依赖。
 *
 * Baritone 自带多线程异步寻路，比我们自己写的 A* 稳、快、聪明，所以只要它在场就优先用它：
 *   BaritoneAPI.getProvider().getPrimaryBaritone().getCustomGoalProcess().setGoalAndPath(goal)
 *   goal 用 baritone.api.pathing.goals.{GoalBlock,GoalXZ,GoalNear,GoalGetToBlock,GoalFollow}
 * 停止用 getPathingBehavior().cancelEverything()。
 *
 * Automatone 是同一套 API 的分支，包名候选一起探。
 */
public final class BaritoneBridge {

    private BaritoneBridge() {}

    private static final String[] ROOTS = {"baritone.api", "automatone.api"};

    private static boolean probed;
    private static boolean available;
    private static Object baritone;      // IBaritone
    private static Method setGoalAndPath;
    private static Method cancelEverything;
    private static Class<?> goalBlock;
    private static Class<?> goalXZ;
    private static Class<?> goalNear;
    private static Class<?> goalGetToBlock;
    private static Class<?> goalFollow;

    /** 探一次，之后缓存结果。 */
    public static synchronized boolean available() {
        if (probed) {
            return available;
        }
        probed = true;
        if (!AiConfig.INSTANCE.engine.preferBaritone) {
            return false;
        }
        for (String root : ROOTS) {
            try {
                Class<?> apiCls = Class.forName(root + ".BaritoneAPI");
                Object provider = apiCls.getMethod("getProvider").invoke(null);
                Object primary = provider.getClass().getMethod("getPrimaryBaritone").invoke(provider);
                if (primary == null) {
                    continue;
                }
                Object customGoal = primary.getClass().getMethod("getCustomGoalProcess").invoke(primary);
                Object behavior = primary.getClass().getMethod("getPathingBehavior").invoke(primary);
                setGoalAndPath = findMethod(customGoal.getClass(), "setGoalAndPath");
                if (setGoalAndPath == null) {
                    continue;
                }
                cancelEverything = findMethod(behavior.getClass(), "cancelEverything");
                goalBlock = tryClass(root + ".pathing.goals.GoalBlock");
                goalXZ = tryClass(root + ".pathing.goals.GoalXZ");
                goalNear = tryClass(root + ".pathing.goals.GoalNear");
                goalGetToBlock = tryClass(root + ".pathing.goals.GoalGetToBlock");
                goalFollow = tryClass(root + ".pathing.goals.GoalFollow");
                baritone = primary;
                available = true;
                return true;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 换下一个包名
            }
        }
        return false;
    }

    private static Class<?> tryClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** 接口方法在实现类上找不到就往上翻父类/接口。 */
    private static Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name)) {
                    return m;
                }
            }
        }
        return null;
    }

    private static boolean setGoal(Object goal) {
        if (goal == null || setGoalAndPath == null) {
            return false;
        }
        try {
            setGoalAndPath.invoke(baritone, goal);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    private static Object newGoal(Class<?> cls, Object... args) {
        if (cls == null) {
            return null;
        }
        for (Constructor<?> ctor : cls.getConstructors()) {
            if (ctor.getParameterCount() != args.length) {
                continue;
            }
            try {
                return ctor.newInstance(args);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 试下一个构造器
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- 对外

    /** 走到某个 XZ（y 交给它自己找）。 */
    public static boolean gotoXZ(int x, int z) {
        if (!available()) {
            return false;
        }
        if (setGoal(newGoal(goalXZ, x, z))) {
            return true;
        }
        return setGoal(newGoal(goalNear, x, 64, z, 2));
    }

    /** 走到精确方块（会自己搭/挖）。 */
    public static boolean gotoXYZ(int x, int y, int z) {
        if (!available()) {
            return false;
        }
        return setGoal(newGoal(goalBlock, x, y, z));
    }

    /** 走到并挖掉这个方块。 */
    public static boolean mine(int x, int y, int z) {
        if (!available()) {
            return false;
        }
        if (setGoal(newGoal(goalGetToBlock, x, y, z))) {
            return true;
        }
        return setGoal(newGoal(goalBlock, x, y, z));
    }

    /** 跟着某个实体（传 Entity 对象，靠反射塞进去，避免编译期依赖 MC）。 */
    public static boolean follow(Object entity, int range) {
        if (!available() || entity == null) {
            return false;
        }
        return setGoal(newGoal(goalFollow, entity, range));
    }

    /** 让它停下来。 */
    public static boolean stop() {
        if (!available()) {
            return false;
        }
        try {
            if (cancelEverything != null) {
                cancelEverything.invoke(baritone);
                return true;
            }
            return setGoal(null);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    /** 它现在在不在走路（用于引擎判断忙不忙）。 */
    public static boolean isPathing() {
        if (!available()) {
            return false;
        }
        try {
            Object behavior = baritone.getClass().getMethod("getPathingBehavior").invoke(baritone);
            Object val = behavior.getClass().getMethod("isPathing").invoke(behavior);
            return Boolean.TRUE.equals(val);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return false;
        }
    }

    public static String version() {
        if (!available()) {
            return "none";
        }
        try {
            return String.valueOf(baritone.getClass().getName());
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    /** 配置改过之后重新探。 */
    public static synchronized void reset() {
        probed = false;
        available = false;
        baritone = null;
    }
}
