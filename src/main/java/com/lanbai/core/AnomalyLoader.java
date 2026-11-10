package com.lanbai.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.lanbai.LanbaiMod;
import net.fabricmc.loader.api.FabricLoader;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 收容物数据加载器。
 *
 * 热更新设计：
 *   优先读取 <游戏目录>/lanbai/anomalies.json（服务器上即 lanbai-server/lanbai/anomalies.json）
 *   文件不存在时回退到 jar 内置 /data/lanbai/anomalies.json，并把内置数据导出到磁盘。
 *   之后只需改磁盘文件 + /lanbai reload 即可生效，无需重启、无需重新编译。
 */
public class AnomalyLoader {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String BUNDLED = "/data/lanbai/anomalies.json";

    /** 数据目录 <gameDir>/lanbai */
    public static Path dataDir() {
        return FabricLoader.getInstance().getGameDir().resolve("lanbai");
    }

    /** 热更新用的数据文件 */
    public static Path dataFile() {
        return dataDir().resolve("anomalies.json");
    }

    /** 上次加载来源（/lanbai reload 回显） */
    public static String lastSource = "-";

    /**
     * 加载：磁盘优先 → 内置兜底（并落盘）。
     * @return 成功注册的数量
     */
    public static int load() {
        Path file = dataFile();
        JsonArray root = null;
        String source = "";

        try {
            if (Files.isRegularFile(file)) {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                root = GSON.fromJson(text, JsonArray.class);
                source = "磁盘 " + file;
            }
        } catch (Exception ex) {
            LanbaiMod.LOGGER.error("[LanBai] 读取 {} 失败，回退内置数据: {}", file, ex.getMessage());
            root = null;
        }

        if (root == null) {
            root = readBundled();
            source = "jar 内置";
            if (root != null) {
                try {
                    Files.createDirectories(dataDir());
                    Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
                    LanbaiMod.LOGGER.info("[LanBai] 已把内置数据导出到 {}", file);
                    source = "jar 内置（已导出到磁盘）";
                } catch (Exception ex) {
                    LanbaiMod.LOGGER.warn("[LanBai] 导出数据失败: {}", ex.getMessage());
                }
            }
        }

        if (root == null) {
            LanbaiMod.LOGGER.warn("[LanBai] 没有任何可用的收容物数据");
            lastSource = "无";
            return 0;
        }

        lastSource = source;
        return registerAll(root, source);
    }

    /** 从 jar 内置资源读取 */
    private static JsonArray readBundled() {
        try (var is = AnomalyLoader.class.getResourceAsStream(BUNDLED)) {
            if (is == null) {
                LanbaiMod.LOGGER.warn("[LanBai] jar 内未找到 {}", BUNDLED);
                return null;
            }
            return GSON.fromJson(new InputStreamReader(is, StandardCharsets.UTF_8), JsonArray.class);
        } catch (Exception ex) {
            LanbaiMod.LOGGER.error("[LanBai] 读取内置数据失败", ex);
            return null;
        }
    }

    /** 逐条解析并注册 */
    private static int registerAll(JsonArray root, String source) {
        AnomalyRegistry reg = AnomalyRegistry.get();
        int count = 0;
        int failed = 0;
        for (var el : root) {
            try {
                JsonObject o = el.getAsJsonObject();
                Anomaly a = parse(o);
                if (a != null) {
                    reg.register(a);
                    count++;
                }
            } catch (Exception e) {
                failed++;
                LanbaiMod.LOGGER.warn("[LanBai] 解析收容物失败: {}", e.getMessage());
            }
        }
        LanbaiMod.LOGGER.info("[LanBai] 从 {} 加载 {} 个收容物（失败 {} 个）", source, count, failed);
        return count;
    }

    private static Object toJava(com.google.gson.JsonElement v) {
        if (v.isJsonNull()) return null;
        if (!v.isJsonPrimitive()) return v.toString();
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
        if (o.has("book")) a.book = o.get("book").getAsString();

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
                    if (val.isJsonObject()) {
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

            // depth：优先 effect 自身，其次收容物级，最后随机分配
            if (eo.has("depth")) e.depth = eo.get("depth").getAsLong();
            else if (o.has("depth")) e.depth = o.get("depth").getAsLong();
            if (e.depth == 0) e.depth = AnomalyRegistry.get().allocateDepth(1000, 999_999_999L);

            if (eo.has("fidelity")) e.fidelity = eo.get("fidelity").getAsString();
            if (eo.has("conflictGroup")) e.conflictGroup = eo.get("conflictGroup").getAsString();
            a.addEffect(e);
        }
        return a;
    }
}
