package com.tan.log4j2.appender;

import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.plugins.Plugin;
import org.apache.logging.log4j.core.config.plugins.PluginAttribute;
import org.apache.logging.log4j.core.config.plugins.PluginElement;
import org.apache.logging.log4j.core.config.plugins.PluginFactory;
import org.apache.logging.log4j.core.layout.PatternLayout;
import redis.clients.jedis.Jedis;
import redis.clients.util.SafeEncoder;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

@Plugin(name = "Redis", category = "Core", elementType = "appender", printObject = true)
public class RedisAppender extends AbstractAppender implements Runnable {

    private String key;
    private String host;
    private int port;
    private String password;

    private int batchSize = 100;
    private boolean alwaysBatch = true;
    private boolean purgeOnFailure = true;
    private boolean daemonThread = true;

    private int messageIndex = 0;
    private ConcurrentLinkedQueue<LogEvent> events;
    private List<String> batch;

    private Jedis jedis;

    private ExecutorService executor;

    private Future<?> task;

    private RedisAppender(String name, Filter filter, Layout<? extends Serializable> layout, String key,
            String host, int port, String password, int batchSize, boolean purgeOnFailure,
            boolean alwaysBatch, boolean daemonThread) {
        super(name, filter, layout);
        this.key = key;
        this.host = host;
        this.port = port;
        this.password = password;
        this.batchSize = batchSize;
        this.purgeOnFailure = purgeOnFailure;
        this.alwaysBatch = alwaysBatch;
        this.daemonThread = daemonThread;
    }

    @Override
    public void start() {
        this.init();
        super.start();
    }

    private void init() {
        try {
            if (key == null) {
                throw new IllegalStateException("Must set 'key'");
            }

            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(new NamedThreadFactory("RedisAppender",
                        daemonThread));
            }

            if (task != null && !task.isDone()) {
                task.cancel(true);
            }

            if (jedis != null && jedis.isConnected()) {
                jedis.disconnect();
            }

            events = new ConcurrentLinkedQueue<>();
            batch = new ArrayList<>(batchSize);
            messageIndex = 0;

            jedis = new Jedis(host, port);
            task = executor.submit(this);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void append(LogEvent logEvent) {
        populateEvent(logEvent);
        events.add(logEvent);
    }

    private void populateEvent(LogEvent event) {
        event.getThreadName();
        event.getSource();
    }

    @PluginFactory
    public static RedisAppender createAppender(
            @PluginAttribute("name") String name,
            @PluginAttribute("host") String host,
            @PluginAttribute("port") int port,
            @PluginAttribute("password") String password,
            @PluginAttribute("key") String key,
            @PluginAttribute("batchSize") int batchSize,
            @PluginAttribute("purgeOnFailure") boolean purgeOnFailure,
            @PluginAttribute("alwaysBatch") boolean alwaysBatch,
            @PluginAttribute("daemonThread") boolean daemonThread,
            @PluginElement("Filter") final Filter filter,
            @PluginElement("Layout") Layout<? extends Serializable> layout) {
        if (name == null) {
            LOGGER.error("No name provided for MyCustomAppenderImpl");
            return null;
        }
        if (layout == null) {
            layout = PatternLayout.createDefaultLayout();
        }
        return new RedisAppender(name, filter, layout, key, host, port, password, batchSize, purgeOnFailure,
                alwaysBatch, daemonThread);
    }

    @Override
    public void stop() {
        try {
            task.cancel(false);
            executor.shutdown();
            jedis.disconnect();
        } catch (Exception e) {
            LOGGER.error("Fail to close redis connection.", e);
        }
    }

    private boolean connect() {
        try {
            if (!jedis.isConnected()) {
                jedis.connect();

                if (password != null) {
                    String result = jedis.auth(password);
                    if (!"OK".equals(result)) {
                        LOGGER.error("");
                    }
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public void run() {
        if (!connect()) {
            if (purgeOnFailure) {
                events.clear();
                messageIndex = 0;
            }
            return;
        }

        while (true) {
            LogEvent event = events.poll();
            if (event == null) {
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                continue;
            }
            batch.add(messageIndex++, new String(this.getLayout().toByteArray(event)));
            pushBatch();
            if (!alwaysBatch && messageIndex > 0) {
                publisheBatch();
            }
        }
    }

    private void pushBatch() {
        if (alwaysBatch && messageIndex == batchSize) {
            publisheBatch();
        }
    }

    private void publisheBatch() {
        for (int i = 0; i < messageIndex; i++) {
            byte[] safeEncoderKey = SafeEncoder.encode(key);
            jedis.publish(safeEncoderKey, SafeEncoder.encode(batch.get(i)));
        }

        batch.clear();
        messageIndex = 0;
    }
}
