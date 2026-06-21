package src;

import src.ConfigLoader.RouteConfig;
import src.ConfigLoader.ServerConfig;
import src.utils.Cookie;
import src.utils.SessionManager;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.*;

public class Router {
    public static class MultipartPart {
        public String filename;
        public byte[] data;
    }

    public static List<MultipartPart> parseMultipart(byte[] body, String boundary) {
        List<MultipartPart> parts = new ArrayList<>();
        if (body == null || boundary == null) return parts;
        byte[] boundaryBytes = ("--" + boundary).getBytes();
        int idx = 0;
        while (idx < body.length) {
            int nextBoundary = indexOf(body, boundaryBytes, idx);
            if (nextBoundary == -1) break;
            int partStart = nextBoundary + boundaryBytes.length;
            if (partStart + 2 > body.length) break;
            if (body[partStart] == '-' && body[partStart + 1] == '-') break;
            if (body[partStart] == '\r' && body[partStart + 1] == '\n') {
                partStart += 2;
            }
            int partEnd = indexOf(body, boundaryBytes, partStart);
            if (partEnd == -1) break;
            
            int actualEnd = partEnd;
            if (actualEnd - 2 >= partStart && body[actualEnd - 2] == '\r' && body[actualEnd - 1] == '\n') {
                actualEnd -= 2;
            }
            
            byte[] partBytes = Arrays.copyOfRange(body, partStart, actualEnd);
            int headerEnd = indexOf(partBytes, new byte[]{'\r', '\n', '\r', '\n'}, 0);
            if (headerEnd != -1) {
                String headerStr = new String(partBytes, 0, headerEnd);
                byte[] fileData = Arrays.copyOfRange(partBytes, headerEnd + 4, partBytes.length);
                
                String filename = null;
                for (String line : headerStr.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-disposition:")) {
                        int fnIdx = line.toLowerCase().indexOf("filename=\"");
                        if (fnIdx != -1) {
                            int fnEnd = line.indexOf("\"", fnIdx + 10);
                            if (fnEnd != -1) {
                                filename = line.substring(fnIdx + 10, fnEnd);
                            }
                        }
                    }
                }
                if (filename != null && !filename.isEmpty()) {
                    MultipartPart part = new MultipartPart();
                    part.filename = filename;
                    part.data = fileData;
                    parts.add(part);
                }
            }
            idx = partEnd;
        }
        return parts;
    }

    private static int indexOf(byte[] src, byte[] target, int start) {
        for (int i = start; i <= src.length - target.length; i++) {
            boolean match = true;
            for (int j = 0; j < target.length; j++) {
                if (src[i + j] != target[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    public static HttpResponse handleCustomEndpoints(HttpParser.HttpRequest req) {
        if ("/set-session".equals(req.path)) {
            String bodyStr = new String(req.body != null ? req.body : new byte[0]);
            String username = null;
            for (String param : bodyStr.split("&")) {
                String[] pair = param.split("=", 2);
                if (pair.length == 2 && "username".equals(pair[0])) {
                    try {
                        username = java.net.URLDecoder.decode(pair[1], "UTF-8");
                    } catch (Exception ignored) {}
                }
            }
            if (username != null && !username.trim().isEmpty()) {
                SessionManager.Session session = SessionManager.getOrCreateSession(null);
                session.setAttribute("username", username.trim());
                HttpResponse res = new HttpResponse(302);
                res.setHeader("Location", "/");
                res.setHeader("Set-Cookie", Cookie.format("JSESSIONID", session.getId(), "/", true));
                return res;
            }
            return new HttpResponse(400);
        }

        if ("/check-session".equals(req.path)) {
            Map<String, String> cookies = Cookie.parse(req.headers.get("cookie"));
            String sessionId = cookies.get("JSESSIONID");
            SessionManager.Session session = SessionManager.getSession(sessionId);
            String username = (session != null) ? (String) session.getAttribute("username") : null;
            
            HttpResponse res = new HttpResponse(200);
            res.setHeader("Content-Type", "text/html; charset=UTF-8");
            
            String content;
            if (username != null) {
                content = "<!DOCTYPE html><html><head><title>Session Check</title>" +
                          "<style>body { background: linear-gradient(135deg, #0f0c1b, #201a30); color: #f1f1f1; font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }" +
                          ".card { background: rgba(255,255,255,0.05); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); padding: 3rem; border-radius: 16px; text-align: center; }" +
                          "h1 { color: #c084fc; }</style></head><body>" +
                          "<div class='card'><h1>Hello, " + username + "!</h1><p>Your session is active and secure.</p><a href='/' style='color:#38bdf8;text-decoration:none;'>Back Home</a></div>" +
                          "</body></html>";
            } else {
                content = "<!DOCTYPE html><html><head><title>Session Check</title>" +
                          "<style>body { background: linear-gradient(135deg, #0f0c1b, #201a30); color: #f1f1f1; font-family: sans-serif; display: flex; align-items: center; justify-content: center; height: 100vh; margin: 0; }" +
                          ".card { background: rgba(255,255,255,0.05); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); padding: 3rem; border-radius: 16px; text-align: center; }</style></head><body>" +
                          "<div class='card'><h1>No Active Session</h1><p>Please establish a session on the homepage.</p><a href='/' style='color:#38bdf8;text-decoration:none;'>Back Home</a></div>" +
                          "</body></html>";
            }
            res.setBody(content);
            return res;
        }

        return null;
    }

    public static HttpResponse route(HttpParser.HttpRequest req, ServerConfig config) throws IOException {
        HttpResponse customRes = handleCustomEndpoints(req);
        if (customRes != null) {
            return customRes;
        }

        RouteConfig route = null;
        for (RouteConfig r : config.routes) {
            if (req.path.startsWith(r.prefix)) {
                route = r;
                break;
            }
        }

        if (route == null) {
            return new HttpResponse(404);
        }

        if (!route.methods.contains(req.method)) {
            return new HttpResponse(405);
        }

        if (route.redirect != null) {
            HttpResponse redirectRes = new HttpResponse(302);
            redirectRes.setHeader("Location", route.redirect);
            return redirectRes;
        }

        String relPath = req.path.substring(route.prefix.length());
        if (relPath.startsWith("/")) {
            relPath = relPath.substring(1);
        }

        File file = new File(route.root, relPath);
        String canonicalRoot = new File(route.root).getCanonicalPath();
        String canonicalFile = file.getCanonicalPath();
        if (!canonicalFile.startsWith(canonicalRoot)) {
            return new HttpResponse(403);
        }

        if (file.isDirectory()) {
            if (route.index != null) {
                File indexFile = new File(file, route.index);
                if (indexFile.exists() && indexFile.isFile()) {
                    file = indexFile;
                }
            }
        }

        if (file.isDirectory() && "GET".equals(req.method)) {
            if (route.dirListing) {
                return generateDirectoryListing(file, req.path);
            } else {
                return new HttpResponse(403);
            }
        }

        if ("DELETE".equals(req.method)) {
            if (!file.exists()) {
                return new HttpResponse(404);
            }
            if (file.delete()) {
                HttpResponse res = new HttpResponse(200);
                res.setBody("File deleted successfully.");
                return res;
            } else {
                return new HttpResponse(500);
            }
        }

        if ("POST".equals(req.method) && req.path.startsWith(route.prefix)) {
            String contentType = req.headers.get("content-type");
            if (contentType != null && contentType.toLowerCase().contains("multipart/form-data")) {
                String boundary = null;
                for (String param : contentType.split(";")) {
                    String trimmed = param.trim();
                    if (trimmed.startsWith("boundary=")) {
                        boundary = trimmed.substring(9);
                    }
                }
                if (boundary != null) {
                    List<MultipartPart> parts = parseMultipart(req.body, boundary);
                    for (MultipartPart part : parts) {
                        File dest = new File(new File(route.root), part.filename);
                        String canonicalDest = dest.getCanonicalPath();
                        if (!canonicalDest.startsWith(canonicalRoot)) {
                            return new HttpResponse(403);
                        }
                        dest.getParentFile().mkdirs();
                        Files.write(dest.toPath(), part.data);
                    }
                    HttpResponse res = new HttpResponse(200);
                    res.setHeader("Content-Type", "text/html; charset=UTF-8");
                    res.setBody("<!DOCTYPE html><html><head><title>Success</title><style>body{background:linear-gradient(135deg, #0f0c1b, #201a30);color:#f1f1f1;font-family:sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;}.card{background:rgba(255,255,255,0.05);backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,0.1);padding:3rem;border-radius:16px;text-align:center;}h1{color:#c084fc;}</style></head><body><div class='card'><h1>Files Uploaded Successfully</h1><a href='/' style='color:#38bdf8;text-decoration:none;'>Back Home</a></div></body></html>");
                    return res;
                }
            }
        }

        if (!file.exists()) {
            return new HttpResponse(404);
        }

        String name = file.getName();
        int dot = name.lastIndexOf('.');
        if (dot != -1) {
            String ext = name.substring(dot + 1);
            if (route.cgiHandlers.containsKey(ext)) {
                return null; // Signals Server to handle it as CGI
            }
        }

        HttpResponse res = new HttpResponse(200);
        res.setHeader("Content-Type", getContentType(file));
        res.setBody(Files.readAllBytes(file.toPath()));
        return res;
    }

    private static HttpResponse generateDirectoryListing(File dir, String requestPath) {
        HttpResponse res = new HttpResponse(200);
        res.setHeader("Content-Type", "text/html; charset=UTF-8");
        
        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html><head><title>Index of ").append(requestPath).append("</title>");
        sb.append("<style>")
          .append("body { background: linear-gradient(135deg, #0f0c1b, #201a30); color: #f1f1f1; font-family: sans-serif; padding: 2rem; }")
          .append(".container { max-width: 800px; margin: 0 auto; background: rgba(255,255,255,0.05); backdrop-filter: blur(10px); border: 1px solid rgba(255,255,255,0.1); border-radius: 16px; padding: 2rem; box-shadow: 0 8px 32px 0 rgba(0,0,0,0.37); }")
          .append("h1 { color: #c084fc; margin-top: 0; }")
          .append("table { width: 100%; border-collapse: collapse; }")
          .append("th, td { padding: 0.75rem; text-align: left; border-bottom: 1px solid rgba(255,255,255,0.1); }")
          .append("th { color: #38bdf8; }")
          .append("a { color: #a855f7; text-decoration: none; }")
          .append("a:hover { text-decoration: underline; }")
          .append("</style></head><body><div class='container'>");
        sb.append("<h1>Index of ").append(requestPath).append("</h1>");
        sb.append("<table><tr><th>Name</th><th>Size</th><th>Last Modified</th></tr>");
        
        if (!"/".equals(requestPath)) {
            sb.append("<tr><td><a href='../'>[Parent Directory]</a></td><td>-</td><td>-</td></tr>");
        }
        
        File[] files = dir.listFiles();
        if (files != null) {
            Arrays.sort(files, (f1, f2) -> {
                if (f1.isDirectory() && !f2.isDirectory()) return -1;
                if (!f1.isDirectory() && f2.isDirectory()) return 1;
                return f1.getName().compareTo(f2.getName());
            });
            for (File f : files) {
                String name = f.getName() + (f.isDirectory() ? "/" : "");
                String href = requestPath.endsWith("/") ? requestPath + f.getName() : requestPath + "/" + f.getName();
                sb.append("<tr>")
                  .append("<td><a href='").append(href).append("'>").append(name).append("</a></td>")
                  .append("<td>").append(f.isDirectory() ? "-" : f.length() + " B").append("</td>")
                  .append("<td>").append(new Date(f.lastModified())).append("</td>")
                  .append("</tr>");
            }
        }
        sb.append("</table></div></body></html>");
        res.setBody(sb.toString());
        return res;
    }

    private static String getContentType(File file) {
        String name = file.getName().toLowerCase();
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=UTF-8";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }
}
