package com.lanbai.core.effect;

import com.lanbai.core.Anomaly;
import com.lanbai.core.TriggerContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * 效果执行器：所有 ef_* 效果的具体实现。
 *
 * 设计原则：只调用原版 API，不写特例逻辑。
 */
public final class EffectExecutors {

    private EffectExecutors() {
    }

    public static void apply(Anomaly.Effect e, TriggerContext ctx) {
        switch (e.id) {
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
            default -> {
                // 未实现的效果：静默跳过，由 AnomalyCommand 提示
            }
        }
    }

    public static boolean implemented(String id) {
        return switch (id) {
            case "ef_status", "ef_damage", "ef_heal", "ef_set_attr", "ef_immune",
                 "ef_teleport", "ef_set_block", "ef_push", "ef_spawn", "ef_destroy", "ef_mark" -> true;
            default -> false;
        };
    }

    // ==================== 状态效果 ====================

    private static void status(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (!(t instanceof LivingEntity living)) return;

        String effectId = e.strVal("effect", "minecraft:wither");
        int level = e.intVal("level", 0);
        int duration = e.intVal("duration", 200);

        MobEffect me = BuiltInRegistries.MOB_EFFECT.get(new ResourceLocation(effectId));
        if (me == null) return;
        living.addEffect(new MobEffectInstance(me, duration, level, false, true));
    }

    // ==================== 伤害 ====================

    private static void damage(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target;
        if (!(t instanceof LivingEntity living)) return;
        float amount = (float) e.doubleVal("amount", 1.0);
        living.hurt(ctx.level.damageSources().magic(), amount);
    }

    // ==================== 治疗 ====================

    private static void heal(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (!(t instanceof LivingEntity living)) return;
        float amount = (float) e.doubleVal("amount", 1.0);
        living.heal(amount);
    }

    // ==================== 属性修改 ====================

    private static void setAttr(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (!(t instanceof LivingEntity living)) return;

        String attrName = e.strVal("attr", "minecraft:generic.movement_speed");
        double value = e.doubleVal("value", 0.0);
        String mode = e.strVal("mode", "multiply_base");

        ResourceLocation rl = new ResourceLocation(attrName);
        Attribute attr = BuiltInRegistries.ATTRIBUTE.get(rl);
        if (attr == null) return;

        AttributeInstance inst = living.getAttribute(attr);
        if (inst == null) return;

        UUID uuid = UUID.nameUUIDFromBytes((e.id + attrName).getBytes());
        inst.removeModifier(uuid);

        AttributeModifier.Operation op = switch (mode) {
            case "add" -> AttributeModifier.Operation.ADDITION;
            case "multiply_total" -> AttributeModifier.Operation.MULTIPLY_TOTAL;
            default -> AttributeModifier.Operation.MULTIPLY_BASE;
        };
        inst.addPermanentModifier(new AttributeModifier(uuid, "lanbai_anomaly", value, op));
    }

    // ==================== 免疫 ====================

    private static void immune(Anomaly.Effect e, TriggerContext ctx) {
        // 免疫是拦截式效果，实际拦截在事件处理器里完成。
        // 这里只做标记，供事件处理器查询。
        String type = e.strVal("type", "all_break");
        ctx.shared.put("immune:" + type, Boolean.TRUE);
    }

    // ==================== 传送 ====================

    private static void teleport(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (t == null) return;
        double x = e.doubleVal("x", t.getX());
        double y = e.doubleVal("y", t.getY());
        double z = e.doubleVal("z", t.getZ());
        t.teleportTo(x, y, z);
    }

    // ==================== 方块修改 ====================

    private static void setBlock(Anomaly.Effect e, TriggerContext ctx) {
        if (ctx.pos == null) return;
        String blockId = e.strVal("block", "minecraft:stone");
        Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(blockId));
        if (b == null) return;
        ctx.level.setBlockAndUpdate(ctx.pos, b.defaultBlockState());
    }

    // ==================== 击退 ====================

    private static void push(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target;
        if (t == null) return;
        double power = e.doubleVal("power", 1.0);
        if (ctx.source != null) {
            double dx = t.getX() - ctx.source.getX();
            double dz = t.getZ() - ctx.source.getZ();
            double len = Math.max(0.01, Math.sqrt(dx * dx + dz * dz));
            t.push(dx / len * power, 0.3, dz / len * power);
        } else {
            t.push(0, power, 0);
        }
    }

    // ==================== 生成 ====================

    private static void spawn(Anomaly.Effect e, TriggerContext ctx) {
        String entityId = e.strVal("entity", "minecraft:zombie");
        var type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(entityId));
        if (type == null || ctx.pos == null) return;
        Entity spawned = type.create(ctx.level);
        if (spawned == null) return;
        spawned.moveTo(ctx.pos.getX() + 0.5, ctx.pos.getY() + 1, ctx.pos.getZ() + 0.5);
        ctx.level.addFreshEntity(spawned);
    }

    // ==================== 销毁 ====================

    private static void destroy(Anomaly.Effect e, TriggerContext ctx) {
        if (ctx.target != null) {
            ctx.target.discard();
        } else if (ctx.pos != null) {
            ctx.level.removeBlock(ctx.pos, false);
        }
    }

    // ==================== 标记 ====================

    private static void mark(Anomaly.Effect e, TriggerContext ctx) {
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        if (t == null) return;
        int duration = e.intVal("duration", 200);
        t.setGlowingTag(true);
        ctx.shared.put("mark_until", ctx.gameTime + duration);
    }
}
