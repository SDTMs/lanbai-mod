package com.lanbai.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;

/**
 * 许愿队列：玩家在游戏里 /lanbai wish <内容>，写入服务器目录的 wishes.log。
 * AI 侧定时读取该文件，处理完写回 done 标记。
 */
public final class WishQueue {

    private WishQueue() {
    }

    public static Path file() {
        return Paths.get(System.getProperty("user.dir")).resolve("wishes.log");
    }

    /** 追加一条许愿：时间戳 \t 玩家 \t 内容 */
    public static void add(String player, String text) {
        try {
            String line = System.currentTimeMillis() + "\t" + player + "\t"
                    + text.replace("\n", " ").replace("\t", " ") + "\n";
            Files.writeString(file(), line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
    }
}
