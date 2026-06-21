package src;

import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.util.*;

public class HttpParser {
    public enum State {
        READING_HEADERS,
        READING_BODY,
        READING_CHUNKS,
        COMPLETE,
        ERROR
    }

    public enum ChunkState {
        READING_SIZE,
        READING_DATA,
        READING_CRLF
    }

    public static class HttpRequest {
        public State state = State.READING_HEADERS;
        public String method;
        public String path;
        public String queryString = "";
        public String version;
        public Map<String, String> headers = new HashMap<>();
        public byte[] body;
        public int errorStatus = 200;

        private final ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        private final ByteArrayOutputStream bodyBuffer = new ByteArrayOutputStream();

        private final long clientBodyLimit;
        private long contentLength = -1;
        private boolean isChunked = false;

        private ChunkState chunkState = ChunkState.READING_SIZE;
        private int currentChunkSize = -1;
        private final ByteArrayOutputStream chunkLineBuffer = new ByteArrayOutputStream();
        private int chunkBytesRead = 0;

        public HttpRequest(long clientBodyLimit) {
            this.clientBodyLimit = clientBodyLimit;
        }

        public void feed(ByteBuffer buffer) {
            while (buffer.hasRemaining() && state != State.COMPLETE && state != State.ERROR) {
                byte b = buffer.get();
                if (state == State.READING_HEADERS) {
                    headerBuffer.write(b);
                    byte[] bytes = headerBuffer.toByteArray();
                    int len = bytes.length;
                    if (len >= 4 && 
                        bytes[len - 4] == '\r' && bytes[len - 3] == '\n' &&
                        bytes[len - 2] == '\r' && bytes[len - 1] == '\n') {
                        parseHeaders(bytes);
                    }
                } else if (state == State.READING_BODY) {
                    bodyBuffer.write(b);
                    if (bodyBuffer.size() > clientBodyLimit) {
                        state = State.ERROR;
                        errorStatus = 413;
                        return;
                    }
                    if (bodyBuffer.size() >= contentLength) {
                        body = bodyBuffer.toByteArray();
                        state = State.COMPLETE;
                    }
                } else if (state == State.READING_CHUNKS) {
                    feedChunk(b);
                }
            }
        }

        private void parseHeaders(byte[] bytes) {
            String raw = new String(bytes, 0, bytes.length - 4);
            String[] lines = raw.split("\r\n");
            if (lines.length == 0 || lines[0].isEmpty()) {
                state = State.ERROR;
                errorStatus = 400;
                return;
            }

            String[] reqLine = lines[0].split(" ");
            if (reqLine.length < 3) {
                state = State.ERROR;
                errorStatus = 400;
                return;
            }

            method = reqLine[0].toUpperCase();
            String fullPath = reqLine[1];
            version = reqLine[2];

            int qIndex = fullPath.indexOf('?');
            if (qIndex != -1) {
                path = fullPath.substring(0, qIndex);
                queryString = fullPath.substring(qIndex + 1);
            } else {
                path = fullPath;
            }

            try {
                path = URLDecoder.decode(path, "UTF-8");
            } catch (UnsupportedEncodingException ignored) {}

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                int colon = line.indexOf(':');
                if (colon != -1) {
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String val = line.substring(colon + 1).trim();
                    headers.put(name, val);
                }
            }

            String clStr = headers.get("content-length");
            if (clStr != null) {
                try {
                    contentLength = Long.parseLong(clStr);
                    if (contentLength > clientBodyLimit) {
                        state = State.ERROR;
                        errorStatus = 413;
                        return;
                    }
                } catch (NumberFormatException e) {
                    state = State.ERROR;
                    errorStatus = 400;
                    return;
                }
            }

            String te = headers.get("transfer-encoding");
            if (te != null && te.toLowerCase().contains("chunked")) {
                isChunked = true;
            }

            if (isChunked) {
                state = State.READING_CHUNKS;
            } else if (contentLength > 0) {
                state = State.READING_BODY;
                if (bodyBuffer.size() >= contentLength) {
                    body = bodyBuffer.toByteArray();
                    state = State.COMPLETE;
                }
            } else {
                state = State.COMPLETE;
            }
        }

        private void feedChunk(byte b) {
            if (chunkState == ChunkState.READING_SIZE) {
                chunkLineBuffer.write(b);
                byte[] bytes = chunkLineBuffer.toByteArray();
                int len = bytes.length;
                if (len >= 2 && bytes[len - 2] == '\r' && bytes[len - 1] == '\n') {
                    String line = new String(bytes, 0, len - 2).trim();
                    int semi = line.indexOf(';');
                    if (semi != -1) {
                        line = line.substring(0, semi).trim();
                    }
                    try {
                        currentChunkSize = Integer.parseInt(line, 16);
                    } catch (NumberFormatException e) {
                        state = State.ERROR;
                        errorStatus = 400;
                        return;
                    }
                    chunkLineBuffer.reset();
                    if (currentChunkSize == 0) {
                        chunkState = ChunkState.READING_CRLF;
                    } else {
                        chunkState = ChunkState.READING_DATA;
                        chunkBytesRead = 0;
                    }
                }
            } else if (chunkState == ChunkState.READING_DATA) {
                bodyBuffer.write(b);
                if (bodyBuffer.size() > clientBodyLimit) {
                    state = State.ERROR;
                    errorStatus = 413;
                    return;
                }
                chunkBytesRead++;
                if (chunkBytesRead >= currentChunkSize) {
                    chunkState = ChunkState.READING_CRLF;
                }
            } else if (chunkState == ChunkState.READING_CRLF) {
                chunkLineBuffer.write(b);
                byte[] bytes = chunkLineBuffer.toByteArray();
                int len = bytes.length;
                if (len >= 2 && bytes[len - 2] == '\r' && bytes[len - 1] == '\n') {
                    chunkLineBuffer.reset();
                    if (currentChunkSize == 0) {
                        body = bodyBuffer.toByteArray();
                        state = State.COMPLETE;
                    } else {
                        chunkState = ChunkState.READING_SIZE;
                    }
                }
            }
        }
    }
}
