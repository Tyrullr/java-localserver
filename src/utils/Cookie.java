package src.utils;

import java.util.HashMap;
import java.util.Map;

public class Cookie {
    public static Map<String, String> parse(String cookieHeader) {
        Map<String, String> cookies = new HashMap<>();
        if (cookieHeader == null || cookieHeader.isEmpty()) {
            return cookies;
        }
        String[] pairs = cookieHeader.split(";");
        for (String pair : pairs) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) {
                cookies.put(parts[0].trim(), parts[1].trim());
            } else if (parts.length == 1) {
                cookies.put(parts[0].trim(), "");
            }
        }
        return cookies;
    }

    public static String format(String name, String value, String path, boolean httpOnly) {
        StringBuilder sb = new StringBuilder();
        sb.append(name).append("=").append(value);
        if (path != null) {
            sb.append("; Path=").append(path);
        }
        if (httpOnly) {
            sb.append("; HttpOnly");
        }
        sb.append("; SameSite=Lax");
        return sb.toString();
    }
}
