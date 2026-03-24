package com.webconsole;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.config.Configuration;

import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

public class CustomLogAppender extends AbstractAppender {
    private final List<LogEntry> logStorage = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger idGenerator = new AtomicInteger(0);
    private final DateTimeFormatter timeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss");

    public CustomLogAppender() {
        super("WebConsoleAppender", null, null, false, null);
    }

    public void setup() {
        LoggerContext ctx = (LoggerContext) LogManager.getContext(false);
        Configuration config = ctx.getConfiguration();
        config.addAppender(this);
        ctx.getRootLogger().addAppender(config.getAppender(this.getName()));
        this.start();
    }

    @Override
    public void append(LogEvent event) {
        String message = event.getMessage().getFormattedMessage();
        String time = LocalTime.now().format(timeFormatter);
        String level = event.getLevel().name();

        logStorage.add(new LogEntry(idGenerator.incrementAndGet(), time, level, message));

        // Храним только последние 500 строк, чтобы не забивать память
        if (logStorage.size() > 500) {
            logStorage.remove(0);
        }
    }

    public List<LogEntry> getLogsAfter(int id) {
        synchronized (logStorage) {
            return logStorage.stream()
                    .filter(log -> log.id > id)
                    .collect(Collectors.toList());
        }
    }

    public static class LogEntry {
        public int id;
        public String time;
        public String level;
        public String message;

        public LogEntry(int id, String time, String level, String message) {
            this.id = id;
            this.time = time;
            this.level = level;
            this.message = message;
        }
    }
}