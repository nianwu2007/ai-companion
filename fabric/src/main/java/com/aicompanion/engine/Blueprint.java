package com.aicompanion.engine;

import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * 建造蓝图：程序化生成「小木屋」和「别墅」的方块清单（相对坐标）。
 *
 * 只生成"该放什么方块在哪"，具体砌砖由 PlayerEngine 一块块执行。
 * 顺序特意排成 地板 → 墙（下往上）→ 屋顶，保证先有落脚点再往上垒。
 */
public final class Blueprint {

    private Blueprint() {}

    /** 一块砖：放什么、放哪。 */
    public record Step(BlockPos pos, String item) {}

    /** 小木屋：7×7，墙高 3，阶梯屋顶。 */
    public static List<Step> cottage(BlockPos origin, String wall) {
        return house(origin, 3, 3, wall);
    }

    /** 别墅：11×11，墙高 6（两层高），大阶梯屋顶。 */
    public static List<Step> villa(BlockPos origin, String wall) {
        return house(origin, 5, 6, wall);
    }

    private static final String CORNER = "minecraft:oak_log";
    private static final String GLASS = "minecraft:glass_pane";

    /** 通用房屋：r = 半径（边长 2r+1），wallH = 墙高。 */
    private static List<Step> house(BlockPos o, int r, int wallH, String wall) {
        List<Step> out = new ArrayList<>();

        // 1) 地板
        for (int x = -r; x <= r; x++) {
            for (int z = -r; z <= r; z++) {
                out.add(new Step(o.add(x, 0, z), wall));
            }
        }

        // 2) 墙：四面，角柱用原木，每面中间留窗，南面留门洞
        for (int y = 1; y <= wallH; y++) {
            for (int x = -r; x <= r; x++) {
                for (int z = -r; z <= r; z++) {
                    boolean onX = Math.abs(x) == r;
                    boolean onZ = Math.abs(z) == r;
                    if (!onX && !onZ) {
                        continue; // 内部不放
                    }
                    // 门洞：南墙正中，两格高
                    if (z == r && Math.abs(x) == 0 && y <= 2) {
                        continue;
                    }
                    // 窗：每面墙正中，y=2 处
                    boolean win = y == 2 && ((onX && z == 0) || (onZ && x == 0));
                    boolean corner = onX && onZ;
                    out.add(new Step(o.add(x, y, z), win ? GLASS : (corner ? CORNER : wall)));
                }
            }
        }

        // 3) 阶梯屋顶：每层缩一圈，最顶封口
        int y = wallH + 1;
        for (int rr = r; rr >= 0; rr--, y++) {
            for (int x = -rr; x <= rr; x++) {
                for (int z = -rr; z <= rr; z++) {
                    boolean edge = Math.abs(x) == rr || Math.abs(z) == rr;
                    if (edge || rr == 0) {
                        out.add(new Step(o.add(x, y, z), wall));
                    }
                }
            }
        }
        return out;
    }
}
