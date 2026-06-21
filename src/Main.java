package src;

import src.ConfigLoader.ServerConfig;

public class Main {
    public static void main(String[] args) {
        String configFile = "config.properties";
        if (args.length > 0) {
            configFile = args[0];
        }

        try {
            ServerConfig config = ConfigLoader.load(configFile);
            System.out.println("Loaded config from: " + configFile);
            System.out.println("Host: " + config.host);
            System.out.println("Ports: " + config.ports);
            System.out.println("Default Port: " + config.defaultPort);
            System.out.println("Client Body Limit: " + config.clientBodyLimit + " bytes");

            Server server = new Server(config);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Shutting down server...");
                server.stop();
            }));

            System.out.println("Starting custom HTTP/1.1 NIO Server...");
            server.start();

        } catch (Exception e) {
            System.err.println("Failed to start server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
