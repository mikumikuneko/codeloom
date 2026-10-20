package com.codeloom.agent.tool;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 这条会话里读过哪些文件，以及读到时的版本。
 *
 * <p>它是“读过才许覆盖”规则的账本：{@code read_file} 成功读取、{@code write_file} 写入和
 * {@code edit_file} 修改成功后都会记录，错误路径不记录。覆盖和修改都要求先有记录。
 * 记录版本用于发现读后被别人改动，在共享工作区里拿旧内容覆盖会静默吃掉别人的修改。
 *
 * <p>账从历史投影重建时只有路径、没有版本，此时版本未知，一律按未改过处理。
 * 检查与写入之间仍有 TOCTOU 窗口，本类不假装原子。账的范围是整条会话，由调用方持有。
 */
public final class ReadLedger {

    /**
     * 一条“读过”的记录。
     *
     * @param modifiedAt 读取时的修改时间；null = 版本未知（从历史重建）
     * @param size       读取时的字节数；与 mtime 一起用于发现改动
     * @param whole      读取时是否拿到整份；指定 offset/limit 或被截断都不算
     */
    public record Observation(FileTime modifiedAt, long size, boolean whole) {

        /**
         * 从历史重建的记录：知道读过，但不知道版本。{@code whole} 取 true，
         * 否则重启后的每次整份覆盖都会被拦下。
         *
         * @return 版本未知的观测
         */
        public static Observation reconstructed() {
            return new Observation(null, 0, true);
        }
    }

    /**
     * 键是绝对且规范化的路径，避免同一文件的不同写法产生多个键。
     * 使用并发集合，因为一批只读调用会并发记录。
     */
    private final Map<Path, Observation> read = new ConcurrentHashMap<>();

    /**
     * 记录一次读取，版本取当前磁盘状态。
     *
     * @param file  读到的文件
     * @param whole 是否读到整份
     */
    public void mark(Path file, boolean whole) {
        read.put(key(file), observe(file, whole));
    }

    /**
     * 从历史重建一条记录：知道读过，不知道版本。
     *
     * @param file 读过的文件
     */
    public void markReconstructed(Path file) {
        read.put(key(file), Observation.reconstructed());
    }

    /**
     * 这个文件在当前对话里读过吗；读过时返回当时的版本。
     *
     * @param file 文件
     * @return 观测记录
     */
    public Optional<Observation> observationOf(Path file) {
        return Optional.ofNullable(read.get(key(file)));
    }

    /**
     * 从记录时刻到现在，这个文件是否被改过。版本未知或无法读取时返回 false：
     * 有意的宽方向，真正的写入失败由写入步骤报告。
     *
     * @param file     文件
     * @param observed 读取时的观测
     * @return 修改时间或大小变化时为 true
     */
    public static boolean changedSince(Path file, Observation observed) {
        if (observed.modifiedAt() == null) {
            return false;
        }
        try {
            return !Files.getLastModifiedTime(file).equals(observed.modifiedAt())
                    || Files.size(file) != observed.size();
        } catch (IOException e) {
            return false;
        }
    }

    private static Observation observe(Path file, boolean whole) {
        try {
            return new Observation(Files.getLastModifiedTime(file), Files.size(file), whole);
        } catch (IOException e) {
            // 取不到版本时仍记为“读过”，否则刚被其他进程碰过的文件会被拒绝读取。
            return new Observation(null, 0, whole);
        }
    }

    private static Path key(Path file) {
        return file.toAbsolutePath().normalize();
    }
}
