package com.lanbai.core;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.*;

/**
 * 收容物注册表。服务端权威。
 */
public class AnomalyRegistry {

    private static final AnomalyRegistry INSTANCE = new AnomalyRegistry();

    public static AnomalyRegistry get() {
        return INSTANCE;
    }

    /** 全部收容物（id -> anomaly） */
    private final Map<String, Anomaly> all = new LinkedHashMap<>();

    /** INSTANCE 级：实体 UUID -> 收容物 */
    private final Map<UUID, List<Anomaly>> byEntity = new HashMap<>();

    /** INSTANCE 级：方块坐标 -> 收容物 */
    private final Map<BlockPos, List<Anomaly>> byBlock = new HashMap<>();

    /** TYPE 级：实体类型 -> 收容物 */
    private final Map<ResourceLocation, List<Anomaly>> byEntityType = new HashMap<>();

    /** 深度分配器 */
    private final Set<Long> usedDepths = new HashSet<>();
    private final Random rng = new Random();

    // ==================== 注册 ====================

    public void register(Anomaly a) {
        all.put(a.id, a);
        index(a);
    }

    public void unregister(String id) {
        Anomaly a = all.remove(id);
        if (a == null) return;
        byEntity.values().forEach(l -> l.remove(a));
        byBlock.values().forEach(l -> l.remove(a));
        byEntityType.values().forEach(l -> l.remove(a));
    }

    private void index(Anomaly a) {
        if (a.target == null) return;
        switch (a.target.kind) {
            case ENTITY -> {
                if (a.target.scope == Anomaly.BindTarget.Scope.INSTANCE) {
                    try {
                        UUID uuid = UUID.fromString(a.target.ref);
                        byEntity.computeIfAbsent(uuid, k -> new ArrayList<>()).add(a);
                    } catch (Exception ignored) {
                    }
                } else if (a.target.scope == Anomaly.BindTarget.Scope.TYPE) {
                    byEntityType.computeIfAbsent(new ResourceLocation(a.target.ref), k -> new ArrayList<>()).add(a);
                }
            }
            case BLOCK, POS -> {
                try {
                    BlockPos p = parsePos(a.target.ref);
                    byBlock.computeIfAbsent(p, k -> new ArrayList<>()).add(a);
                } catch (Exception ignored) {
                }
            }
            default -> {
                // DIMENSION / AREA / BEHAVIOR / PLAYER_DATA / ITEM 暂存于 all，由各自事件查表
            }
        }
    }

    public static BlockPos parsePos(String ref) {
        // 格式 "dim;x,y,z" 或 "x,y,z"
        String s = ref.contains(";") ? ref.substring(ref.indexOf(';') + 1) : ref;
        String[] parts = s.split(",");
        return new BlockPos(Integer.parseInt(parts[0].trim()),
                Integer.parseInt(parts[1].trim()),
                Integer.parseInt(parts[2].trim()));
    }

    // ==================== 查询 ====================

    public Collection<Anomaly> all() {
        return all.values();
    }

    public Anomaly byId(String id) {
        return all.get(id);
    }

    public List<Anomaly> getInstanceAnomalies(Entity e) {
        return byEntity.getOrDefault(e.getUUID(), List.of());
    }

    public List<Anomaly> getTypeAnomalies(Entity e) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType());
        return key == null ? List.of() : byEntityType.getOrDefault(key, List.of());
    }

    public List<Anomaly> getBlockAnomalies(BlockPos pos) {
        return byBlock.getOrDefault(pos, List.of());
    }

    /**
     * 收集一个实体身上所有生效的收容物（两层：TYPE + INSTANCE，互不覆盖）
     */
    public List<Anomaly> collectFor(Entity e) {
        List<Anomaly> list = new ArrayList<>();
        list.addAll(getTypeAnomalies(e));
        list.addAll(getInstanceAnomalies(e));
        return list;
    }

    // ==================== 深度 ====================

    public long allocateDepth(long min, long max) {
        for (int i = 0; i < 1000; i++) {
            long v = min + (long) (rng.nextDouble() * (max - min));
            if (usedDepths.add(v)) return v;
        }
        return min + (long) (rng.nextDouble() * (max - min));
    }

    public void clear() {
        all.clear();
        byEntity.clear();
        byBlock.clear();
        byEntityType.clear();
        usedDepths.clear();
    }

    public int size() {
        return all.size();
    }
}
