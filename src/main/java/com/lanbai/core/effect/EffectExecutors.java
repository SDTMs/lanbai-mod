package com.lanbai.core.effect;

import com.lanbai.core.Anomaly;
import com.lanbai.core.TriggerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 效果执行器（原子效果库）。
 *
 * 每个 ef_xxx 对应 JSON 里 effects[].id，参数通过 params 传入。
 * 新增原子的原则：只用基础 API（伤害/效果/生成/方块/物品/坐标），避免版本敏感的签名。
 */
public final class EffectExecutors {

    /** 我们改动过的属性名，reload 时据此撤销旧 modifier */
    private static final Set<String> USED_ATTRS = new HashSet<>();

    private EffectExecutors() {
    }

    public static void apply(Anomaly.Effect e, TriggerContext ctx) {
        String id = e.id;
        switch (id) {
            // ---- 原始 15 个 ----
            case "ef_status" -> status(e, ctx);
            case "ef_damage" -> damage(e, ctx);
            case "ef_heal" -> heal(e, ctx);
            case "ef_set_attr" -> setAttr(e, ctx);
            case "ef_immune" -> immune(e, ctx);
            case "ef_teleport" -> teleport(e, ctx);
            case "ef_set_block" -> setBlock(e, ctx);
            case "ef_push" -> push(e, ctx);
            case "ef_spawn" -> spawn(e, ctx);
            case "ef_destroy" -> destroy(e, ctx);
            case "ef_mark" -> mark(e, ctx);
            case "ef_force_action" -> forceAction(e, ctx);
            case "ef_rule" -> rule(e, ctx);
            case "ef_pull" -> pull(e, ctx);
            case "ef_perceive" -> perceive(e, ctx);
            // ---- 扩展原子（v2）----
            case "ef_explode" -> explode(e, ctx);
            case "ef_lightning" -> lightning(e, ctx);
            case "ef_reflect" -> reflect(e, ctx);
            case "ef_shield" -> shield(e, ctx);
            case "ef_give" -> give(e, ctx);
            case "ef_take" -> take(e, ctx);
            case "ef_fill" -> fill(e, ctx);
            case "ef_copy" -> copy(e, ctx);
            case "ef_transform" -> transform(e, ctx);
            case "ef_drain" -> drain(e, ctx);
            case "ef_launch" -> launch(e, ctx);
            case "ef_scan" -> scan(e, ctx);
            case "ef_message" -> message(e, ctx);
            case "ef_xp" -> xp(e, ctx);
            case "ef_dispel" -> dispel(e, ctx);
            case "ef_freeze" -> freeze(e, ctx);
            default -> { /* 未知效果：忽略，不中断其他效果 */ }
        }
    }

    // ============ 目标筛选 ============

    /** targetFilter: all / player / non_player */
    private static boolean accept(Anomaly.Effect e, Entity target) {
        String f = e.strVal("targetFilter", "all");
        if (f.equals("player")) return target instanceof Player;
        if (f.equals("non_player")) return !(target instanceof Player);
        return true;
    }

    private static List<LivingEntity> around(TriggerContext ctx, int radius) {
        return ctx.level.getEntitiesOfClass(LivingEntity.class, new AABB(ctx.pos).inflate(radius));
    }

    private static List<LivingEntity> aroundAt(TriggerContext ctx, BlockPos pos, int radius) {
        return ctx.level.getEntitiesOfClass(LivingEntity.class, new AABB(pos).inflate(radius));
    }

    /**
     * 统一目标选择器。
     * selector: target(默认) / source / around / around_target / players / type
     * 配合 radius / maxTargets / targetFilter 使用。
     */
    private static List<LivingEntity> selectTargets(Anomaly.Effect e, TriggerContext ctx) {
        String sel = e.strVal("selector", "target");
        int radius = e.intVal("radius", 8);
        int max = Math.max(1, Math.min(e.intVal("maxTargets", 32), 200));
        List<LivingEntity> out = new ArrayList<>();

        switch (sel) {
            case "source" -> {
                if (ctx.source instanceof LivingEntity le) out.add(le);
            }
            case "around", "area" -> {
                if (ctx.pos != null) out.addAll(around(ctx, radius));
                else if (ctx.source != null) out.addAll(aroundAt(ctx, ctx.source.blockPosition(), radius));
            }
            case "around_target" -> {
                if (ctx.target != null) out.addAll(aroundAt(ctx, ctx.target.blockPosition(), radius));
            }
            case "players" -> {
                MinecraftServer srv = ctx.level.getServer();
                if (srv != null) {
                    for (var p : srv.getPlayerList().getPlayers()) {
                        if (ctx.pos != null) {
                            if (p.level() == ctx.level
                                    && p.blockPosition().distSqr(ctx.pos) <= (double) radius * radius) {
                                out.add(p);
                            }
                        } else {
                            out.add(p);
                        }
                    }
                }
            }
            case "type" -> {
                String typeId = e.strVal("entityType", "minecraft:zombie");
                BlockPos base = ctx.pos != null ? ctx.pos
                        : (ctx.source != null ? ctx.source.blockPosition() : null);
                if (base != null) {
                    for (var en : ctx.level.getEntitiesOfClass(Entity.class, new AABB(base).inflate(radius))) {
                        var key = BuiltInRegistries.ENTITY_TYPE.getKey(en.getType());
                        if (key != null && key.toString().equals(typeId) && en instanceof LivingEntity le) {
                            out.add(le);
                        }
                    }
                }
            }
            default -> {
                Entity t = ctx.target != null ? ctx.target : ctx.source;
                if (t instanceof LivingEntity le) out.add(le);
            }
        }

        out.removeIf(le -> !le.isAlive() || !accept(e, le));
        if (out.size() > max) out = new ArrayList<>(out.subList(0, max));
        return out;
    }

    /** 按 id 取状态效果（1.20.1 的 MobEffects 字段是 Holder，不能直接用于 MobEffectInstance） */
    private static MobEffect effect(String id) {
        return BuiltInRegistries.MOB_EFFECT.get(new ResourceLocation(id));
    }

    /** 优先给玩家发消息 */
    private static void tell(TriggerContext ctx, String text) {
        if (ctx.source instanceof Player p) p.displayClientMessage(Component.literal(text), true);
        else if (ctx.target instanceof Player p) p.displayClientMessage(Component.literal(text), true);
    }

    // ============ 状态效果 ============

    private static void status(Anomaly.Effect e, TriggerContext ctx) {
        String effectId = e.strVal("effect", "minecraft:wither");
        int level = e.intVal("level", 0);
        int duration = e.intVal("duration", 200);
        MobEffect me = BuiltInRegistries.MOB_EFFECT.get(new ResourceLocation(effectId));
        if (me == null) return;

        int radius = e.intVal("radius", 0);
        if (radius > 0 && ctx.pos != null) {
            for (LivingEntity le : around(ctx, radius)) {
                if (accept(e, le)) le.addEffect(new MobEffectInstance(me, duration, level, false, true));
            }
            return;
        }

        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (t instanceof LivingEntity living && accept(e, living)) {
            living.addEffect(new MobEffectInstance(me, duration, level, false, true));
        }
    }

    // ============ 伤害 ============

    private static void damage(Anomaly.Effect e, TriggerContext ctx) {
        float amount = (float) e.doubleVal("amount", 3.0);
        int radius = e.intVal("radius", 0);
        if (radius > 0 && ctx.pos != null) {
            for (LivingEntity le : around(ctx, radius)) {
                if (accept(e, le)) le.hurt(ctx.level.damageSources().magic(), amount);
            }
            return;
        }
        if (ctx.target instanceof LivingEntity living && accept(e, living)) {
            living.hurt(ctx.level.damageSources().magic(), amount);
        }
    }

    // ============ 治疗 / 净化 ============

    private static void heal(Anomaly.Effect e, TriggerContext ctx) {
        boolean cureAll = Boolean.TRUE.equals(e.val("cureAll", Boolean.FALSE));
        float amount = (float) e.doubleVal("amount", 5.0);

        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (!(t instanceof LivingEntity living) || !accept(e, living)) return;

        living.heal(amount);
        if (cureAll) {
            var harmful = new ArrayList<MobEffect>();
            for (var inst : living.getActiveEffects()) {
                if (!inst.getEffect().isBeneficial()) harmful.add(inst.getEffect());
            }
            for (MobEffect m : harmful) living.removeEffect(m);
            if (living instanceof Player p) {
                p.displayClientMessage(Component.literal("§a[医王金针] 百病尽除"), true);
            }
        }
    }

    // ============ 属性修改 ============

    private static void setAttr(Anomaly.Effect e, TriggerContext ctx) {
        String attrName = e.strVal("attr", "minecraft:generic.movement_speed");
        double value = e.doubleVal("value", 0.0);
        String mode = e.strVal("mode", "multiply_base");

        Attribute attr = BuiltInRegistries.ATTRIBUTE.get(new ResourceLocation(attrName));
        if (attr == null) return;
        USED_ATTRS.add(attrName);

        int radius = e.intVal("radius", 0);
        if (radius > 0 && ctx.pos != null) {
            for (LivingEntity le : around(ctx, radius)) {
                if (accept(e, le)) applyAttr(le, attr, value, mode);
            }
            return;
        }

        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (t instanceof LivingEntity living && accept(e, living)) {
            applyAttr(living, attr, value, mode);
        }
    }

    private static void applyAttr(LivingEntity living, Attribute attr, double value, String mode) {
        AttributeInstance inst = living.getAttribute(attr);
        if (inst == null) return;
        UUID uuid = UUID.nameUUIDFromBytes((living.getUUID() + attr.getDescriptionId()).getBytes());
        inst.removeModifier(uuid);
        AttributeModifier.Operation op = mode.equals("add")
                ? AttributeModifier.Operation.ADDITION
                : (mode.equals("multiply_total") ? AttributeModifier.Operation.MULTIPLY_TOTAL
                : AttributeModifier.Operation.MULTIPLY_BASE);
        inst.addPermanentModifier(new AttributeModifier(uuid, "lanbai_anomaly", value, op));
    }

    /**
     * reload 前撤销所有由本 mod 加上的属性 modifier。
     * 不清理的话，改过的收容物数值会残留在实体身上直到该效果下次触发。
     */
    public static void clearAllModifiers(MinecraftServer server) {
        if (server == null || USED_ATTRS.isEmpty()) return;
        for (ServerLevel level : server.getAllLevels()) {
            for (Player p : level.players()) {
                for (String a : USED_ATTRS) {
                    Attribute attr = BuiltInRegistries.ATTRIBUTE.get(new ResourceLocation(a));
                    if (attr == null) continue;
                    AttributeInstance inst = p.getAttribute(attr);
                    if (inst == null) continue;
                    UUID uuid = UUID.nameUUIDFromBytes((p.getUUID() + attr.getDescriptionId()).getBytes());
                    inst.removeModifier(uuid);
                }
            }
        }
    }

    // ============ 免疫 ============

    private static void immune(Anomaly.Effect e, TriggerContext ctx) {
        String type = e.strVal("type", "all");
        ctx.shared.put("immune:" + type, Boolean.TRUE);
        if (ctx.source instanceof Player p && "magic".equals(type)) {
            p.displayClientMessage(Component.literal("§b[厌魔石] 魔力对你无效"), true);
        }
    }

    // ============ 传送 ============

    private static void teleport(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (t == null) return;
        double x = e.doubleVal("x", t.getX());
        double y = e.doubleVal("y", t.getY());
        double z = e.doubleVal("z", t.getZ());
        t.teleportTo(x, y, z);
    }

    // ============ 方块 ============

    private static void setBlock(Anomaly.Effect e, TriggerContext ctx) {
        if (ctx.pos == null) return;
        Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(e.strVal("block", "minecraft:stone")));
        if (b != null) ctx.level.setBlockAndUpdate(ctx.pos, b.defaultBlockState());
    }

    // ============ 击退 ============

    private static void push(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target;
        if (t == null) return;
        double power = e.doubleVal("power", 1.5);
        if (ctx.source != null) {
            double dx = t.getX() - ctx.source.getX();
            double dz = t.getZ() - ctx.source.getZ();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            t.push(dx / len * power, 0.3, dz / len * power);
        } else {
            t.push(0, power, 0);
        }
    }

    // ============ 生成（支持数量） ============

    private static void spawn(Anomaly.Effect e, TriggerContext ctx) {
        String entityId = e.strVal("entity", "minecraft:zombie");
        int count = Math.min(e.intVal("count", 1), 200);   // 上限 200 防卡
        int spread = e.intVal("spread", 3);
        var type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(entityId));
        if (type == null || ctx.pos == null) return;

        var rng = ctx.level.random;
        for (int i = 0; i < count; i++) {
            Entity spawned = type.create(ctx.level);
            if (spawned == null) continue;
            double dx = (rng.nextDouble() - 0.5) * spread * 2;
            double dz = (rng.nextDouble() - 0.5) * spread * 2;
            spawned.moveTo(ctx.pos.getX() + 0.5 + dx, ctx.pos.getY() + 1, ctx.pos.getZ() + 0.5 + dz);
            ctx.level.addFreshEntity(spawned);
        }
    }

    // ============ 销毁 ============

    private static void destroy(Anomaly.Effect e, TriggerContext ctx) {
        if (ctx.target != null) ctx.target.discard();
        else if (ctx.pos != null) ctx.level.removeBlock(ctx.pos, false);
    }

    // ============ 标记 / 追踪 ============

    private static void mark(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (t == null) return;
        t.setGlowingTag(true);
        int duration = e.intVal("duration", 400);
        if (duration > 0) {
            ctx.shared.put("mark_expire", ctx.gameTime + duration);
        }
    }

    // ============ 强制动作 ============

    private static void forceAction(Anomaly.Effect e, TriggerContext ctx) {
        String action = e.strVal("action", "flee");
        int radius = e.intVal("radius", 16);
        int duration = e.intVal("duration", 200);
        if (ctx.pos == null) return;

        for (LivingEntity le : around(ctx, radius)) {
            if (!accept(e, le)) continue;
            if (action.equals("flee")) {
                if (ctx.source != null) {
                    double dx = le.getX() - ctx.source.getX();
                    double dz = le.getZ() - ctx.source.getZ();
                    double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                    le.push(dx / len * 2.5, 0.3, dz / len * 2.5);
                }
                MobEffect slow = effect("minecraft:slowness");
                if (slow != null) le.addEffect(new MobEffectInstance(slow, duration, 1, false, false));
            } else if (action.equals("sleep")) {
                MobEffect slow = effect("minecraft:slowness");
                MobEffect blind = effect("minecraft:blindness");
                if (slow != null) le.addEffect(new MobEffectInstance(slow, duration, 10, false, false));
                if (blind != null) le.addEffect(new MobEffectInstance(blind, duration, 0, false, false));
            }
        }
    }

    // ============ 规则改写 ============

    private static void rule(Anomaly.Effect e, TriggerContext ctx) {
        String ruleName = e.strVal("rule", "ban_psychic");
        int radius = e.intVal("radius", 32);
        String text = e.strVal("text", "");
        if (ctx.level.getServer() != null) {
            ctx.level.getServer().getPlayerList().getPlayers().forEach(p -> {
                if (p.level() == ctx.level && ctx.pos != null
                        && p.blockPosition().distSqr(ctx.pos) <= (double) radius * radius) {
                    p.displayClientMessage(
                            Component.literal(text.isEmpty() ? "§8[无色石] 心灵力量被禁止" : text), true);
                }
            });
        }
        ctx.shared.put("rule:" + ruleName, radius);
    }

    // ============ 拉取 ============

    private static void pull(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target;
        if (t == null || ctx.source == null) return;
        double speed = e.doubleVal("speed", 3.0);
        double dx = ctx.source.getX() - t.getX();
        double dy = ctx.source.getY() - t.getY();
        double dz = ctx.source.getZ() - t.getZ();
        double len = Math.max(0.01, Math.sqrt(dx * dx + dy * dy + dz * dz));
        t.setDeltaMovement(dx / len * speed, dy / len * speed + 0.2, dz / len * speed);
        t.hurtMarked = true;

        float soulDamage = (float) e.doubleVal("soulDamage", 0.0);
        if (soulDamage > 0 && t instanceof LivingEntity living) {
            living.hurt(ctx.level.damageSources().magic(), soulDamage);
        }
    }

    // ============ 感知 ============

    private static void perceive(Anomaly.Effect e, TriggerContext ctx) {
        String mode = e.strVal("mode", "tracking");
        if (mode.equals("tracking") && ctx.target != null) {
            ctx.target.setGlowingTag(true);
        } else if (mode.equals("see_block")) {
            String blockId = e.strVal("block", "minecraft:stone");
            if (ctx.source instanceof Player p) {
                p.displayClientMessage(
                        Component.literal("§7[感知] 该方块已透视: " + blockId), true);
            }
        }
    }

    // ==================================================================
    // 扩展原子（v2）
    // ==================================================================

    /** 爆炸：范围魔法伤害 + 击退，可选破坏方块 */
    private static void explode(Anomaly.Effect e, TriggerContext ctx) {
        if (ctx.pos == null) return;
        int radius = Math.max(1, Math.min(e.intVal("radius", 4), 12));
        double power = e.doubleVal("power", 6.0);
        boolean breakBlocks = Boolean.TRUE.equals(e.val("breakBlocks", Boolean.FALSE));

        for (LivingEntity le : around(ctx, radius)) {
            if (!accept(e, le)) continue;
            double d = Math.sqrt(le.blockPosition().distSqr(ctx.pos));
            double ratio = 1.0 - Math.min(1.0, d / radius);
            float dmg = (float) (power * ratio);
            if (dmg > 0.1) {
                le.hurt(ctx.level.damageSources().magic(), dmg);
                double dx = le.getX() - ctx.pos.getX();
                double dz = le.getZ() - ctx.pos.getZ();
                double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                le.push(dx / len * ratio * 2.0, 0.4 * ratio, dz / len * ratio * 2.0);
            }
        }

        if (breakBlocks) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos p = ctx.pos.offset(dx, dy, dz);
                        if (ctx.level.getBlockState(p).isAir()) continue;
                        ctx.level.removeBlock(p, false);
                    }
                }
            }
        }
    }

    /** 落雷：生成闪电实体 + 追加范围伤害 */
    private static void lightning(Anomaly.Effect e, TriggerContext ctx) {
        BlockPos base = ctx.pos != null ? ctx.pos
                : (ctx.target != null ? ctx.target.blockPosition()
                : (ctx.source != null ? ctx.source.blockPosition() : null));
        if (base == null) return;
        int radius = e.intVal("radius", 2);
        double dmg = e.doubleVal("damage", 5.0);

        var type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation("minecraft:lightning_bolt"));
        if (type != null) {
            Entity bolt = type.create(ctx.level);
            if (bolt != null) {
                bolt.moveTo(base.getX() + 0.5, base.getY(), base.getZ() + 0.5);
                ctx.level.addFreshEntity(bolt);
            }
        }
        for (LivingEntity le : aroundAt(ctx, base, radius)) {
            if (accept(e, le)) le.hurt(ctx.level.damageSources().magic(), (float) dmg);
        }
    }

    /** 反伤：把受到的伤害乘以 factor 反弹给攻击者 */
    private static void reflect(Anomaly.Effect e, TriggerContext ctx) {
        Object dv = ctx.extra.get("damage");
        double taken = dv instanceof Number n ? n.doubleValue() : 0.0;
        if (taken <= 0) return;
        double factor = e.doubleVal("factor", 2.0);
        double max = e.doubleVal("max", 40.0);
        Entity attacker = ctx.source;
        if (attacker == null || attacker == ctx.target) return;
        if (attacker instanceof LivingEntity le) {
            le.hurt(ctx.level.damageSources().magic(), (float) Math.min(taken * factor, max));
        }
    }

    /** 护盾：吸收伤害（absorption） */
    private static void shield(Anomaly.Effect e, TriggerContext ctx) {
        int level = e.intVal("level", 3);
        int duration = e.intVal("duration", 200);
        for (LivingEntity le : selectTargets(e, ctx)) {
            MobEffect absorption = effect("minecraft:absorption");
            if (absorption != null) le.addEffect(new MobEffectInstance(absorption, duration, level, false, true));
        }
    }

    /** 给物品 */
    private static void give(Anomaly.Effect e, TriggerContext ctx) {
        String itemId = e.strVal("item", "minecraft:bread");
        int count = Math.max(1, Math.min(e.intVal("count", 1), 64));
        Item it = BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        if (it == null || it == Items.AIR) return;
        for (LivingEntity le : selectTargets(e, ctx)) {
            if (le instanceof Player p) {
                ItemStack s = new ItemStack(it, count);
                if (!p.getInventory().add(s)) p.drop(s, false);
            }
        }
    }

    /** 没收物品：item 留空则清空背包，否则按物品删 count 个 */
    private static void take(Anomaly.Effect e, TriggerContext ctx) {
        String itemId = e.strVal("item", "");
        int count = Math.max(1, e.intVal("count", 1));
        Item it = itemId.isEmpty() ? null : BuiltInRegistries.ITEM.get(new ResourceLocation(itemId));
        for (LivingEntity le : selectTargets(e, ctx)) {
            if (!(le instanceof Player p)) continue;
            if (it == null || it == Items.AIR) {
                p.getInventory().clearContent();
                continue;
            }
            int left = count;
            for (int i = 0; i < p.getInventory().getContainerSize() && left > 0; i++) {
                ItemStack s = p.getInventory().getItem(i);
                if (s.isEmpty() || s.getItem() != it) continue;
                int n = Math.min(left, s.getCount());
                s.shrink(n);
                left -= n;
            }
        }
    }

    /** 区域填充方块 */
    private static void fill(Anomaly.Effect e, TriggerContext ctx) {
        if (ctx.pos == null) return;
        int radius = Math.max(1, Math.min(e.intVal("radius", 2), 6));
        boolean hollow = Boolean.TRUE.equals(e.val("hollow", Boolean.FALSE));
        Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(e.strVal("block", "minecraft:sand")));
        if (b == null) return;
        var bs = b.defaultBlockState();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (hollow && Math.abs(dx) < radius && Math.abs(dy) < radius && Math.abs(dz) < radius) continue;
                    ctx.level.setBlockAndUpdate(ctx.pos.offset(dx, dy, dz), bs);
                }
            }
        }
    }

    /** 复制实体 */
    private static void copy(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target;
        if (t == null) return;
        int count = Math.max(1, Math.min(e.intVal("count", 1), 16));
        int spread = e.intVal("spread", 2);
        EntityType<?> type = t.getType();
        var rng = ctx.level.random;
        for (int i = 0; i < count; i++) {
            Entity n = type.create(ctx.level);
            if (n == null) continue;
            double dx = (rng.nextDouble() - 0.5) * spread * 2;
            double dz = (rng.nextDouble() - 0.5) * spread * 2;
            n.moveTo(t.getX() + dx, t.getY(), t.getZ() + dz);
            ctx.level.addFreshEntity(n);
        }
    }

    /** 变形：把目标换成另一种实体 */
    private static void transform(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (t == null) return;
        var type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(e.strVal("entity", "minecraft:zombie")));
        if (type == null) return;
        Entity n = type.create(ctx.level);
        if (n == null) return;
        n.moveTo(t.getX(), t.getY(), t.getZ());
        n.setYRot(t.getYRot());
        ctx.level.addFreshEntity(n);
        t.discard();
    }

    /** 吸血：伤害目标并回复自身 */
    private static void drain(Anomaly.Effect e, TriggerContext ctx) {
        float amount = (float) e.doubleVal("amount", 4.0);
        double ratio = e.doubleVal("ratio", 0.5);
        if (ctx.target instanceof LivingEntity target && accept(e, target)) {
            target.hurt(ctx.level.damageSources().magic(), amount);
            if (ctx.source instanceof LivingEntity src && src != target) {
                src.heal((float) (amount * ratio));
            }
        }
    }

    /** 弹射：给目标（或自己）一个方向速度 */
    private static void launch(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (t == null) return;
        double power = e.doubleVal("power", 1.5);
        String dir = e.strVal("direction", "up");
        if (dir.equals("forward") && ctx.source != null) {
            double yaw = Math.toRadians(ctx.source.getYRot());
            double dx = -Math.sin(yaw) * power;
            double dz = Math.cos(yaw) * power;
            t.setDeltaMovement(dx, 0.2, dz);
        } else {
            t.setDeltaMovement(t.getDeltaMovement().x, power, t.getDeltaMovement().z);
        }
        t.hurtMarked = true;
    }

    /** 扫描：半径内实体列给玩家（铜镜 / 共享之眼） */
    private static void scan(Anomaly.Effect e, TriggerContext ctx) {
        int radius = Math.max(1, Math.min(e.intVal("radius", 32), 128));
        String header = e.strVal("header", "§7[扫描] 范围内：");
        StringBuilder sb = new StringBuilder(header);
        int n = 0;
        BlockPos base = ctx.pos != null ? ctx.pos
                : (ctx.source != null ? ctx.source.blockPosition() : null);
        if (base == null) return;
        for (LivingEntity le : aroundAt(ctx, base, radius)) {
            if (!accept(e, le)) continue;
            if (n++ >= 20) {
                sb.append(" §8...");
                break;
            }
            sb.append(" §f").append(le.getName().getString())
              .append("§7(").append((int) le.getX()).append(",")
              .append((int) le.getY()).append(",").append((int) le.getZ()).append(")");
        }
        if (n == 0) sb.append(" §8无目标");
        tell(ctx, sb.toString());
    }

    /** 发消息 */
    private static void message(Anomaly.Effect e, TriggerContext ctx) {
        String text = e.strVal("text", "……");
        boolean actionbar = !"chat".equals(e.strVal("mode", "actionbar"));
        for (LivingEntity le : selectTargets(e, ctx)) {
            if (le instanceof Player p) p.displayClientMessage(Component.literal(text), actionbar);
        }
    }

    /** 给经验 */
    private static void xp(Anomaly.Effect e, TriggerContext ctx) {
        int amount = e.intVal("amount", 10);
        int levels = e.intVal("levels", 0);
        for (LivingEntity le : selectTargets(e, ctx)) {
            if (!(le instanceof Player p)) continue;
            if (levels > 0) p.giveExperienceLevels(levels);
            else if (amount > 0) p.giveExperiencePoints(amount);
        }
    }

    /** 驱散状态效果 */
    private static void dispel(Anomaly.Effect e, TriggerContext ctx) {
        boolean beneficial = Boolean.TRUE.equals(e.val("beneficial", Boolean.FALSE));
        for (LivingEntity le : selectTargets(e, ctx)) {
            var list = new ArrayList<MobEffect>();
            for (var inst : le.getActiveEffects()) {
                if (inst.getEffect().isBeneficial() == beneficial) list.add(inst.getEffect());
            }
            for (MobEffect m : list) le.removeEffect(m);
        }
    }

    /** 冻结 */
    private static void freeze(Anomaly.Effect e, TriggerContext ctx) {
        int ticks = e.intVal("ticks", 140);
        for (LivingEntity le : selectTargets(e, ctx)) {
            le.setTicksFrozen(Math.max(le.getTicksFrozen(), ticks));
            MobEffect slow = effect("minecraft:slowness");
            if (slow != null) le.addEffect(new MobEffectInstance(slow, ticks, 2, false, false));
        }
    }
}
