package src;

import src.ConfigLoader.RouteConfig;
import src.ConfigLoader.ServerConfig;
import src.utils.SessionManager;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.Files;
import java.util.Iterator;
import java.util.Set;

public class Server {
    public static class ConnectionContext {
        public SocketChannel channel;
        public SelectionKey key;
        public HttpParser.HttpRequest request;
        public HttpResponse response;
        public long lastActiveTime;
        public boolean cgiRunning = false;

        public ConnectionContext(SocketChannel channel, SelectionKey key, long clientBodyLimit) {
            this.channel = channel;
            this.key = key;
            this.request = new HttpParser.HttpRequest(clientBodyLimit);
            this.lastActiveTime = System.currentTimeMillis();
        }
    }

    private final ServerConfig config;
    private Selector selector;
    private boolean running = false;
    private final long timeoutMillis = 10000;

    public Server(ServerConfig config) {
        this.config = config;
    }

    public void start() throws IOException {
        selector = Selector.open();
        for (int port : config.ports) {
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.configureBlocking(false);
            serverChannel.socket().setReuseAddress(true);
            serverChannel.bind(new InetSocketAddress(config.host, port));
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);
        }
        running = true;

        while (running) {
            try {
                int selectCount = selector.select(1000);
                long now = System.currentTimeMillis();
                
                if (selectCount > 0) {
                    Set<SelectionKey> selectedKeys = selector.selectedKeys();
                    Iterator<SelectionKey> keyIterator = selectedKeys.iterator();

                    while (keyIterator.hasNext()) {
                        SelectionKey key = keyIterator.next();
                        keyIterator.remove();

                        if (!key.isValid()) continue;

                        try {
                            if (key.isAcceptable()) {
                                handleAccept(key);
                            } else if (key.isReadable()) {
                                handleRead(key);
                            } else if (key.isWritable()) {
                                handleWrite(key);
                            }
                        } catch (IOException e) {
                            closeConnection(key);
                        }
                    }
                }
                
                checkTimeouts(now);
                SessionManager.cleanExpiredSessions();

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    public void stop() {
        running = false;
        if (selector != null) {
            try {
                for (SelectionKey key : selector.keys()) {
                    Channel channel = key.channel();
                    try {
                        channel.close();
                    } catch (IOException ignored) {}
                }
                selector.close();
            } catch (IOException ignored) {}
        }
    }

    private void handleAccept(SelectionKey key) throws IOException {
        ServerSocketChannel serverChannel = (ServerSocketChannel) key.channel();
        SocketChannel clientChannel = serverChannel.accept();
        if (clientChannel != null) {
            clientChannel.configureBlocking(false);
            SelectionKey clientKey = clientChannel.register(selector, SelectionKey.OP_READ);
            ConnectionContext ctx = new ConnectionContext(clientChannel, clientKey, config.clientBodyLimit);
            clientKey.attach(ctx);
        }
    }

    private void handleRead(SelectionKey key) throws IOException {
        ConnectionContext ctx = (ConnectionContext) key.attachment();
        if (ctx == null || ctx.cgiRunning) return;

        ByteBuffer buffer = ByteBuffer.allocate(8192);
        int readBytes = ctx.channel.read(buffer);

        if (readBytes == -1) {
            closeConnection(key);
            return;
        }

        if (readBytes > 0) {
            ctx.lastActiveTime = System.currentTimeMillis();
            buffer.flip();
            ctx.request.feed(buffer);

            if (ctx.request.state == HttpParser.State.COMPLETE) {
                processRequest(ctx);
            } else if (ctx.request.state == HttpParser.State.ERROR) {
                sendError(ctx, ctx.request.errorStatus);
            }
        }
    }

    private void processRequest(ConnectionContext ctx) {
        try {
            RouteConfig matchedRoute = null;
            for (RouteConfig r : config.routes) {
                if (ctx.request.path.startsWith(r.prefix)) {
                    matchedRoute = r;
                    break;
                }
            }

            if (matchedRoute != null) {
                String relPath = ctx.request.path.substring(matchedRoute.prefix.length());
                if (relPath.startsWith("/")) {
                    relPath = relPath.substring(1);
                }
                File file = new File(matchedRoute.root, relPath);
                
                if (file.isDirectory() && matchedRoute.index != null) {
                    File indexFile = new File(file, matchedRoute.index);
                    if (indexFile.exists() && indexFile.isFile()) {
                        file = indexFile;
                    }
                }

                String name = file.getName();
                int dot = name.lastIndexOf('.');
                if (dot != -1) {
                    String ext = name.substring(dot + 1);
                    if (matchedRoute.cgiHandlers.containsKey(ext)) {
                        CGIHandler.executeAsync(ctx, matchedRoute, file, config);
                        return;
                    }
                }
            }

            HttpResponse res = Router.route(ctx.request, config);
            if (res.getStatus() >= 400) {
                res = buildErrorResponse(res.getStatus());
            }
            ctx.response = res;
            ctx.key.interestOps(SelectionKey.OP_WRITE);

        } catch (Exception e) {
            sendError(ctx, 500);
        }
    }

    private void handleWrite(SelectionKey key) throws IOException {
        ConnectionContext ctx = (ConnectionContext) key.attachment();
        if (ctx == null || ctx.response == null) return;

        boolean finished = ctx.response.write(ctx.channel);
        if (finished) {
            closeConnection(key);
        }
    }

    private void sendError(ConnectionContext ctx, int status) {
        ctx.response = buildErrorResponse(status);
        ctx.key.interestOps(SelectionKey.OP_WRITE);
    }

    private HttpResponse buildErrorResponse(int status) {
        HttpResponse res = new HttpResponse(status);
        String errorPath = config.errorPages.get(status);
        if (errorPath != null) {
            File f = new File(errorPath);
            if (f.exists() && f.isFile()) {
                try {
                    res.setHeader("Content-Type", "text/html; charset=UTF-8");
                    res.setBody(Files.readAllBytes(f.toPath()));
                    return res;
                } catch (IOException ignored) {}
            }
        }
        res.setHeader("Content-Type", "text/html; charset=UTF-8");
        res.setBody("<!DOCTYPE html><html><head><title>Error " + status + "</title>" +
            "<style>body { background: linear-gradient(135deg, #0f0c1b, #201a30); color: #f1f1f1; font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }" +
            ".card { background: rgba(255,255,255,0.05); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); padding: 3rem; border-radius: 16px; text-align: center; }" +
            "h1 { color: #f857a6; font-size: 4rem; margin: 0; }</style></head><body>" +
            "<div class='card'><h1>" + status + "</h1><h2>" + HttpResponse.getStatusText(status) + "</h2></div>" +
            "</body></html>");
        return res;
    }

    private void checkTimeouts(long now) {
        if (selector == null) return;
        for (SelectionKey key : selector.keys()) {
            if (key.isValid() && key.attachment() instanceof ConnectionContext) {
                ConnectionContext ctx = (ConnectionContext) key.attachment();
                if (!ctx.cgiRunning && (now - ctx.lastActiveTime) > timeoutMillis) {
                    closeConnection(key);
                }
            }
        }
    }

    private void closeConnection(SelectionKey key) {
        if (key != null) {
            Channel channel = key.channel();
            try {
                channel.close();
            } catch (IOException ignored) {}
            key.cancel();
        }
    }
}
