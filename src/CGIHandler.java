package src;

import src.ConfigLoader.RouteConfig;
import src.ConfigLoader.ServerConfig;
import src.Server.ConnectionContext;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.SelectionKey;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.TimeUnit;

public class CGIHandler {
    public static void executeAsync(ConnectionContext ctx, RouteConfig route, File file, ServerConfig config) {
        ctx.cgiRunning = true;
        ctx.key.interestOps(0);

        Thread cgiThread = new Thread(() -> {
            try {
                String ext = "";
                String name = file.getName();
                int dot = name.lastIndexOf('.');
                if (dot != -1) {
                    ext = name.substring(dot + 1);
                }
                String interpreter = route.cgiHandlers.get(ext);

                ProcessBuilder pb;
                if (interpreter != null && !interpreter.isEmpty()) {
                    pb = new ProcessBuilder(interpreter, file.getAbsolutePath());
                } else {
                    pb = new ProcessBuilder(file.getAbsolutePath());
                }

                pb.directory(file.getParentFile());

                Map<String, String> env = pb.environment();
                env.put("REQUEST_METHOD", ctx.request.method);
                env.put("QUERY_STRING", ctx.request.queryString);
                env.put("PATH_INFO", file.getAbsolutePath());
                env.put("SCRIPT_NAME", file.getName());
                env.put("SERVER_PORT", String.valueOf(ctx.channel.socket().getLocalPort()));
                env.put("SERVER_NAME", config.host);
                
                String cl = ctx.request.headers.get("content-length");
                env.put("CONTENT_LENGTH", cl != null ? cl : "");
                
                String ct = ctx.request.headers.get("content-type");
                env.put("CONTENT_TYPE", ct != null ? ct : "");

                String cookie = ctx.request.headers.get("cookie");
                env.put("HTTP_COOKIE", cookie != null ? cookie : "");

                for (Map.Entry<String, String> entry : ctx.request.headers.entrySet()) {
                    String key = "HTTP_" + entry.getKey().toUpperCase().replace("-", "_");
                    env.put(key, entry.getValue());
                }

                Process process = pb.start();

                if ("POST".equals(ctx.request.method) && ctx.request.body != null) {
                    try (OutputStream os = process.getOutputStream()) {
                        os.write(ctx.request.body);
                        os.flush();
                    }
                }

                ByteArrayOutputStream stdoutStream = new ByteArrayOutputStream();
                ByteArrayOutputStream stderrStream = new ByteArrayOutputStream();

                Thread outReader = new Thread(() -> {
                    try (InputStream is = process.getInputStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            stdoutStream.write(buf, 0, n);
                        }
                    } catch (Exception ignored) {}
                });

                Thread errReader = new Thread(() -> {
                    try (InputStream is = process.getErrorStream()) {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = is.read(buf)) != -1) {
                            stderrStream.write(buf, 0, n);
                        }
                    } catch (Exception ignored) {}
                });

                outReader.start();
                errReader.start();

                boolean exited = process.waitFor(10, TimeUnit.SECONDS);
                if (!exited) {
                    process.destroyForcibly();
                    sendErrorResponse(ctx, 500, "CGI Timeout");
                    return;
                }

                outReader.join();
                errReader.join();

                if (process.exitValue() != 0) {
                    sendErrorResponse(ctx, 500, "CGI process failed: " + stderrStream.toString());
                    return;
                }

                byte[] cgiOutput = stdoutStream.toByteArray();
                parseCgiOutput(ctx, cgiOutput);

            } catch (Exception e) {
                sendErrorResponse(ctx, 500, e.getMessage());
            }
        });
        cgiThread.setDaemon(true);
        cgiThread.start();
    }

    private static void parseCgiOutput(ConnectionContext ctx, byte[] output) {
        int headerEnd = -1;
        for (int i = 0; i <= output.length - 4; i++) {
            if (output[i] == '\r' && output[i + 1] == '\n' && output[i + 2] == '\r' && output[i + 3] == '\n') {
                headerEnd = i;
                break;
            }
        }

        if (headerEnd == -1) {
            HttpResponse res = new HttpResponse(200);
            res.setHeader("Content-Type", "text/html; charset=UTF-8");
            res.setBody(output);
            ctx.response = res;
        } else {
            String headerStr = new String(output, 0, headerEnd);
            byte[] body = java.util.Arrays.copyOfRange(output, headerEnd + 4, output.length);

            int status = 200;
            Map<String, String> headers = new LinkedHashMap<>();

            for (String line : headerStr.split("\r\n")) {
                int colon = line.indexOf(':');
                if (colon != -1) {
                    String name = line.substring(0, colon).trim();
                    String val = line.substring(colon + 1).trim();
                    if ("status".equalsIgnoreCase(name)) {
                        try {
                            String[] parts = val.split(" ", 2);
                            status = Integer.parseInt(parts[0]);
                        } catch (Exception ignored) {}
                    } else {
                        headers.put(name, val);
                    }
                }
            }

            HttpResponse res = new HttpResponse(status);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                res.setHeader(entry.getKey(), entry.getValue());
            }
            res.setBody(body);
            ctx.response = res;
        }

        ctx.cgiRunning = false;
        ctx.key.interestOps(SelectionKey.OP_WRITE);
        ctx.key.selector().wakeup();
    }

    private static void sendErrorResponse(ConnectionContext ctx, int status, String msg) {
        HttpResponse res = new HttpResponse(status);
        res.setBody("CGI Error: " + msg);
        ctx.response = res;
        ctx.cgiRunning = false;
        ctx.key.interestOps(SelectionKey.OP_WRITE);
        ctx.key.selector().wakeup();
    }
}
