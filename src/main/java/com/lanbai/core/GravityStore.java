package com.lanbai.core;

import net.minecraft.world.entity.Entity;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 实体重力因子存储（1.0=正常，0=失重，2=双倍）。
 * 由 LivingEntity#travel 的 mixin 每 tick 读取并施加。
 */
public final class GravityStore {

    private static final Map<UUID, Float> GRAVITY = new HashMap<>();

    private GravityStore() {
    }

    public static void set(UUID id, float factor) {
        if (id == null) return;
        if (Math.abs(factor - 1.0f) < 0.001f) GRAVITY.remove(id);
        else GRAVITY.put(id, factor);
    }

    public static float get(Entity e) {
        if (e == null) return 1.0f;
        Float f = GRAVITY.get(e.getUUID());
        return f == null ? 1.0f : f;
    }

    public static void clear() {
        GRAVITY.clear();
    }

    public static int size() {
        return GRAVITY.size();
    }
}
