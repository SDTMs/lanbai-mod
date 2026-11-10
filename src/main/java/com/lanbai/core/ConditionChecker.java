package com.lanbai.core;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;

import java.util.Map;

/**
 * 效果前置条件判定。
 *
 * JSON 里 effects[].when = { "hp_below": 10 } 这类写法，
 * 全部条件满足才执行该效果。支持：
 *   hp_below / hp_above / hp_ratio_below
 *   is_player / is_mob / sneaking
 *   has_tag / random / time(day|night) / weather(rain|clear|thunder) / dimension
 */
public final class ConditionChecker {

    private ConditionChecker() {
    }

    public static boolean check(Map<String, Object> when, TriggerContext ctx) {
        if (when == null || when.isEmpty()) return true;
        Entity t = ctx.target != null ? ctx.target : ctx.source;
        for (Map.Entry<String, Object> en : when.entrySet()) {
            if (!one(en.getKey(), en.getValue(), ctx, t)) return false;
        }
        return true;
    }

    private static boolean one(String k, Object v, TriggerContext ctx, Entity t) {
        switch (k) {
            case "hp_below":
                return t instanceof LivingEntity le && le.getHealth() < num(v, 0);
            case "hp_above":
                return t instanceof LivingEntity le && le.getHealth() > num(v, 0);
            case "hp_ratio_below":
                return t instanceof LivingEntity le && le.getMaxHealth() > 0
                        && (le.getHealth() / le.getMaxHealth()) < num(v, 0);
            case "is_player":
                return (t instanceof Player) == bool(v, true);
            case "is_mob":
                return (t instanceof Mob) == bool(v, true);
            case "sneaking":
                return t instanceof Player p && p.isShiftKeyDown() == bool(v, true);
            case "has_tag":
                return t != null && TagStore.has(t.getUUID(), String.valueOf(v));
            case "random":
                return Math.random() < num(v, 1);
            case "time": {
                long time = ctx.level.getDayTime() % 24000L;
                boolean night = time >= 13000 && time < 23000;
                String want = String.valueOf(v);
                if (want.equals("night")) return night;
                if (want.equals("day")) return !night;
                return true;
            }
            case "weather": {
                String want = String.valueOf(v);
                if (want.equals("rain")) return ctx.level.isRaining();
                if (want.equals("thunder")) return ctx.level.isThundering();
                if (want.equals("clear")) return !ctx.level.isRaining();
                return true;
            }
            case "dimension":
                return ctx.level.dimension().location().toString().equals(String.valueOf(v));
            default:
                return true;
        }
    }

    private static double num(Object v, double def) {
        return v instanceof Number n ? n.doubleValue() : def;
    }

    private static boolean bool(Object v, boolean def) {
        return v instanceof Boolean b ? b : def;
    }
}
