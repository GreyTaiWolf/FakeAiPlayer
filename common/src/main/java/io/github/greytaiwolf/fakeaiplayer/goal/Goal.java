package io.github.greytaiwolf.fakeaiplayer.goal;

import java.util.Locale;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

public sealed interface Goal permits Goal.HaveItem, Goal.HavePickaxeTier, Goal.MineOre, Goal.HarvestCrop, Goal.Armor, Goal.Workstation, Goal.Stockpile, Goal.Food, Goal.Build {
    record HaveItem(Item item, int count) implements Goal {
        public HaveItem {
            if (item == null) {
                throw new IllegalArgumentException("goal_item_missing:have_item");
            }
            count = Math.max(1, count);
        }
    }

    record HavePickaxeTier(int tier) implements Goal {
        public HavePickaxeTier {
            tier = Math.max(0, tier);
        }
    }

    record MineOre(Set<Block> ores, int count) implements Goal {
        public MineOre {
            if (ores != null && ores.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("goal_ores_invalid:mine_ore");
            }
            ores = ores == null ? Set.of() : Set.copyOf(ores);
            if (ores.isEmpty()) {
                throw new IllegalArgumentException("goal_ores_missing:mine_ore");
            }
            count = Math.max(1, count);
        }
    }

    /** P3:收获 N 个作物(小麦/胡萝卜/土豆)。倒推:有锄头(+种子)→ 开垦/播种/等熟/收割。 */
    record HarvestCrop(Block crop, Item seed, Item produce, int count) implements Goal {
        public HarvestCrop {
            if (crop == null) {
                throw new IllegalArgumentException("goal_crop_missing:harvest_crop");
            }
            if (seed == null) {
                throw new IllegalArgumentException("goal_seed_missing:harvest_crop");
            }
            if (produce == null) {
                throw new IllegalArgumentException("goal_produce_missing:harvest_crop");
            }
            count = Math.max(1, count);
        }
    }

    /** Phase1:武装起来——成套护甲 + 剑(目前为铁质,复用 GoalPlanner.ensureArmor 倒推)。 */
    record Armor() implements Goal {
    }

    /** Phase2:基建——备齐并摆好工作台/熔炉/箱子三件套(生产+存储据点)。 */
    record Workstation() implements Goal {
    }

    /** Phase3:囤货——获取 count 个 item,并(尽力)存进附近箱子。 */
    record Stockpile(Item item, int count) implements Goal {
        public Stockpile {
            if (item == null) {
                throw new IllegalArgumentException("goal_item_missing:stockpile");
            }
            count = Math.max(1, count);
        }
    }

    /** 第4层 备粮:猎肉并烤成 cookedCount 个熟食(走 GoalPlanner 的猎→烤闭环)。
     *  供"去打猎/去搞点吃的/弄点肉"等口语入口(provision_food 工具)。 */
    record Food(int cookedCount) implements Goal {
        public Food {
            cookedCount = Math.max(1, cookedCount);
        }
    }

    /**
     * 盖房目标:按蓝图建造("盖房子"一句话全链:自动备料→建造)。
     *
     * <p>{@code anchor} 与 {@code dimension} 固化用户确认过的世界位置，
     * {@code blueprintDigest} 则把任务绑定到确认时审核过的规范化蓝图内容。执行器要求三个
     * 字段全部存在；升级前的自动选址任务和任何不完整存档都会失败关闭，绝不在没有当前玩家
     * 投影确认的情况下继续施工。</p>
     */
    record Build(String blueprint,
                 BlockPos anchor,
                 String dimension,
                 String blueprintDigest) implements Goal {
        public Build {
            if (blueprint == null || blueprint.isBlank()) {
                throw new IllegalArgumentException("goal_blueprint_missing:build");
            }
            anchor = anchor == null ? null : anchor.immutable();
            if (dimension == null || dimension.isBlank()) {
                dimension = null;
            } else {
                ResourceLocation parsed = ResourceLocation.tryParse(dimension);
                if (parsed == null) {
                    throw new IllegalArgumentException("invalid_build_dimension: " + dimension);
                }
                dimension = parsed.toString();
            }
            if (blueprintDigest == null || blueprintDigest.isBlank()) {
                blueprintDigest = null;
            } else {
                blueprintDigest = blueprintDigest.toLowerCase(Locale.ROOT);
                if (!blueprintDigest.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("invalid_blueprint_digest");
                }
            }
        }

        public Build(String blueprint, BlockPos anchor, String dimension) {
            this(blueprint, anchor, dimension, null);
        }

        public Build(String blueprint, BlockPos anchor) {
            this(blueprint, anchor, null, null);
        }

        public Build(String blueprint) {
            this(blueprint, null, null, null);
        }

        public boolean isGeneratedReference() {
            return blueprint != null && blueprint.startsWith("generated_");
        }

        public boolean hasCompleteConfirmedBinding() {
            return anchor != null && dimension != null && blueprintDigest != null;
        }
    }
}
