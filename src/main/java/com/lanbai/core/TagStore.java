package com.lanbai.core;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 实体标签存储：给实体打任意字符串标签，可设过期时间。
 * 配合效果参数 when.has_tag 实现「被标记的人才受影响」这类规则。
 */
public final class TagStore {

    private static final Map<UUID, Map<String, Long>> TAGS = new HashMap<>();

    private TagStore() {
    }

    /**
     * @param expireAt 过期时刻（Scheduler.now() 基准），0 表示永久
     */
    public static void add(UUID id, String tag, long expireAt) {
        if (id == null || tag == null || tag.isEmpty()) return;
        TAGS.computeIfAbsent(id, k -> new HashMap<>()).put(tag, expireAt);
    }

    public static boolean has(UUID id, String tag) {
        if (id == null || tag == null) return false;
        Map<String, Long> m = TAGS.get(id);
        if (m == null) return false;
        Long exp = m.get(tag);
        if (exp == null) return false;
        if (exp > 0 && exp <= Scheduler.now()) {
            m.remove(tag);
            return false;
        }
        return true;
    }

    public static void remove(UUID id, String tag) {
        Map<String, Long> m = TAGS.get(id);
        if (m != null) m.remove(tag);
    }

    public static void clear(UUID id) {
        if (id != null) TAGS.remove(id);
    }

    public static void clearAll() {
        TAGS.clear();
    }

    /** 清理过期标签，返回清理数量 */
    public static int cleanup() {
        int n = 0;
        long now = Scheduler.now();
        Iterator<Map.Entry<UUID, Map<String, Long>>> it = TAGS.entrySet().iterator();
        while (it.hasNext()) {
            Map<String, Long> m = it.next().getValue();
            m.entrySet().removeIf(en -> en.getValue() > 0 && en.getValue() <= now);
            n++;
            if (m.isEmpty()) it.remove();
        }
        return n;
    }
}
