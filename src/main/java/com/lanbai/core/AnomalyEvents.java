package com.lanbai.core;

import com.lanbai.core.effect.EffectExecutors;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

public class AnomalyEvents {

    private final RulingEngine engine = new RulingEngine();
    private int tickCounter = 0;

    private static List<String> fired(String... conds) {
        List<String> l = new ArrayList<>();
        for (String c : conds) if (c != null) l.add(c);
        return l;
    }

    private void dispatch(ServerLevel level, TriggerContext ctx, List<Anomaly> anomalies,
                          List<String> conditions, Player feedbackTo) {
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
                    runOne(a, e, ctx, feedbackTo);
                }
            }
        } finally {
            engine.endEvent();
        }
    }

    private void dispatchPassive(ServerLevel level, TriggerContext ctx, List<Anomaly> anomalies,
                                 Player feedbackTo) {
        if (anomalies == null || anomalies.isEmpty()) return;
        for (Anomaly a : anomalies) {
            for (Anomaly.Effect e : a.effects) {
                if (e.trigger == null) runOne(a, e, ctx, feedbackTo);
            }
        }
    }

    private void runOne(Anomaly a, Anomaly.Effect e, TriggerContext ctx, Player feedbackTo) {
        try {
            EffectExecutors.apply(e, ctx);
            if (feedbackTo instanceof ServerPlayer sp) {
                sp.displayClientMessage(
                        Component.literal("§7[收容物] §f" + a.name + " §8→ §e" + describe(e)), true);
            }
        } catch (Exception ex) {
            System.err.println("[LanBai] 执行 " + e.id + " 失败: " + ex);
        }
    }

    private static String describe(Anomaly.Effect e) {
        String id = e.id;
        if (id.equals("ef_status")) return "施加 " + e.strVal("effect", "?").replace("minecraft:", "");
        if (id.equals("ef_damage")) return "伤害 " + e.doubleVal("amount", 3);
        if (id.equals("ef_heal")) return "回复 " + e.doubleVal("amount", 3);
        if (id.equals("ef_set_attr")) return "属性 " + e.strVal("attr", "?").replace("minecraft:generic.", "");
        if (id.equals("ef_immune")) return "免疫";
        if (id.equals("ef_teleport")) return "传送";
        if (id.equals("ef_set_block")) return "改方块";
        if (id.equals("ef_push")) return "击退";
        if (id.equals("ef_spawn")) return "生成 " + e.strVal("entity", "?").replace("minecraft:", "");
        if (id.equals("ef_destroy")) return "销毁";
        if (id.equals("ef_mark")) return "标记";
        return id;
    }

    public static void register() {
        new AnomalyEvents().hook();
    }

    private void hook() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            TriggerContext ctx = new TriggerContext(level)
                    .source(player).target(entity).item(player.getItemInHand(hand));
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity),
                    fired("on_use", "on_contact"), player);
            return InteractionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            TriggerContext ctx = new TriggerContext(level)
                    .source(player).target(entity).item(player.getItemInHand(hand));
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity),
                    fired("on_attack"), player);
            return InteractionResult.PASS;
        });

        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (!(world instanceof ServerLevel level)) return true;
            List<Anomaly> list = AnomalyRegistry.get().getBlockAnomalies(pos);
            if (list.isEmpty()) return true;
            for (Anomaly a : list) {
                if (a.target != null && a.target.persistent) {
                    if (player instanceof ServerPlayer sp) {
                        sp.displayClientMessage(
                                Component.literal("§c[收容物] §f" + a.name + " §7无法破坏"), true);
                    }
                    return false;
                }
            }
            TriggerContext ctx = new TriggerContext(level).source(player).pos(pos);
            dispatch(level, ctx, list, fired("on_break", "on_hit_block"), player);
            return true;
        });

        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            TriggerContext ctx = new TriggerContext(world).target(entity).source(entity);
            dispatch(world, ctx, AnomalyRegistry.get().collectFor(entity), fired("on_spawn"), null);
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> {
            if (!(entity.level() instanceof ServerLevel level)) return true;
            TriggerContext ctx = new TriggerContext(level)
                    .source(source.getEntity()).target(entity);
            ctx.put("damage", amount);
            Player fb = source.getEntity() instanceof Player p ? p : null;
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity),
                    fired("on_damage", "on_attacked"), fb);

            Entity attacker = source.getEntity();
            if (attacker != null && attacker.level() instanceof ServerLevel al) {
                TriggerContext c2 = new TriggerContext(al).source(attacker).target(entity);
                c2.put("damage", amount);
                dispatch(al, c2, AnomalyRegistry.get().collectFor(attacker),
                        fired("on_attack"), attacker instanceof Player p2 ? p2 : null);
            }
            return true;
        });

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.level() instanceof ServerLevel level)) return;
            TriggerContext ctx = new TriggerContext(level)
                    .source(source.getEntity()).target(entity);
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity),
                    fired("on_death", "on_kill"), null);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 10 != 0) return;

            var players = server.getPlayerList().getPlayers();
            var all = new ArrayList<>(AnomalyRegistry.get().all());

            for (ServerPlayer player : players) {
                ServerLevel level = (ServerLevel) player.level();
                BlockPos ppos = player.blockPosition();

                for (Anomaly a : all) {
                    if (a.target == null) continue;

                    switch (a.target.kind) {
                        case BLOCK, POS, AREA -> {
                            BlockPos ap;
                            try {
                                ap = AnomalyRegistry.parsePos(a.target.ref);
                            } catch (Exception ex) {
                                continue;
                            }
                            double d2 = ap.distSqr(ppos);
                            if (d2 <= 16.0) {
                                TriggerContext ctx = new TriggerContext(level)
                                        .source(player).target(player).pos(ap);
                                List<String> conds = d2 <= 1.0
                                        ? fired("on_contact", "on_near", "on_above")
                                        : fired("on_near");
                                dispatch(level, ctx, List.of(a), conds, player);
                                dispatchPassive(level, ctx, List.of(a), player);
                            }
                        }
                        case ENTITY, ITEM, BEHAVIOR, DIMENSION, PLAYER_DATA -> {
                            if (player.getUUID().toString().equals(a.target.ref)) {
                                TriggerContext ctx = new TriggerContext(level)
                                        .source(player).target(player);
                                dispatch(level, ctx, List.of(a), fired("on_hold", "on_near"), player);
                                dispatchPassive(level, ctx, List.of(a), player);
                            }
                        }
                    }
                }
            }

            for (Anomaly a : all) {
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
