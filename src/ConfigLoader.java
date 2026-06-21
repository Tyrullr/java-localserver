package src;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;

public class ConfigLoader {
    public static class RouteConfig {
        public String prefix;
        public String root;
        public Set<String> methods = new HashSet<>();
        public String index;
        public boolean dirListing = false;
        public Map<String, String> cgiHandlers = new HashMap<>();
        public String redirect;
    }

    public static class ServerConfig {
        public String host;
        public List<Integer> ports = new ArrayList<>();
        public int defaultPort;
        public long clientBodyLimit = 10 * 1024 * 1024;
        public Map<Integer, String> errorPages = new HashMap<>();
        public List<RouteConfig> routes = new ArrayList<>();
    }

    public static ServerConfig load(String filePath) throws IOException {
        Properties props = new Properties();
        try (InputStream is = new FileInputStream(filePath)) {
            props.load(is);
        }

        ServerConfig config = new ServerConfig();
        config.host = props.getProperty("host", "127.0.0.1");
        
        String portsStr = props.getProperty("ports", "8080");
        for (String p : portsStr.split(",")) {
            config.ports.add(Integer.parseInt(p.trim()));
        }

        config.defaultPort = Integer.parseInt(props.getProperty("default_port", String.valueOf(config.ports.get(0))));
        config.clientBodyLimit = Long.parseLong(props.getProperty("client_body_limit", "10485760"));

        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("error.")) {
                try {
                    int code = Integer.parseInt(key.substring(6));
                    config.errorPages.put(code, props.getProperty(key));
                } catch (NumberFormatException ignored) {}
            }
        }

        Map<String, RouteConfig> routeMap = new HashMap<>();
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith("route.")) {
                if (key.contains(".cgi.")) {
                    int cgiIdx = key.indexOf(".cgi.");
                    String prefix = key.substring(6, cgiIdx);
                    String ext = key.substring(cgiIdx + 5);
                    RouteConfig route = routeMap.computeIfAbsent(prefix, k -> {
                        RouteConfig r = new RouteConfig();
                        r.prefix = k;
                        return r;
                    });
                    route.cgiHandlers.put(ext, props.getProperty(key));
                } else {
                    int lastDot = key.lastIndexOf('.');
                    if (lastDot <= 6) continue;
                    String prefix = key.substring(6, lastDot);
                    String prop = key.substring(lastDot + 1);
                    String val = props.getProperty(key);

                    RouteConfig route = routeMap.computeIfAbsent(prefix, k -> {
                        RouteConfig r = new RouteConfig();
                        r.prefix = k;
                        return r;
                    });

                    if ("root".equals(prop)) {
                        route.root = val;
                    } else if ("methods".equals(prop)) {
                        for (String m : val.split(",")) {
                            route.methods.add(m.trim().toUpperCase());
                        }
                    } else if ("index".equals(prop)) {
                        route.index = val;
                    } else if ("dir_listing".equals(prop)) {
                        route.dirListing = Boolean.parseBoolean(val);
                    } else if ("redirect".equals(prop)) {
                        route.redirect = val;
                    }
                }
            }
        }

        config.routes.addAll(routeMap.values());
        config.routes.sort((r1, r2) -> Integer.compare(r2.prefix.length(), r1.prefix.length()));

        return config;
    }
}
