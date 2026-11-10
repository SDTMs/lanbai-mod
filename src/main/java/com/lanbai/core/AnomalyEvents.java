package com.lanbai.core;

import com.lanbai.core.effect.EffectExecutors;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class AnomalyEvents {

    private final RulingEngine engine = new RulingEngine();
    private int tickCounter = 0;

    /** 装备状态缓存（检测穿戴/卸下） */
    private final Map<UUID, String> equipCache = new HashMap<>();
    /** 睡眠状态缓存 */
    private final Map<UUID, Boolean> sleepCache = new HashMap<>();
    /** 潜行状态缓存 */
    private final Map<UUID, Boolean> sneakCache = new HashMap<>();

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
                    fire(a, e, ctx, feedbackTo);
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
                if (e.trigger == null) fire(a, e, ctx, feedbackTo);
            }
        }
    }

    /**
     * 周期效果：只执行声明了 period 参数（>0）的被动效果。
     * 没有 period 的被动效果仍然只在 load/交互时执行，避免每 10 tick 刷屏。
     */
    private void dispatchPeriodic(ServerLevel level, TriggerContext ctx, List<Anomaly> anomalies,
                                  Player feedbackTo) {
        if (anomalies == null || anomalies.isEmpty()) return;
        for (Anomaly a : anomalies) {
            for (Anomaly.Effect e : a.effects) {
                if (e.trigger != null) continue;
                int period = e.intVal("period", 0);
                if (period <= 0) continue;
                if (tickCounter % Math.max(10, period) != 0) continue;
                fire(a, e, ctx, feedbackTo);
            }
        }
    }

    /** 执行前检查条件与延迟 */
    private void fire(Anomaly a, Anomaly.Effect e, TriggerContext ctx, Player feedbackTo) {
        if (!ConditionChecker.check(e.when, ctx)) return;
        if (e.delay > 0) {
            Scheduler.schedule(e.delay, () -> runOne(a, e, ctx, feedbackTo));
        } else {
            runOne(a, e, ctx, feedbackTo);
        }
    }

    /** 读取玩家手持/副手物品上的收容物身份证（NBT） */
    private static String heldAnomalyId(Player p) {
        ItemStack[] hands = {p.getMainHandItem(), p.getOffhandItem()};
        for (ItemStack st : hands) {
            if (st.isEmpty()) continue;
            var tag = st.getTag();
            if (tag != null && tag.contains("lanbai_anomaly")) {
                return tag.getString("lanbai_anomaly");
            }
        }
        return null;
    }

    /**
     * 该实体此刻生效的收容物：
     *   TYPE 实体绑定 + INSTANCE 绑定 + 手持/副手物品的 ITEM 绑定。
     */
    private static List<Anomaly> activeFor(Entity e) {
        List<Anomaly> list = new ArrayList<>(AnomalyRegistry.get().collectFor(e));
        if (e instanceof Player p) {
            // 1) NBT 身份（/lanbai give 发放的收容物）
            String nid = heldAnomalyId(p);
            if (nid != null) {
                Anomaly issued = AnomalyRegistry.get().byId(nid);
                if (issued != null && !list.contains(issued)) list.add(issued);
            }
            // 2) 物品类型匹配（旧方式）
            var main = BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem());
            var off = BuiltInRegistries.ITEM.getKey(p.getOffhandItem().getItem());
            String mm = main != null ? main.toString() : "";
            String oo = off != null ? off.toString() : "";
            for (Anomaly a : AnomalyRegistry.get().all()) {
                if (a.target == null || a.target.kind != Anomaly.BindTarget.Kind.ITEM) continue;
                if (a.target.scope != Anomaly.BindTarget.Scope.TYPE) continue;
                if (mm.equals(a.target.ref) || oo.equals(a.target.ref)) list.add(a);
            }
        }
        return list;
    }

    /**
     * 声明式否决检查：实体身上若有 ef_deny 且 type 匹配，该行为直接被拦下。
     * 这是「绝对特性」的核心——不需要触发，绑定即生效。
     */
    private static boolean denied(Entity entity, String type) {
        if (entity == null) return false;
        for (Anomaly a : activeFor(entity)) {
            for (Anomaly.Effect ef : a.effects) {
                if (!"ef_deny".equals(ef.id)) continue;
                String t = ef.strVal("type", "all");
                if (t.equals("all") || t.equals(type)) return true;
            }
        }
        return false;
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
        if (id.equals("ef_explode")) return "爆炸";
        if (id.equals("ef_lightning")) return "落雷";
        if (id.equals("ef_reflect")) return "反伤";
        if (id.equals("ef_shield")) return "护盾";
        if (id.equals("ef_give")) return "给予物品";
        if (id.equals("ef_take")) return "没收物品";
        if (id.equals("ef_fill")) return "填充方块";
        if (id.equals("ef_copy")) return "复制";
        if (id.equals("ef_transform")) return "变形";
        if (id.equals("ef_drain")) return "吸血";
        if (id.equals("ef_launch")) return "弹射";
        if (id.equals("ef_scan")) return "扫描";
        if (id.equals("ef_message")) return "提示";
        if (id.equals("ef_xp")) return "经验";
        if (id.equals("ef_dispel")) return "驱散";
        if (id.equals("ef_freeze")) return "冻结";
        return id;
    }

    public static void register() {
        new AnomalyEvents().hook();
    }

    /** 装备槽位签名，用于检测穿戴变化 */
    private static String equipSignature(ServerPlayer p) {
        StringBuilder sb = new StringBuilder(64);
        EquipmentSlot[] slots = {EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
        for (EquipmentSlot s : slots) {
            var st = p.getItemBySlot(s);
            if (st.isEmpty()) {
                sb.append('-');
            } else {
                var key = BuiltInRegistries.ITEM.getKey(st.getItem());
                sb.append(key == null ? '?' : key.toString());
            }
            sb.append('|');
        }
        return sb.toString();
    }

    /** persistent 方块应恢复成的状态：取该收容物 ef_set_block 的方块，没写才退回石头 */
    private static BlockState expectedState(Anomaly a) {
        for (Anomaly.Effect e : a.effects) {
            if ("ef_set_block".equals(e.id)) {
                Block b = BuiltInRegistries.BLOCK.get(new ResourceLocation(e.strVal("block", "minecraft:stone")));
                if (b != null) return b.defaultBlockState();
            }
        }
        return Blocks.STONE.defaultBlockState();
    }

    private void hook() {
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            if (denied(player, "interact")) return InteractionResult.FAIL;
            TriggerContext ctx = new TriggerContext(level)
                    .source(player).target(entity).item(player.getItemInHand(hand));
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity),
                    fired("on_use", "on_contact"), player);
            return InteractionResult.PASS;
        });

        // 右键方块：方块 + 手持物都能触发
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!(world instanceof ServerLevel level)) return InteractionResult.PASS;
            if (denied(player, "interact")) return InteractionResult.FAIL;
            BlockPos pos = hitResult.getBlockPos();
            TriggerContext ctx = new TriggerContext(level)
                    .source(player).target(player).pos(pos).item(player.getItemInHand(hand));
            List<Anomaly> list = new ArrayList<>(AnomalyRegistry.get().getBlockAnomalies(pos));
            list.addAll(activeFor(player));
            dispatch(level, ctx, list, fired("on_use_block", "on_use", "on_contact"), player);
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
            if (denied(player, "break")) return false;
            List<Anomaly> list = AnomalyRegistry.get().getBlockAnomalies(pos);
            for (Anomaly ba : list) {
                for (Anomaly.Effect be : ba.effects) {
                    if ("ef_deny".equals(be.id)) {
                        String bt = be.strVal("type", "all");
                        if (bt.equals("all") || bt.equals("break")) return false;
                    }
                }
            }
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
            if (denied(entity, "damage")) return false;
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

        ServerLivingEntityEvents.ALLOW_DEATH.register((entity, source, amount) -> !denied(entity, "death"));

        ServerLivingEntityEvents.AFTER_DEATH.register((entity, source) -> {
            if (!(entity.level() instanceof ServerLevel level)) return;
            TriggerContext ctx = new TriggerContext(level)
                    .source(source.getEntity()).target(entity);
            dispatch(level, ctx, AnomalyRegistry.get().collectFor(entity),
                    fired("on_death", "on_kill"), null);
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Scheduler.tick();
            tickCounter++;
            if (tickCounter % 10 != 0) return;

            if (tickCounter % 200 == 0) TagStore.cleanup();

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
                        case ITEM -> {
                            if (a.target.scope == Anomaly.BindTarget.Scope.INSTANCE) {
                                if (player.getUUID().toString().equals(a.target.ref)) {
                                    TriggerContext ctx = new TriggerContext(level)
                                            .source(player).target(player);
                                    dispatch(level, ctx, List.of(a), fired("on_hold", "on_near"), player);
                                    dispatchPassive(level, ctx, List.of(a), player);
                                    dispatchPeriodic(level, ctx, List.of(a), player);
                                }
                            } else {
                                // TYPE 绑定：手持/副手拿着该物品即生效
                                var main = BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem());
                                var off = BuiltInRegistries.ITEM.getKey(player.getOffhandItem().getItem());
                                boolean match = a.id.equals(heldAnomalyId(player))
                                        || (main != null && main.toString().equals(a.target.ref))
                                        || (off != null && off.toString().equals(a.target.ref));
                                if (match) {
                                    TriggerContext ctx = new TriggerContext(level)
                                            .source(player).target(player);
                                    dispatch(level, ctx, List.of(a), fired("on_hold", "on_near"), player);
                                    dispatchPassive(level, ctx, List.of(a), player);
                                    dispatchPeriodic(level, ctx, List.of(a), player);
                                }
                            }
                        }
                        case ENTITY, BEHAVIOR, DIMENSION, PLAYER_DATA -> {
                            if (player.getUUID().toString().equals(a.target.ref)) {
                                TriggerContext ctx = new TriggerContext(level)
                                        .source(player).target(player);
                                dispatch(level, ctx, List.of(a), fired("on_hold", "on_near"), player);
                                dispatchPassive(level, ctx, List.of(a), player);
                            }
                        }
                    }
                }

                // 穿戴 / 睡眠 / 潜行：用状态变化检测，避免依赖不存在的事件 API
                String ek = equipSignature(player);
                String oldEk = equipCache.put(player.getUUID(), ek);
                if (oldEk != null && !oldEk.equals(ek)) {
                    TriggerContext ctx = new TriggerContext(level).source(player).target(player);
                    dispatch(level, ctx, activeFor(player),
                            fired("on_equip", "on_unequip"), player);
                }

                boolean sleeping = player.isSleeping();
                Boolean oldSleep = sleepCache.put(player.getUUID(), sleeping);
                if (oldSleep != null && oldSleep != sleeping) {
                    TriggerContext ctx = new TriggerContext(level).source(player).target(player);
                    dispatch(level, ctx, activeFor(player),
                            fired(sleeping ? "on_sleep" : "on_wake"), player);
                }

                boolean sneaking = player.isShiftKeyDown();
                Boolean oldSneak = sneakCache.put(player.getUUID(), sneaking);
                if (oldSneak != null && oldSneak != sneaking) {
                    TriggerContext ctx = new TriggerContext(level).source(player).target(player);
                    dispatch(level, ctx, activeFor(player),
                            fired(sneaking ? "on_sneak" : "on_stand"), player);
                }

                // TYPE 绑定的周期效果：只扫描玩家附近，控制开销
                for (Entity near : level.getEntitiesOfClass(Entity.class, new AABB(ppos).inflate(32))) {
                    List<Anomaly> typed = AnomalyRegistry.get().getTypeAnomalies(near);
                    if (typed.isEmpty()) continue;
                    TriggerContext ctx = new TriggerContext(level)
                            .source(player).target(near).pos(near.blockPosition());
                    dispatchPeriodic(level, ctx, typed, player);
                }
            }

            // persistent 方块：按收容物自己声明的方块恢复
            for (Anomaly a : all) {
                if (a.target == null) continue;
                if (a.target.kind == Anomaly.BindTarget.Kind.POS && a.target.persistent) {
                    try {
                        BlockPos pos = AnomalyRegistry.parsePos(a.target.ref);
                        ServerLevel level = server.overworld();
                        BlockState want = expectedState(a);
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
