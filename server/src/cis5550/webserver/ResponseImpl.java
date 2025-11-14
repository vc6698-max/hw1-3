package cis5550.webserver;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;


public class ResponseImpl implements Response {

    private int sc = 200;
    private String rp = "OK";
    private final Map<String, List<String>> hdrs = new HashMap<>();
    private String bStr = null;
    private byte[] bby = null;

    private boolean c = false;       // committed flag
    private boolean h = false;       // halted flag
    private boolean wr = false;     // bool check for writ

    private BufferedOutputStream out = null;

    public ResponseImpl() {
        hdrs.put("content-type", new ArrayList<>());
        hdrs.get("content-type").add("text/html");
    }

    public void body(String bod) {
        if (!c && !h) {
            this.bStr = bod;
            this.bby = null;
        }
    }

    public void bodyAsBytes(byte[] blob) {
        if (!c && !h) {
            this.bby = blob;
            this.bStr = null;
        }
    }

    public void header(String name, String value) {
        if (!c && !h) {
            String k = name.toLowerCase();
            hdrs.computeIfAbsent(k, k2 -> new ArrayList<>()).add(value);
        }
    }

    public void type(String contentType) {
        if (!c && !h) {
            hdrs.put("content-type", new ArrayList<>());
            hdrs.get("content-type").add(contentType);
        }
    }

    public void status(int statusCode, String reasonPhrase) {
        if (!c && !h) {
            this.sc = statusCode;
            this.rp = reasonPhrase;
        }
    }

    public void write(byte[] b) throws Exception {
        if (!c && !h) {
            wr = true;
            header("Connection", "close");
            commitHeaders();
        }
        if (!h && out != null) {
            out.write(b);
            out.flush();
        }
    }

    public void redirect(String url, int responseCode) {
        if (!c && !h) {
            if (responseCode != 301 && responseCode != 302 &&
                responseCode != 303 && responseCode != 307 &&
                responseCode != 308) {
                throw new IllegalArgumentException("Invalid redirect status code");
            }

            status(responseCode, "Redirect");
            header("Location", url);

            try {
                commitHeaders();
            } catch (IOException e) {
                throw new RuntimeException("Failed to commit redirect headers", e);
            }
        }
    }

    public void halt(int statusCode, String reasonPhrase) {
        if (!c) {
            this.sc = statusCode;
            this.rp = reasonPhrase;
            this.h = true;
            throw new HaltException(statusCode, reasonPhrase);
        }
    }

    public void setOutputStream(BufferedOutputStream o) {
        this.out = o;
    }

    public boolean isCommitted() {
        return c;
    }

    public boolean isHalted() {
        return h;
    }

    public boolean isWriteCalled() {
        return wr;
    }

    public int getStatusCode() {
        return sc;
    }

    public String getReasonPhrase() {
        return rp;
    }

    public void commitHeaders() throws IOException {
        if (!c && out != null) {
            c = true;

            if (wr && !hdrs.containsKey("connection")) {
                hdrs.put("connection", new ArrayList<>(Collections.singletonList("close")));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("HTTP/1.1 ")
              .append(sc).append(" ")
              .append(rp).append("\r\n");

            for (Map.Entry<String, List<String>> e : hdrs.entrySet()) {
                for (String v : e.getValue()) {
                    sb.append(e.getKey())
                      .append(": ")
                      .append(v)
                      .append("\r\n");
                }
            }

            if (wr) {
                sb.append("\r\n");
                out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                out.flush();
            } else {
                if (bStr != null) {
                    byte[] bodyData = bStr.getBytes(StandardCharsets.UTF_8);
                    sb.append("Content-Length: ").append(bodyData.length).append("\r\n\r\n");
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    out.write(bodyData);
                } else if (bby != null) {
                    sb.append("Content-Length: ").append(bby.length).append("\r\n\r\n");
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                    out.write(bby);
                } else {
                    sb.append("Content-Length: 0\r\n\r\n");
                    out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                }
                out.flush();
            }
        }
    }

    public static class HaltException extends RuntimeException {
        private final int code;
        private final String reason;

        public HaltException(int statusCode, String reasonPhrase) {
            super(reasonPhrase);
            this.code = statusCode;
            this.reason = reasonPhrase;
        }

        public int getStatusCode() {
            return code;
        }

        public String getReasonPhrase() {
            return reason;
        }
    }
}
