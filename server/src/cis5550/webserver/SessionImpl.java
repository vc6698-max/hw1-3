package cis5550.webserver;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class SessionImpl implements Session {
    private final String id;
    private final long creationTime;
    private volatile long lastAccessedTime;
    private volatile int maxActiveInterval = 300;

    private final Map<String, Object> attributes = new ConcurrentHashMap<>();
    private volatile boolean invalidated = false;

    public SessionImpl(String id) {
        this.id = id;
        this.creationTime = System.currentTimeMillis();
        this.lastAccessedTime = this.creationTime;
    }

    public String id() {
        return id;
    }

    public long creationTime() {
        return creationTime;
    }

    public long lastAccessedTime() {
        return lastAccessedTime;
    }

    public void touch() {
        this.lastAccessedTime = System.currentTimeMillis();
    }

    public void maxActiveInterval(int seconds) {
        this.maxActiveInterval = seconds;
    }

    public int getMaxActiveInterval() {
        return this.maxActiveInterval;
    }

    public void invalidate() {
        this.invalidated = true;
        Server.removeSession(this.id);
    }

    public Object attribute(String name) {
        if (invalidated) return null;
        touch();
        return attributes.get(name);
    }

    public void attribute(String name, Object value) {
        if (invalidated) return;
        touch();
        if (value == null) {
            attributes.remove(name);
        } else {
            attributes.put(name, value);
        }
    }
}