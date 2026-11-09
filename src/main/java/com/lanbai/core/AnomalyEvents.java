package com.lanbai.core;

import com.lanbai.core.effect.EffectExecutors;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/**
 * 事件分发器（Fabric 版）
 */
public class AnomalyEvents {

    private final RulingEngine engine = new RulingEngine();
    private int tickCounter = 0;

    private static List<String> fired(String... conds) {
        List<String> l = new ArrayList<>();
        for (String c : conds) if (c != null) l.add(c);
        return l;
    }

    private void dispatch(ServerLevel level, TriggerContext ctx, List<Anomaly> anomalies, List<String> conditions) {
        if (anomalies == null || anomalies.isEmpty()) return;
        engine.beginEvent();
        try {
            for (Anomaly a : anomalies) {
                List<Anomaly.Effect> matches = new ArrayList<>();
                for (Anomaly.Effect e : a.effects) {
                    if (e.trigger == null || e.trigger.matches(conditions)) {
                        matches.add(e);
                    }
                }
                if (matches.isEmpty()) continue;
                List<Anomaly.Effect> finalList = engine.resolveIntra(a, matches, ctx);
                for (Anomaly.Effect e : finalList) {
                    try {
                        EffectExecutors.apply(e, ctx);
                    } catch (Exception ex) {
                        System.err.println("[LanBai] 执行 " + e.id + " 失败: " + ex);
                    }
                }
            }
        } finally {
            engine.endEvent();
        }
    }

    public static void register() {
        new AnomalyEvents().hook();
    }

    private void hook() {
        // 右键实体
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            TriggerContext ctx = new TriggerContext(level)
                    .source(player)
                    .target(entity)
                    .item(player.getItemInHand(hand));
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity), fired("on_use", "on_contact"));
            return InteractionResult.PASS;
        });

        // 左键攻击
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            TriggerContext ctx = new TriggerContext(level)
                    .source(player)
                    .target(entity)
                    .item(player.getItemInHand(hand));
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity), fired("on_attack"));
            return InteractionResult.PASS;
        });

        // 方块破坏（可取消）
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerLevel level)) return true;
            List<Anomaly> list = AnomalyRegistry.get().getBlockAnomalies(pos);
            if (list.isEmpty()) return true;

            for (Anomaly a : list) {
                if (a.target != null && a.target.persistent) {
                    return false;
                }
            }
            TriggerContext ctx = new TriggerContext(level).source(player).pos(pos);
            dispatch(level, ctx, list, fired("on_break", "on_hit_block"));
            return true;
        });

        // 实体加载
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (!(world instanceof ServerLevel level)) return;
            TriggerContext ctx = new TriggerContext(level).target(entity).source(entity);
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity), fired("on_spawn"));
        });

        // 受伤
        ServerLivingEntityEvents.AFTER_DAMAGE.register((entity, source, baseDamage, damageTaken, blocked) -> {
            if (!(entity.level() instanceof ServerLevel level)) return;
            TriggerContext ctx = new TriggerContext(level)
                    .source(source.getEntity())
                    .target(entity);
            ctx.put("damage", damageTaken);
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity), fired("on_damage", "on_attacked"));

            Entity attacker = source.getEntity();
            if (attacker != null && attacker.level() instanceof ServerLevel al) {
                TriggerContext c2 = new TriggerContext(al).source(attacker).target(entity);
                c2.put("damage", damageTaken);
                dispatch(al, c2, AnomalyRegistry.get().collectFor(attacker), fired("on_attack"));
            }
        });

        // 死亡
        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.level() instanceof ServerLevel level)) return;
            TriggerContext ctx = new TriggerContext(level)
                    .source(source.getEntity())
                    .target(entity);
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity), fired("on_death", "on_kill"));
        });

        // 周期 tick（POS 持久化校验）
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 100 != 0) return;
            for (Anomaly a : AnomalyRegistry.get().all()) {
                if (a.target == null) continue;
                if (a.target.kind == Anomaly.BindTarget.Kind.POS && a.target.persistent) {
                    try {
                        BlockPos pos = AnomalyRegistry.parsePos(a.target.ref);
                        ServerLevel level = server.overworld();
                        var want = Blocks.STONE.defaultBlockState();
                        if (!level.getBlockState(pos).equals(want)) {
                            level.setBlockAndUpdate(pos, want);
                        }
                    } catch (Exception ignored) {
                    }
                }
            }
        });
    }
}
