package com.aicompanion.entity;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.Inventories;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import software.bernie.geckolib.core.animatable.GeoAnimatable;
import software.bernie.geckolib.core.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.core.animation.AnimatableManager;
import software.bernie.geckolib.core.animation.AnimationController;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.core.animation.RawAnimation;
import software.bernie.geckolib.core.object.PlayState;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.UUID;

/**
 * 玩家形态的 AI 同伴。
 *
 * 它继承 PathAwareEntity（因此天然拥有 goals / navigation 结构），但实体本身只做
 * 「数据载体 + 渲染载体」：物品栏、装备、状态、主人。所有行为（走路 / 挖矿 / 放置）
 * 由客户端侧的 PlayerEngine 驱动，因为同伴是纯客户端实体。
 */
public class AICompanionEntity extends PathAwareEntity implements GeoAnimatable {

    public static final int INV_SIZE = 36;

    private final DefaultedList<ItemStack> inventory = DefaultedList.ofSize(INV_SIZE, ItemStack.EMPTY);

    private final AnimatableInstanceCache geoCache = GeckoLibUtil.createInstanceCache(this);
    private static final RawAnimation ANIM_IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation ANIM_WALK = RawAnimation.begin().thenLoop("walk");
    private static final RawAnimation ANIM_RUN = RawAnimation.begin().thenLoop("run");
    private static final RawAnimation ANIM_SIT = RawAnimation.begin().thenLoop("sit");

    private UUID ownerUuid;
    private String ownerName = "";
    /** idle / follow / goto / mine / place / talk */
    private String taskState = "idle";
    private Vec3d lookTarget;
    private boolean sitting = false;

    public AICompanionEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
        this.setPersistent();
        this.experiencePoints = 0;
    }

    public static DefaultAttributeContainer.Builder createCompanionAttributes() {
        return PathAwareEntity.createMobAttributes()
                .add(EntityAttributes.GENERIC_MAX_HEALTH, 20.0D)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.32D)
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 64.0D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 2.0D)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 1.0D);
    }

    // ------------------------------------------------------------ ownership

    public void setOwner(PlayerEntity player) {
        this.ownerUuid = player.getUuid();
        this.ownerName = player.getGameProfile().getName();
    }

    public UUID getOwnerUuid() {
        return ownerUuid;
    }

    public String getOwnerName() {
        return ownerName;
    }

    /** 客户端拿不到的玩家时退化为名字匹配。 */
    public PlayerEntity resolveOwner() {
        if (this.getWorld() == null) return null;
        if (this.getWorld() instanceof ServerWorld sw && ownerUuid != null) {
            PlayerEntity p = sw.getPlayerByUuid(ownerUuid);
            if (p != null) return p;
        }
        if (this.getWorld().isClient()) {
            net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
            if (mc.player == null) return null;
            if (ownerUuid != null && ownerUuid.equals(mc.player.getUuid())) return mc.player;
            if (!ownerName.isEmpty() && ownerName.equals(mc.player.getGameProfile().getName())) return mc.player;
        }
        return null;
    }

    // ------------------------------------------------------------ inventory

    public DefaultedList<ItemStack> getInventory() {
        return inventory;
    }

    public ItemStack getStack(int slot) {
        if (slot < 0 || slot >= INV_SIZE) return ItemStack.EMPTY;
        return inventory.get(slot);
    }

    public void setStack(int slot, ItemStack stack) {
        if (slot < 0 || slot >= INV_SIZE) return;
        inventory.set(slot, stack == null ? ItemStack.EMPTY : stack);
    }

    /** 返回剩余数量（0 表示全部塞进去了）。 */
    public int insert(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        for (int i = 0; i < INV_SIZE && !stack.isEmpty(); i++) {
            ItemStack cur = inventory.get(i);
            if (!cur.isEmpty() && ItemStack.canCombine(cur, stack)) {
                int room = cur.getMaxCount() - cur.getCount();
                if (room > 0) {
                    int move = Math.min(room, stack.getCount());
                    cur.increment(move);
                    stack.decrement(move);
                }
            }
        }
        for (int i = 0; i < INV_SIZE && !stack.isEmpty(); i++) {
            if (inventory.get(i).isEmpty()) {
                inventory.set(i, stack.copy());
                stack.setCount(0);
            }
        }
        return stack.getCount();
    }

    public int count(Item item) {
        int n = 0;
        for (int i = 0; i < INV_SIZE; i++) {
            ItemStack st = inventory.get(i);
            if (!st.isEmpty() && st.isOf(item)) n += st.getCount();
        }
        return n;
    }

    public int firstSlotOf(Item item) {
        for (int i = 0; i < INV_SIZE; i++) {
            ItemStack st = inventory.get(i);
            if (!st.isEmpty() && st.isOf(item)) return i;
        }
        return -1;
    }

    /** 消耗指定物品 n 个，成功返回 true。 */
    public boolean consume(Item item, int n) {
        if (count(item) < n) return false;
        int left = n;
        for (int i = 0; i < INV_SIZE && left > 0; i++) {
            ItemStack st = inventory.get(i);
            if (!st.isEmpty() && st.isOf(item)) {
                int take = Math.min(left, st.getCount());
                st.decrement(take);
                left -= take;
                if (st.isEmpty()) inventory.set(i, ItemStack.EMPTY);
            }
        }
        return true;
    }

    // ------------------------------------------------------------ equipment

    @Override
    public ItemStack getEquippedStack(EquipmentSlot slot) {
        if (slot == EquipmentSlot.MAINHAND) return getStack(0);
        if (slot == EquipmentSlot.OFFHAND) return getStack(1);
        return super.getEquippedStack(slot);
    }

    @Override
    public void equipStack(EquipmentSlot slot, ItemStack stack) {
        if (slot == EquipmentSlot.MAINHAND) {
            setStack(0, stack);
            return;
        }
        if (slot == EquipmentSlot.OFFHAND) {
            setStack(1, stack);
            return;
        }
        super.equipStack(slot, stack);
    }

    // ------------------------------------------------------------ state

    public String getTaskState() {
        return taskState;
    }

    public void setTaskState(String state) {
        this.taskState = state == null ? "idle" : state;
    }

    public void setSitting(boolean s) {
        this.sitting = s;
        if (s) {
            this.setVelocity(0, 0, 0);
        }
        this.setTaskState(s ? "sit" : "idle");
    }

    public boolean isSitting() {
        return sitting;
    }

    public Vec3d getLookTarget() {
        return lookTarget;
    }

    public void lookAt(Vec3d target) {
        this.lookTarget = target;
    }

    public void clearLook() {
        this.lookTarget = null;
    }

    /** 让同伴朝某个方向看（客户端每帧调用）。 */
    public void faceTowards(Vec3d target) {
        Vec3d d = target.subtract(this.getPos());
        double dx = d.x;
        double dz = d.z;
        double dy = d.y;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0D);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, horiz)));
        this.setYaw(yaw);
        this.setHeadYaw(yaw);
        this.setPitch(Math.max(-90f, Math.min(90f, pitch)));
    }

    public void hop() {
        Vec3d v = this.getVelocity();
        this.setVelocity(v.x, 0.42D, v.z);
        this.velocityModified = true;
    }

    /** 手动位移（客户端寻路用，绕开服务端寻路）。 */
    public void step(Vec3d delta) {
        this.move(net.minecraft.entity.MovementType.SELF, delta);
    }

    // ------------------------------------------------------------ geckolib 动画

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "move", 5, this::animateBody));
    }

    @Override
    public double getTick(Object entity) {
        return this.age;
    }

    private PlayState animateBody(AnimationState<AICompanionEntity> state) {
        AICompanionEntity e = state.getAnimatable();
        if (e.sitting) {
            state.getController().setAnimation(ANIM_SIT);
            return PlayState.CONTINUE;
        }
        double h = e.getVelocity().horizontalLengthSquared();
        if (h > 0.04) {
            state.getController().setAnimation(ANIM_RUN);
        } else if (h > 1.0E-4) {
            state.getController().setAnimation(ANIM_WALK);
        } else {
            state.getController().setAnimation(ANIM_IDLE);
        }
        return PlayState.CONTINUE;
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return geoCache;
    }

    // ------------------------------------------------------------ misc

    @Override
    public boolean isPersistent() {
        return true;
    }

    @Override
    public boolean canImmediatelyDespawn(double distanceSquared) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        boolean hurt = super.damage(source, amount);
        if (hurt && !this.getWorld().isClient()) {
            this.getWorld().sendEntityStatus(this, (byte) 2);
        }
        return hurt;
    }

    @Override
    public void tick() {
        super.tick();
        if (!this.getWorld().isClient()) {
            return;
        }

        // 坐下：原地不动
        if (this.sitting) {
            this.setVelocity(0, 0, 0);
            return;
        }

        // 跟随主人（保持 1.5~3 格，不贴脸）
        PlayerEntity owner = resolveOwner();
        if (owner != null && !owner.isRemoved()) {
            double distSq = squaredDistanceTo(owner);
            if (distSq > 9.0) {
                moveToward(owner.getPos(), 0.22);
            } else if (distSq > 2.25) {
                moveToward(owner.getPos(), 0.12);
            } else {
                this.setVelocity(0, this.getVelocity().y * 0.5, 0);
            }
        }

        // 捡起附近的掉落物
        pickupNearby();

        // 空手时偶尔冒个粒子，方便在人群里找到她
        if (this.age % 12 == 0 && this.getStack(0).isEmpty() && this.random.nextFloat() < 0.08f) {
            this.getWorld().addParticle(ParticleTypes.HAPPY_VILLAGER,
                    this.getX() + (this.random.nextDouble() - 0.5) * 0.6,
                    this.getY() + 1.6 + this.random.nextDouble() * 0.3,
                    this.getZ() + (this.random.nextDouble() - 0.5) * 0.6,
                    0.0, 0.0, 0.0);
        }
    }

    /** 朝目标走：设水平速度，撞墙或目标更高就跳。 */
    private void moveToward(Vec3d target, double speed) {
        Vec3d me = this.getPos();
        Vec3d d = target.subtract(me);
        double h = Math.sqrt(d.x * d.x + d.z * d.z);
        if (h < 0.05) {
            return;
        }
        this.setVelocity(d.x / h * speed, this.getVelocity().y, d.z / h * speed);
        this.faceTowards(target);
        if (this.horizontalCollision && this.isOnGround()) {
            this.hop();
        }
        if (d.y > 1.0 && this.isOnGround() && Math.abs(d.x) + Math.abs(d.z) < 2.0) {
            this.hop();
        }
    }

    /** 捡起 8 格内最近的掉落物。 */
    private void pickupNearby() {
        java.util.List<ItemEntity> items = this.getWorld().getEntitiesByClass(
                ItemEntity.class, this.getBoundingBox().expand(8.0), e -> e != null && !e.isRemoved());
        ItemEntity best = null;
        double bestD = Double.MAX_VALUE;
        for (ItemEntity it : items) {
            double dd = squaredDistanceTo(it);
            if (dd < bestD) {
                bestD = dd;
                best = it;
            }
        }
        if (best == null) {
            return;
        }
        if (bestD <= 1.5) {
            this.insert(best.getStack());
            best.discard();
        } else if (bestD > 4.0) {
            moveToward(best.getPos(), 0.18);
        }
    }

    @Override
    public void playAmbientSound() {
        if (!this.getWorld().isClient()) {
            this.getWorld().playSound(null, this.getBlockPos(), SoundEvents.ENTITY_VILLAGER_AMBIENT,
                    this.getSoundCategory(), 0.25f, 1.4f);
        }
    }

    // ------------------------------------------------------------ nbt

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        NbtCompound inv = new NbtCompound();
        Inventories.writeNbt(inv, this.inventory);
        nbt.put("Inventory", inv);
        if (ownerUuid != null) nbt.putUuid("Owner", ownerUuid);
        nbt.putString("OwnerName", ownerName == null ? "" : ownerName);
        nbt.putString("TaskState", taskState);
        nbt.putBoolean("Sitting", sitting);
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        if (nbt.contains("Inventory", NbtElement.COMPOUND_TYPE)) {
            Inventories.readNbt(nbt.getCompound("Inventory"), this.inventory);
        }
        if (nbt.containsUuid("Owner")) ownerUuid = nbt.getUuid("Owner");
        ownerName = nbt.getString("OwnerName");
        if (nbt.contains("TaskState")) taskState = nbt.getString("TaskState");
        sitting = nbt.getBoolean("Sitting");
    }
}
