package com.github.xingjianluo.motordrivebatterycontroljava.event;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于单行文本的文件事件日志（JSON-lines 风格，管道分隔）。
 * 每次 append 立即刷盘（fsync），保证进程崩溃/重启后已确认的事件可重放。
 * 事件序号在协调器侧统一分配，文件只做持久化载体。
 */
public class FileEventStore implements EventStore {

    private final Path file;

    public FileEventStore(Path file) {
        this.file = file;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            if (!Files.exists(file)) {
                Files.createFile(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public void append(DomainEvent event) {
        try (BufferedWriter w = Files.newBufferedWriter(file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(event.toLogLine());
            w.newLine();
            w.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("追加事件失败: " + file, e);
        }
    }

    @Override
    public List<DomainEvent> loadAll() {
        List<DomainEvent> result = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(file)) {
                if (!line.isBlank()) {
                    result.add(EventCodec.parse(line));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取事件日志失败: " + file, e);
        }
        return result;
    }

    @Override
    public long lastSequence() {
        List<DomainEvent> all = loadAll();
        return all.isEmpty() ? 0L : all.get(all.size() - 1).sequence();
    }
}
