package com.lanbai.core;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 触发上下文。每次事件触发时构造，传给效果执行器。
 */
public class TriggerContext {
    public ServerLevel level;
    @Nullable public Entity source;
    @Nullable public Entity target;
    @Nullable public BlockPos pos;
    @Nullable public ItemStack item;
    public long gameTime;

    /** 触发类型特有数据（按键序列/聊天内容/配方等） */
    public Map<String, Object> extra = new HashMap<>();

    /** 同一收容物内效果间传数据的容器 */
    public Map<String, Object> shared = new HashMap<>();

    public TriggerContext(ServerLevel level) {
        this.level = level;
        this.gameTime = level.getGameTime();
    }

    public TriggerContext source(Entity e) {
        this.source = e;
        return this;
    }

    public TriggerContext target(Entity e) {
        this.target = e;
        return this;
    }

    public TriggerContext pos(BlockPos p) {
        this.pos = p;
        return this;
    }

    public TriggerContext item(ItemStack s) {
        this.item = s;
        return this;
    }

    public TriggerContext put(String key, Object value) {
        this.extra.put(key, value);
        return this;
    }

    public Object get(String key) {
        return this.extra.get(key);
    }

    /**
     * 判定缓存键：稳定字段拼接。
     * 注意：物品用 类型ID + NBT hash，区分不同附魔。
     */
    public String hash(Anomaly anomalyA, Effect effectA, Anomaly anomalyB, Effect effectB) {
        StringBuilder sb = new StringBuilder(128);
        sb.append(anomalyA.id).append('#').append(anomalyA.effects.indexOf(effectA));
        sb.append('|');
        sb.append(anomalyB == null ? "-" : anomalyB.id).append('#')
          .append(anomalyB == null ? "-" : anomalyB.effects.indexOf(effectB));
        sb.append('|');
        sb.append(source != null ? source.getUUID().toString() : "-");
        sb.append('|');
        sb.append(target != null ? String.valueOf(target.getId()) : "-1");
        sb.append('|');
        sb.append(pos != null ? pos.toShortString() : "-");
        sb.append('|');
        if (item != null) {
            sb.append(item.getItem().getDescriptionId()).append('#');
            var tag = item.getTag();
            sb.append(tag != null ? tag.hashCode() : 0);
        } else {
            sb.append('-');
        }
        sb.append('|').append(gameTime / 20);
        return sb.toString();
    }
}
