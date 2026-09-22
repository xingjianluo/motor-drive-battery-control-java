package com.github.xingjianluo.motordrivebatterycontroljava.power.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于 JSON Lines 文件的事件日志：每行一个事件。
 * append 与 readAll 各自同步；协调器在统一锁内先 append 事件、再令内存态生效，
 * 重启时 {@link #readAll()} 顺序重放即可复现账本与故障状态。
 */
public class FileEventLog implements EventLog {

    private static final Logger log = LoggerFactory.getLogger(FileEventLog.class);

    private final Path path;
    private final ObjectMapper mapper = new ObjectMapper();

    public FileEventLog(Path path) {
        this.path = path;
        try {
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("无法创建事件日志目录: " + path, e);
        }
    }

    @Override
    public synchronized void append(Event event) {
        try {
            String line = mapper.writeValueAsString(event);
            Files.writeString(path, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("写入事件失败: " + event, e);
        }
    }

    @Override
    public synchronized List<Event> readAll() {
        if (!Files.exists(path)) {
            return List.of();
        }
        List<Event> events = new ArrayList<>();
        try {
            for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                events.add(mapper.readValue(line, Event.class));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("读取事件日志失败: " + path, e);
        }
        log.info("[事件日志] 从 {} 加载 {} 条事件用于重放", path, events.size());
        return events;
    }
}
