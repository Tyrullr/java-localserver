package src.utils;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class SessionManager {
    public static class Session {
        private final String id;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private long lastAccessed;

        public Session(String id) {
            this.id = id;
            this.lastAccessed = System.currentTimeMillis();
        }

        public String getId() {
            return id;
        }

        public void setAttribute(String name, Object value) {
            attributes.put(name, value);
            lastAccessed = System.currentTimeMillis();
        }

        public Object getAttribute(String name) {
            lastAccessed = System.currentTimeMillis();
            return attributes.get(name);
        }

        public void removeAttribute(String name) {
            attributes.remove(name);
            lastAccessed = System.currentTimeMillis();
        }

        public boolean isExpired(long timeoutMillis) {
            return (System.currentTimeMillis() - lastAccessed) > timeoutMillis;
        }
    }

    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private static final long SESSION_TIMEOUT = 30 * 60 * 1000;

    public static Session getOrCreateSession(String sessionId) {
        if (sessionId != null) {
            Session session = sessions.get(sessionId);
            if (session != null) {
                if (session.isExpired(SESSION_TIMEOUT)) {
                    sessions.remove(sessionId);
                } else {
                    return session;
                }
            }
        }
        String newId = UUID.randomUUID().toString();
        Session newSession = new Session(newId);
        sessions.put(newId, newSession);
        return newSession;
    }

    public static Session getSession(String sessionId) {
        if (sessionId == null) {
            return null;
        }
        Session session = sessions.get(sessionId);
        if (session != null) {
            if (session.isExpired(SESSION_TIMEOUT)) {
                sessions.remove(sessionId);
                return null;
            }
            return session;
        }
        return null;
    }

    public static void cleanExpiredSessions() {
        sessions.entrySet().removeIf(entry -> entry.getValue().isExpired(SESSION_TIMEOUT));
    }
}
