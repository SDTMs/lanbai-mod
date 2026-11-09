package com.lanbai.core;

import java.util.*;

/**
 * 判定引擎：互斥分组 + 深度比较 + 掷骰。
 *
 * 规则：
 *   1. 收集待触发效果
 *   2. 按 conflictGroup 分组
 *   3. 每组取 depth 最高者
 *   4. 同 depth 掷骰（结果写缓存，单次事件内一致）
 *   5. 非互斥效果全部执行
 */
public class RulingEngine {

    /** 显式互斥对 */
    private static final Set<String> CONFLICT_PAIRS = Set.of(
            "ef_damage|ef_immune",
            "ef_status|ef_immune",
            "ef_teleport|ef_lock",
            "ef_hide|ef_perceive",
            "ef_seal|ef_grant"
    );

    /** 判定缓存：单次事件内有效 */
    private final Map<String, Boolean> cache = new HashMap<>();
    private final Random rng = new Random();

    public void beginEvent() {
        cache.clear();
    }

    public void endEvent() {
        cache.clear();
    }

    public boolean conflict(String idA, String idB) {
        return CONFLICT_PAIRS.contains(idA + "|" + idB)
                || CONFLICT_PAIRS.contains(idB + "|" + idA);
    }

    /**
     * 从一组效果中决出胜者。
     *
     * @param candidates 同时触发的效果
     * @param ctx        上下文（用于缓存键）
     * @return 实际执行的效果列表
     */
    public List<Anomaly.Effect> resolve(List<Anomaly.Effect> candidates, TriggerContext ctx) {
        if (candidates.size() <= 1) return candidates;

        // 按 conflictGroup 分组；无组的独立
        Map<String, List<Anomaly.Effect>> groups = new LinkedHashMap<>();
        List<Anomaly.Effect> independents = new ArrayList<>();

        for (Anomaly.Effect e : candidates) {
            String g = e.conflictGroup;
            if (g == null || g.isEmpty()) {
                // 显式互斥对也能配对
                boolean paired = false;
                for (Anomaly.Effect other : candidates) {
                    if (other == e) continue;
                    if (conflict(e.id, other.id)) {
                        paired = true;
                        break;
                    }
                }
                if (!paired) independents.add(e);
                else groups.computeIfAbsent("pair:" + e.id, k -> new ArrayList<>()).add(e);
            } else {
                groups.computeIfAbsent(g, k -> new ArrayList<>()).add(e);
            }
        }

        List<Anomaly.Effect> result = new ArrayList<>(independents);

        for (Map.Entry<String, List<Anomaly.Effect>> en : groups.entrySet()) {
            List<Anomaly.Effect> list = en.getValue();
            if (list.size() == 1) {
                result.add(list.get(0));
                continue;
            }
            // 找最高 depth
            long max = Long.MIN_VALUE;
            for (Anomaly.Effect e : list) max = Math.max(max, e.depth);

            List<Anomaly.Effect> top = new ArrayList<>();
            for (Anomaly.Effect e : list) if (e.depth == max) top.add(e);

            if (top.size() == 1) {
                result.add(top.get(0));
            } else {
                // 同深度掷骰
                Anomaly.Effect a = top.get(0);
                Anomaly.Effect b = top.get(1);
                String key = ctx.hash(null, a, null, b);
                Boolean cached = cache.get(key);
                boolean aWins;
                if (cached != null) {
                    aWins = cached;
                } else {
                    aWins = rng.nextInt(100) < 50;
                    cache.put(key, aWins);
                }
                result.add(aWins ? a : b);
            }
        }

        return result;
    }

    /**
     * 同一收容物内部的冲突（intraConflict = true 时使用）
     */
    public List<Anomaly.Effect> resolveIntra(Anomaly anomaly, List<Anomaly.Effect> fired, TriggerContext ctx) {
        if (!anomaly.intraConflict) return fired;
        return resolve(fired, ctx);
    }
}
