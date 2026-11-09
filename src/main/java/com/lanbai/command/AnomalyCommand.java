package com.lanbai.command;

import com.lanbai.core.Anomaly;
import com.lanbai.core.AnomalyLoader;
import com.lanbai.core.AnomalyRegistry;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * /lanbai list              列出所有收容物
 * /lanbai spawn <序号|名字>  在玩家位置创建
 * /lanbai info <序号>        查看详情
 * /lanbai reload            重载数据
 * /lanbai clear             清空
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
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "  §e" + n + ". §f" + a.name
                                            + " §7(" + a.effects.size() + "效果)"), false);
                            if (n > 200) break;
                        }
                        return 1;
                    }))
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
                                        ServerLevel level = (ServerLevel) p.level();
                                        var pos = p.blockPosition();
                                        a.target.ref = "minecraft:overworld;" + pos.getX() + "," + pos.getY() + "," + pos.getZ();
                                        a.target.scope = Anomaly.BindTarget.Scope.INSTANCE;
                                        AnomalyRegistry.get().register(a);
                                        ctx.getSource().sendSuccess(() -> Component.literal(
                                                "§a已创建收容物: §f" + a.name + " §7于 " + pos.toShortString()), false);
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
                                                "§6" + a.name + " §7(" + a.id + ")"), false);
                                        for (Anomaly.Effect e : a.effects) {
                                            String t = e.trigger == null ? "被动" : String.join("/", e.trigger.conditions);
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "  §b" + e.id + " §7depth=" + e.depth + " 触发=" + t), false);
                                        }
                                        return 1;
                                    })))
                    .then(Commands.literal("reload").executes(ctx -> {
                        AnomalyRegistry.get().clear();
                        int n = AnomalyLoader.load();
                        ctx.getSource().sendSuccess(() -> Component.literal("§a已重载 " + n + " 个"), false);
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

    private static Anomaly find(String arg) {
        List<Anomaly> all = List.copyOf(AnomalyRegistry.get().all());
        // 先按序号
        try {
            int idx = Integer.parseInt(arg);
            if (idx >= 1 && idx <= all.size()) return all.get(idx - 1);
        } catch (NumberFormatException ignored) {
        }
        // 再按名字
        for (Anomaly a : all) {
            if (a.name.equals(arg) || a.name.contains(arg)) return a;
        }
        return null;
    }
}
