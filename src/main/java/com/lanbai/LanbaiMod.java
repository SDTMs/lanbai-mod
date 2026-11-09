package com.lanbai;

import com.lanbai.core.Anomaly;
import com.lanbai.core.AnomalyEvents;
import com.lanbai.core.AnomalyLoader;
import com.lanbai.core.AnomalyRegistry;
import com.lanbai.command.AnomalyCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 蓝白社收容物系统 · Fabric 主类
 */
public class LanbaiMod implements ModInitializer {

    public static final String MODID = "lanbai";
    public static final Logger LOGGER = LoggerFactory.getLogger("LanBai");

    @Override
    public void onInitialize() {
        LOGGER.info("[LanBai] 收容物系统加载中...");
        AnomalyEvents.register();
        AnomalyCommand.register();
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            int loaded = AnomalyLoader.load();
            if (loaded == 0) {
                registerDemoAnomalies();
            }
            LOGGER.info("[LanBai] 已注册 {} 个收容物", AnomalyRegistry.get().size());
        });
        LOGGER.info("[LanBai] 初始化完成");
    }

    private static void registerDemoAnomalies() {
        AnomalyRegistry reg = AnomalyRegistry.get();
        reg.clear();

        // 1. 快速僵尸：实体类型 + 无触发 + 移速
        Anomaly fastZombie = new Anomaly("demo_fast_zombie", "快速僵尸",
                new Anomaly.BindTarget(Anomaly.BindTarget.Scope.TYPE,
                        Anomaly.BindTarget.Kind.ENTITY, "minecraft:zombie"));
        fastZombie.addEffect(new Anomaly.Effect("ef_set_attr", reg.allocateDepth(1000, 999999))
                .set("attr", "minecraft:generic.movement_speed")
                .set("value", 4.0)
                .set("mode", "multiply_base"));
        reg.register(fastZombie);

        // 2. 不可破坏方块
        Anomaly unbreakable = new Anomaly("demo_unbreakable", "不可破坏方块",
                new Anomaly.BindTarget(Anomaly.BindTarget.Scope.INSTANCE,
                        Anomaly.BindTarget.Kind.BLOCK, "minecraft:overworld;0,64,0"));
        unbreakable.target.persistent = true;
        unbreakable.addEffect(new Anomaly.Effect("ef_immune", reg.allocateDepth(1000, 999999))
                .set("type", "all_break"));
        reg.register(unbreakable);

        // 3. 凋零草方块
        Anomaly witherGrass = new Anomaly("demo_wither_grass", "凋零草方块",
                new Anomaly.BindTarget(Anomaly.BindTarget.Scope.INSTANCE,
                        Anomaly.BindTarget.Kind.BLOCK, "minecraft:overworld;10,64,10"));
        witherGrass.addEffect(new Anomaly.Effect("ef_status", reg.allocateDepth(1000, 999999))
                .set("effect", "minecraft:wither")
                .set("level", 0)
                .set("duration", -1)
                .withTriggerAnd("on_contact", "on_above"));
        reg.register(witherGrass);
    }
}
