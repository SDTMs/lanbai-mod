package com.lanbai.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.lanbai.LanbaiMod;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 classpath 读取收容物数据。
 * 资源路径：/data/lanbai/anomalies.json
 */
public class AnomalyLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static int load() {
        AnomalyRegistry reg = AnomalyRegistry.get();
        int count = 0;
        try (var is = AnomalyLoader.class.getResourceAsStream("/data/lanbai/anomalies.json")) {
            if (is == null) {
                LanbaiMod.LOGGER.warn("[LanBai] 未找到 anomalies.json");
                return 0;
            }
            var root = GSON.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8),
                    com.google.gson.JsonArray.class);
            for (var el : root) {
                try {
                    JsonObject o = el.getAsJsonObject();
                    Anomaly a = parse(o);
                    if (a != null) {
                        reg.register(a);
                        count++;
                    }
                } catch (Exception e) {
                    LanbaiMod.LOGGER.warn("[LanBai] 解析收容物失败: {}", e.getMessage());
                }
            }
        } catch (Exception e) {
            LanbaiMod.LOGGER.error("[LanBai] 加载 anomalies.json 失败", e);
        }
        LanbaiMod.LOGGER.info("[LanBai] 从 JSON 加载 {} 个收容物", count);
        return count;
    }

    private static Object toJava(com.google.gson.JsonElement v) {
        if (v.isJsonNull()) return null;
        var p = v.getAsJsonPrimitive();
        if (p.isBoolean()) return p.getAsBoolean();
        if (p.isNumber()) {
            double d = p.getAsDouble();
            return (d == Math.floor(d) && !Double.isInfinite(d)) ? (Object) (int) d : (Object) d;
        }
        return p.getAsString();
    }

    private static Anomaly parse(JsonObject o) {
        String id = o.get("id").getAsString();
        String name = o.get("name").getAsString();

        JsonObject bt = o.getAsJsonObject("bindTarget");
        Anomaly.BindTarget target = new Anomaly.BindTarget();
        target.scope = Anomaly.BindTarget.Scope.valueOf(bt.get("scope").getAsString());
        target.kind = Anomaly.BindTarget.Kind.valueOf(bt.get("kind").getAsString());
        target.ref = bt.get("ref").getAsString();
        target.persistent = bt.has("persistent") && bt.get("persistent").getAsBoolean();

        Anomaly a = new Anomaly(id, name, target);
        a.intraConflict = o.has("intraConflict") && o.get("intraConflict").getAsBoolean();

        for (var ee : o.getAsJsonArray("effects")) {
            JsonObject eo = ee.getAsJsonObject();
            Anomaly.Effect e = new Anomaly.Effect();
            e.id = eo.get("id").getAsString();

            if (eo.has("trigger") && !eo.get("trigger").isJsonNull()) {
                JsonObject t = eo.getAsJsonObject("trigger");
                List<String> conds = new ArrayList<>();
                if (t.has("conditions")) {
                    for (var c : t.getAsJsonArray("conditions")) conds.add(c.getAsString());
                }
                String mode = t.has("mode") ? t.get("mode").getAsString() : "OR";
                e.trigger = new Anomaly.TriggerSpec(conds, Anomaly.TriggerMode.valueOf(mode));
            }

            if (eo.has("params")) {
                for (Map.Entry<String, com.google.gson.JsonElement> en : eo.getAsJsonObject("params").entrySet()) {
                    com.google.gson.JsonElement val = en.getValue();
                    // 兼容两种格式：直接值 或 {value:..} / {min,max,randomMode}
                    if (val.isJsonObject() && !val.getAsJsonObject().has("uuid")) {
                        JsonObject p = val.getAsJsonObject();
                        if (p.has("value")) {
                            e.set(en.getKey(), toJava(p.get("value")));
                        } else if (p.has("min") && p.has("max")) {
                            double min = p.get("min").getAsDouble();
                            double max = p.get("max").getAsDouble();
                            String rm = p.has("randomMode") ? p.get("randomMode").getAsString() : "GENERATION";
                            e.setRange(en.getKey(), min, max, Anomaly.RandomMode.valueOf(rm));
                        }
                    } else {
                        e.set(en.getKey(), toJava(val));
                    }
                }
            }

            e.depth = o.has("depth") ? o.get("depth").getAsLong() : 0;
            if (e.depth == 0) e.depth = AnomalyRegistry.get().allocateDepth(1000, 999_999_999L);

            if (eo.has("fidelity")) e.fidelity = eo.get("fidelity").getAsString();
            a.addEffect(e);
        }
        return a;
    }
}
