package com.lanbai.command;

import com.lanbai.core.Anomaly;
import com.lanbai.core.AnomalyLoader;
import com.lanbai.core.AnomalyRegistry;
import com.lanbai.core.Scheduler;
import com.lanbai.core.TagStore;
import com.lanbai.core.effect.EffectExecutors;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.List;

/**
 * /lanbai list                    列出所有收容物
 * /lanbai spawn <序号|名字>        在玩家位置创建（实体/方块/物品）
 * /lanbai info <序号|名字>         查看详情
 * /lanbai reload                  热重载磁盘上的 anomalies.json
 * /lanbai file                    显示数据文件路径
 * /lanbai clear                   清空
 */
public class AnomalyCommand {

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("lanbai")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("list").executes(ctx -> {
                        var all = AnomalyRegistry.get().all();
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§6[LanBai] 共 " + all.size() + " 个收容物"), false);
                        int i = 1;
                        for (Anomaly a : all) {
                            int n = i++;
                            if (n > 300) break;
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "  §e" + n + ". §f" + a.name + " §7(" + a.effects.size() + "效果)"), false);
                        }
                        return 1;
                    }))
                    .then(Commands.literal("give")
                            .then(Commands.argument("target", com.mojang.brigadier.arguments.StringArgumentType.string())
                                    .executes(ctx -> {
                                        String arg = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "target");
                                        Player p = ctx.getSource().getPlayerOrException();
                                        Anomaly a = find(arg);
                                        if (a == null) {
                                            ctx.getSource().sendFailure(Component.literal("§c未找到: " + arg));
                                            return 0;
                                        }
                                        String msg = grant(a, p);
                                        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                                        return 1;
                                    })))
                    .then(Commands.literal("spawn")
                            .then(Commands.argument("target", com.mojang.brigadier.arguments.StringArgumentType.string())
                                    .executes(ctx -> {
                                        String arg = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "target");
                                        Player p = ctx.getSource().getPlayerOrException();
                                        Anomaly a = find(arg);
                                        if (a == null) {
                                            ctx.getSource().sendFailure(Component.literal("§c未找到: " + arg));
                                            return 0;
                                        }
                                        String msg = materialize(a, p);
                                        ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
                                        return 1;
                                    })))
                    .then(Commands.literal("info")
                            .then(Commands.argument("target", com.mojang.brigadier.arguments.StringArgumentType.string())
                                    .executes(ctx -> {
                                        String arg = com.mojang.brigadier.arguments.StringArgumentType.getString(ctx, "target");
                                        Anomaly a = find(arg);
                                        if (a == null) {
                                            ctx.getSource().sendFailure(Component.literal("§c未找到"));
                                            return 0;
                                        }
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§6" + a.name + " §7(" + a.id + ") §f绑定:" + a.target.scope + "/" + a.target.kind), false);
                                        for (Anomaly.Effect e : a.effects) {
                                            String t = e.trigger == null ? "§e被动" : "§b" + String.join("/", e.trigger.conditions);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "  §d" + e.id + " §7depth=" + e.depth + " 触发=" + t), false);
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("reload").executes(ctx -> {
                        var server = ctx.getSource().getServer();
                        // 清掉旧状态：延迟任务、标签、属性残留
                        Scheduler.clear();
                        TagStore.clearAll();
                        EffectExecutors.clearAllModifiers(server);
                        AnomalyRegistry.get().clear();
                        int n = AnomalyLoader.load();
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§a已重载 " + n + " 个 §7(" + AnomalyLoader.lastSource + ")"), false);
                        return 1;
                    }))
                    .then(Commands.literal("file").executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§e数据文件: §f" + AnomalyLoader.dataFile()), false);
                        ctx.getSource().sendSuccess(() -> Component.literal(
                                "§e上次来源: §f" + AnomalyLoader.lastSource), false);
                        return 1;
                    }))
                    .then(Commands.literal("clear").executes(ctx -> {
                        int n = AnomalyRegistry.get().size();
                        AnomalyRegistry.get().clear();
                        ctx.getSource().sendSuccess(() -> Component.literal("§a已清空 " + n + " 个"), false);
                        return 1;
                    })));
        });
    }

    /**
     * 发放收容物：生成一件带 NBT 身份的物品，手持即生效。
     * 任意物品都能当载体（由 anomaly.icon 指定外观）。
     */
    static String grant(Anomaly a, Player p) {
        Item icon = BuiltInRegistries.ITEM.get(
                new ResourceLocation(a.icon == null || a.icon.isEmpty() ? "minecraft:paper" : a.icon));
        if (icon == null || icon == net.minecraft.world.item.Items.AIR) {
            icon = net.minecraft.world.item.Items.PAPER;
        }
        ItemStack st = new ItemStack(icon);
        st.setHoverName(Component.literal("§6§l" + a.name));

        var tag = st.getOrCreateTag();
        tag.putString("lanbai_anomaly", a.id);

        var display = st.getOrCreateTagElement("display");
        var lore = new ListTag();
        String body = (a.desc == null || a.desc.isEmpty())
                ? "§7收容物 · " + a.id
                : a.desc;
        for (String line : body.split("\\n")) {
            lore.add(StringTag.valueOf(Component.Serializer.toJson(
                    Component.literal("§7" + line))));
        }
        lore.add(StringTag.valueOf(Component.Serializer.toJson(
                Component.literal("§8[收容物] 手持即生效"))));
        display.put("Lore", lore);

        if (!p.getInventory().add(st)) p.drop(st, false);
        return "§a已发放 §f" + a.name + " §7→ " + icon.getDescriptionId().replace("item.minecraft.", "");
    }

    /**
     * 把收容物「实体化」到玩家身边：根据绑定类型创建对应的东西
     */
    private static String materialize(Anomaly a, Player p) {
        ServerLevel level = (ServerLevel) p.level();
        BlockPos pos = p.blockPosition();
        AnomalyRegistry reg = AnomalyRegistry.get();

        // 先移除旧的索引
        reg.unregister(a.id);

        switch (a.target.kind) {
            case ENTITY -> {
                String entId = "minecraft:zombie";
                for (Anomaly.Effect e : a.effects) {
                    if ("ef_spawn".equals(e.id)) {
                        entId = e.strVal("entity", "minecraft:zombie");
                        break;
                    }
                }
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(new ResourceLocation(entId));
                if (type != null) {
                    Entity e = type.create(level);
                    if (e != null) {
                        e.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5);
                        level.addFreshEntity(e);
                        a.target.scope = Anomaly.BindTarget.Scope.INSTANCE;
                        a.target.ref = e.getUUID().toString();
                        reg.register(a);
                        return "§a已创建 §f" + a.name + " §7→ 实体 " + entId + " @ " + e.blockPosition().toShortString();
                    }
                }
                return "§c生成实体失败";
            }
            case BLOCK, POS, AREA -> {
                BlockPos target = pos.below();
                Block b = Blocks.STONE;
                for (Anomaly.Effect e : a.effects) {
                    if ("ef_set_block".equals(e.id)) {
                        Block tmp = BuiltInRegistries.BLOCK.get(new ResourceLocation(e.strVal("block", "minecraft:stone")));
                        if (tmp != null) b = tmp;
                        break;
                    }
                }
                level.setBlockAndUpdate(target, b.defaultBlockState());
                a.target.scope = Anomaly.BindTarget.Scope.INSTANCE;
                a.target.ref = level.dimension().location() + ";" + target.getX() + "," + target.getY() + "," + target.getZ();
                a.target.persistent = true;
                reg.register(a);
                return "§a已创建 §f" + a.name + " §7→ 方块 @ " + target.toShortString();
            }
            case ITEM -> {
                ItemStack stack = new ItemStack(net.minecraft.world.item.Items.DIAMOND_SWORD);
                stack.setHoverName(Component.literal("§6" + a.name));
                if (!p.getInventory().add(stack)) {
                    p.drop(stack, false);
                }
                a.target.scope = Anomaly.BindTarget.Scope.INSTANCE;
                a.target.ref = p.getUUID().toString();
                reg.register(a);
                return "§a已创建 §f" + a.name + " §7→ 物品已放入背包";
            }
            case BEHAVIOR -> {
                a.target.scope = Anomaly.BindTarget.Scope.INSTANCE;
                a.target.ref = p.getUUID().toString();
                reg.register(a);
                return "§a已创建 §f" + a.name + " §7→ 绑定行为到玩家";
            }
            default -> {
                a.target.scope = Anomaly.BindTarget.Scope.INSTANCE;
                a.target.ref = p.getUUID().toString();
                reg.register(a);
                return "§a已创建 §f" + a.name + " §7→ 绑定到玩家";
            }
        }
    }

    private static Anomaly find(String arg) {
        List<Anomaly> all = List.copyOf(AnomalyRegistry.get().all());
        try {
            int idx = Integer.parseInt(arg);
            if (idx >= 1 && idx <= all.size()) return all.get(idx - 1);
        } catch (NumberFormatException ignored) {
        }
        for (Anomaly a : all) {
            if (a.name.equals(arg) || a.id.equals(arg)) return a;
        }
        for (Anomaly a : all) {
            if (a.name.contains(arg)) return a;
        }
        return null;
    }
}
