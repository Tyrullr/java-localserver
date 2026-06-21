package src;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.util.*;

public class HttpResponse {
    private final int status;
    private final String statusText;
    private final Map<String, String> headers = new LinkedHashMap<>();
    private byte[] body = new byte[0];
    private ByteBuffer writeBuffer;

    public HttpResponse(int status) {
        this.status = status;
        this.statusText = getStatusText(status);
        headers.put("Server", "JavaCustomServer/1.0");
        headers.put("Date", new Date().toString());
        headers.put("Connection", "close");
    }

    public static String getStatusText(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 301: return "Moved Permanently";
            case 302: return "Found";
            case 400: return "Bad Request";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 413: return "Payload Too Large";
            case 500: return "Internal Server Error";
            default: return "Unknown";
        }
    }

    public void setHeader(String name, String value) {
        headers.put(name, value);
    }

    public void setBody(byte[] body) {
        this.body = body;
        headers.put("Content-Length", String.valueOf(body.length));
    }

    public void setBody(String bodyText) {
        setBody(bodyText.getBytes());
    }

    public int getStatus() {
        return status;
    }

    public void prepare() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        baos.write(("HTTP/1.1 " + status + " " + statusText + "\r\n").getBytes());
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            baos.write((entry.getKey() + ": " + entry.getValue() + "\r\n").getBytes());
        }
        baos.write("\r\n".getBytes());
        if (body.length > 0) {
            baos.write(body);
        }
        writeBuffer = ByteBuffer.wrap(baos.toByteArray());
    }

    public boolean write(SocketChannel channel) throws IOException {
        if (writeBuffer == null) {
            prepare();
        }
        channel.write(writeBuffer);
        return !writeBuffer.hasRemaining();
    }
}
