package com.lanbai.core.effect;

import com.lanbai.core.Anomaly;
import com.lanbai.core.TriggerContext;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;

import java.util.List;
import java.util.UUID;

public final class EffectExecutors {

    private EffectExecutors() {
    }

    public static void apply(Anomaly.Effect e, TriggerContext ctx) {
        String id = e.id;
        if (id.equals("ef_status")) status(e, ctx);
        else if (id.equals("ef_damage")) damage(e, ctx);
        else if (id.equals("ef_heal")) heal(e, ctx);
        else if (id.equals("ef_set_attr")) setAttr(e, ctx);
        else if (id.equals("ef_immune")) immune(e, ctx);
        else if (id.equals("ef_teleport")) teleport(e, ctx);
        else if (id.equals("ef_set_block")) setBlock(e, ctx);
        else if (id.equals("ef_push")) push(e, ctx);
        else if (id.equals("ef_spawn")) spawn(e, ctx);
        else if (id.equals("ef_destroy")) destroy(e, ctx);
        else if (id.equals("ef_mark")) mark(e, ctx);
        else if (id.equals("ef_force_action")) forceAction(e, ctx);
        else if (id.equals("ef_rule")) rule(e, ctx);
        else if (id.equals("ef_pull")) pull(e, ctx);
        else if (id.equals("ef_perceive")) perceive(e, ctx);
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
        return ctx.level.getEntitiesOfClass(LivingEntity.class,
                new AABB(ctx.pos).inflate(radius));
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
            // 清除所有负面效果
            var harmful = new java.util.ArrayList<MobEffect>();
            for (var inst : living.getActiveEffects()) {
                if (!inst.getEffect().isBeneficial()) harmful.add(inst.getEffect());
            }
            for (MobEffect m : harmful) living.removeEffect(m);
            if (living instanceof Player p) {
                p.displayClientMessage(net.minecraft.network.chat.Component.literal("§a[医王金针] 百病尽除"), true);
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

    // ============ 免疫 ============

    private static void immune(Anomaly.Effect e, TriggerContext ctx) {
        String type = e.strVal("type", "all");
        ctx.shared.put("immune:" + type, Boolean.TRUE);
        // 玩家免疫时给个提示（避免刷屏，只给 source）
        if (ctx.source instanceof Player p && "magic".equals(type)) {
            p.displayClientMessage(net.minecraft.network.chat.Component.literal("§b[厌魔石] 魔力对你无效"), true);
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
                // 恐惧：反向逃跑
                if (ctx.source != null) {
                    double dx = le.getX() - ctx.source.getX();
                    double dz = le.getZ() - ctx.source.getZ();
                    double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
                    le.push(dx / len * 2.5, 0.3, dz / len * 2.5);
                }
                MobEffect fearEffect = BuiltInRegistries.MOB_EFFECT.get(new ResourceLocation("minecraft:slowness"));
                if (fearEffect != null) le.addEffect(new MobEffectInstance(fearEffect, duration, 1, false, false));
            } else if (action.equals("sleep")) {
                MobEffect slow = BuiltInRegistries.MOB_EFFECT.get(new ResourceLocation("minecraft:slowness"));
                MobEffect blind = BuiltInRegistries.MOB_EFFECT.get(new ResourceLocation("minecraft:blindness"));
                if (slow != null) le.addEffect(new MobEffectInstance(slow, duration, 10, false, false));
                if (blind != null) le.addEffect(new MobEffectInstance(blind, duration, 0, false, false));
            }
        }
    }

    // ============ 规则改写 ============

    private static void rule(Anomaly.Effect e, TriggerContext ctx) {
        String ruleName = e.strVal("rule", "ban_psychic");
        int radius = e.intVal("radius", 32);
        // 规则通过 storage 标记，由其他系统读取
        ctx.level.getServer().getPlayerList().getPlayers().forEach(p -> {
            if (p.level() == ctx.level && ctx.pos != null
                    && p.blockPosition().distSqr(ctx.pos) <= (double) radius * radius) {
                p.displayClientMessage(
                        net.minecraft.network.chat.Component.literal("§8[无色石] 心灵力量被禁止"), true);
            }
        });
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
        t.hurtMarked = true;   // 同步给客户端

        // 灵魂伤害
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
                        net.minecraft.network.chat.Component.literal("§7[感知] 该方块已透视: " + blockId), true);
            }
        }
    }
}
