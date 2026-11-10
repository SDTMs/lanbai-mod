package com.lanbai.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 一件收容物 = 绑定对象 + 若干效果
 */
public class Anomaly {
    public String id;
    public String name;
    public BindTarget target;
    public List<Effect> effects = new ArrayList<>();
    public boolean intraConflict = false;
    public String createdBy = "system";
    /** 原著所属：B01 非人基因统合体 / B02 脑洞大爆炸 / B03 蓝白社 */
    public String book = "B00";
    /** 发放时的物品图标 */
    public String icon = "minecraft:paper";
    /** 物品说明（lore，用 \n 分行） */
    public String desc = "";

    public Anomaly() {
    }

    public Anomaly(String id, String name, BindTarget target) {
        this.id = id;
        this.name = name;
        this.target = target;
    }

    public Anomaly addEffect(Effect e) {
        this.effects.add(e);
        return this;
    }

    @Override
    public String toString() {
        return "Anomaly{" + id + ", " + name + ", effects=" + effects.size() + "}";
    }

    // ==================== 绑定对象 ====================

    public static class BindTarget {
        public Scope scope = Scope.INSTANCE;
        public Kind kind = Kind.ENTITY;
        /** 引用：UUID / BlockPos / EntityType / ItemId / 条件串 */
        public String ref = "";
        /** 方块被破坏后是否强制恢复 */
        public boolean persistent = false;

        public BindTarget() {
        }

        public BindTarget(Scope scope, Kind kind, String ref) {
            this.scope = scope;
            this.kind = kind;
            this.ref = ref;
        }

        public enum Scope {
            INSTANCE, TYPE, ALL
        }

        public enum Kind {
            ENTITY, BLOCK, ITEM, DIMENSION, POS, AREA, BEHAVIOR, PLAYER_DATA
        }
    }

    // ==================== 效果 ====================

    public static class Effect {
        public String id;
        /** null 表示纯被动 */
        public TriggerSpec trigger;
        public Map<String, EffectParam> params = new HashMap<>();
        /** 执行前置条件（hp_below / has_tag / time / random / ...），null 表示无条件 */
        public Map<String, Object> when;
        /** 延迟执行（tick），0 表示立即 */
        public int delay = 0;
        public long depth;
        public String conflictGroup;
        /** 还原程度：FULL / PARTIAL / REDUCED */
        public String fidelity = "FULL";

        public Effect() {
        }

        public Effect(String id, long depth) {
            this.id = id;
            this.depth = depth;
        }

        public EffectParam param(String key) {
            return params.get(key);
        }

        public Object val(String key, Object def) {
            EffectParam p = params.get(key);
            return p == null || p.value == null ? def : p.value;
        }

        public int intVal(String key, int def) {
            Object v = val(key, def);
            return v instanceof Number n ? n.intValue() : def;
        }

        public double doubleVal(String key, double def) {
            Object v = val(key, def);
            return v instanceof Number n ? n.doubleValue() : def;
        }

        public String strVal(String key, String def) {
            Object v = val(key, def);
            return v == null ? def : String.valueOf(v);
        }

        public Effect set(String key, Object value) {
            params.put(key, new EffectParam(ParamType.auto(value), value));
            return this;
        }

        public Effect setRange(String key, double min, double max, RandomMode mode) {
            EffectParam p = new EffectParam(ParamType.NUMBER, null);
            p.min = min;
            p.max = max;
            p.randomMode = mode;
            params.put(key, p);
            return this;
        }

        public Effect withTrigger(String... conditions) {
            this.trigger = new TriggerSpec(List.of(conditions), TriggerMode.OR);
            return this;
        }

        public Effect withTriggerAnd(String... conditions) {
            this.trigger = new TriggerSpec(List.of(conditions), TriggerMode.AND);
            return this;
        }

        public Effect withDepth(long d) {
            this.depth = d;
            return this;
        }

        public Effect withDelay(int ticks) {
            this.delay = ticks;
            return this;
        }

        public Effect when(Map<String, Object> cond) {
            this.when = cond;
            return this;
        }

        public Effect withGroup(String g) {
            this.conflictGroup = g;
            return this;
        }

        public Effect withFidelity(String f) {
            this.fidelity = f;
            return this;
        }

        @Override
        public String toString() {
            return id + "(d=" + depth + ")";
        }
    }

    // ==================== 触发规格 ====================

    public static class TriggerSpec {
        public List<String> conditions = new ArrayList<>();
        public TriggerMode mode = TriggerMode.OR;

        public TriggerSpec() {
        }

        public TriggerSpec(List<String> conditions, TriggerMode mode) {
            this.conditions = new ArrayList<>(conditions);
            this.mode = mode;
        }

        /** 空条件视为纯被动 */
        public boolean isPassive() {
            return conditions == null || conditions.isEmpty();
        }

        public boolean matches(List<String> fired) {
            if (isPassive()) return true;
            if (mode == TriggerMode.OR) {
                for (String c : conditions) {
                    if (fired.contains(c)) return true;
                }
                return false;
            }
            // AND
            return fired.containsAll(conditions);
        }
    }

    public enum TriggerMode {
        OR, AND
    }

    // ==================== 参数 ====================

    public static class EffectParam {
        public ParamType type;
        public Object value;
        public Double min;
        public Double max;
        public RandomMode randomMode = RandomMode.NONE;

        public EffectParam() {
        }

        public EffectParam(ParamType type, Object value) {
            this.type = type;
            this.value = value;
        }

        /** 生成时随机一次，把结果写入 value */
        public void resolveOnGenerate(java.util.Random rng) {
            if (randomMode == RandomMode.GENERATION && min != null && max != null) {
                double v = min + rng.nextDouble() * (max - min);
                this.value = (type == ParamType.INT) ? (Object) (int) Math.round(v) : (Object) v;
            }
        }

        /** 触发时取当前值 */
        public Object current(java.util.Random rng) {
            if (randomMode == RandomMode.TRIGGER && min != null && max != null) {
                double v = min + rng.nextDouble() * (max - min);
                return (type == ParamType.INT) ? (Object) (int) Math.round(v) : (Object) v;
            }
            return value;
        }
    }

    public enum ParamType {
        INT, FLOAT, NUMBER, STRING, ENUM, BOOL, VECTOR, LIST;

        public static ParamType auto(Object v) {
            if (v instanceof Integer) return INT;
            if (v instanceof Double || v instanceof Float) return FLOAT;
            if (v instanceof Number) return NUMBER;
            if (v instanceof Boolean) return BOOL;
            return STRING;
        }
    }

    public enum RandomMode {
        GENERATION, TRIGGER, NONE
    }
}
