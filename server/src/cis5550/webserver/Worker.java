package cis5550.webserver;

import cis5550.tools.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;


import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;

class Worker implements Runnable {

    private final ConnectionQueue queue;
    private final String rootDir;
    private final String rootCanonical;

    private static final Logger logger = Logger.getLogger(Worker.class);

    public Worker(ConnectionQueue queue, String rootDir) {
        this.queue = queue;
        this.rootDir = rootDir;

        try {
            this.rootCanonical = new File(rootDir).getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException("Invalid root directory " + rootDir, e);
        }
    }

    public void run() {
        while (true) {
            Socket socket = null;

            try {
                socket = queue.dequeue();
            } catch (InterruptedException ie) {
                continue;
            }

            try {
                handleConnection(socket);
            } catch (Throwable t) {
                logger.error("Worker exception: " + t.getMessage(), t);
            } finally {
                try {
                    if (socket != null && !socket.isClosed()) {
                        socket.close();
                    }
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (
            BufferedInputStream in = new BufferedInputStream(socket.getInputStream(), 16 * 1024);
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream(), 16 * 1024)
        ) {
            boolean keepAlive = true;

            while (keepAlive) {
                boolean cont = processRequest(in, out, socket);
                if (!cont) {
                    break;
                }
            }
        } catch (IOException e) {
            logger.debug("Connection IO error: " + e.getMessage());
        }
    }


    private boolean processRequest(BufferedInputStream in, BufferedOutputStream out, Socket socket) {
        try {
            ByteArrayOutputStream headerBuf = new ByteArrayOutputStream();
            int state = 0;
            int b;

            while (state < 4) {
                b = in.read();

                if (b == -1) {
                    return false;
                }

                headerBuf.write(b);

                if (b == '\r') {
                    if (state == 0 || state == 2) state++;
                    else state = 1;
                } else if (b == '\n') {
                    if (state == 1) state = 2;
                    else if (state == 3) state = 4;
                    else state = 0;
                } else {
                    state = 0;
                }
            }

            String headerString = headerBuf.toString(StandardCharsets.UTF_8.name());
            String[] lines = headerString.split("\r\n");

            if (lines.length == 0) {
                sendError(out, 400, "Bad Request", "GET");
                return false;
            }

            String[] reqParts = lines[0].split("\\s+");

            if (reqParts.length != 3) {
                sendError(out, 400, "Bad Request", "GET");
                return false;
            }

            String method = reqParts[0];
            String url = reqParts[1];
            String version = reqParts[2];

            if (!"HTTP/1.1".equals(version)) {
                sendError(out, 505, "HTTP Version Not Supported", method);
                return false;
            }

            Map<String, String> headers = new HashMap<>();
            boolean hasHost = false;

            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line == null || line.trim().isEmpty()) continue;

                int colon = line.indexOf(':');
                if (colon == -1) {
                    sendError(out, 400, "Bad Request", method);
                    return false;
                }

                String key = line.substring(0, colon).trim().toLowerCase();
                String value = line.substring(colon + 1).trim();
                headers.put(key, value);

                if ("host".equals(key)) {
                    hasHost = true;
                }
            }

            if (!hasHost) {
                sendError(out, 400, "Bad Request", method);
                return false;
            }

            int contentLength = 0;
            if (headers.containsKey("content-length")) {
                try {
                    contentLength = Integer.parseInt(headers.get("content-length"));
                    if (contentLength < 0) {
                        sendError(out, 400, "Bad Request", method);
                        return false;
                    }
                } catch (NumberFormatException nfe) {
                    sendError(out, 400, "Bad Request", method);
                    return false;
                }
            }

            Map<String, String> queryParams = new HashMap<>();
            int qidx = url.indexOf('?');
            String rawPath = (qidx >= 0) ? url.substring(0, qidx) : url;

            if (qidx >= 0) {
                String queryString = url.substring(qidx + 1);
                parseQueryString(queryString, queryParams);
            }

            String decodedPath;
            try {
                decodedPath = URLDecoder.decode(rawPath, "UTF-8");
            } catch (IllegalArgumentException iae) {
                if (contentLength > 0) {
                    if (!discardRequestBody(in, contentLength)) return false;
                }
                sendError(out, 400, "Bad Request", method);
                return false;
            }

            if (decodedPath.contains("..")) {
                if (contentLength > 0) {
                    if (!discardRequestBody(in, contentLength)) return false;
                }
                sendError(out, 403, "Forbidden", method);
                return false;
            }

            if (!decodedPath.startsWith("/")) decodedPath = "/" + decodedPath;

            byte[] bodyRaw = new byte[0];
            if (contentLength > 0) {
                bodyRaw = new byte[contentLength];
                int bytesRead = 0;
                while (bytesRead < contentLength) {
                    int r = in.read(bodyRaw, bytesRead, contentLength - bytesRead);
                    if (r == -1) break;
                    bytesRead += r;
                }
                String contentType = headers.get("content-type");
                if (contentType != null && contentType.contains("application/x-www-form-urlencoded")) {
                    String formData = new String(bodyRaw, StandardCharsets.UTF_8);
                    parseQueryString(formData, queryParams);
                }
            }

            InetSocketAddress remoteAddr = (InetSocketAddress) socket.getRemoteSocketAddress();

            Map<String, String> pathParams = null;
            Route matchedRoute = null;
            RouteEntry matchedEntry = null;
            for (RouteEntry re : Server.routes) {
                if (!re.method.equals(method)) continue;

                String pattern = re.pathPattern;
                String[] patParts = pattern.split("/");
                String[] urlParts = decodedPath.split("/");

                List<String> patList = new ArrayList<>();
                for (String p : patParts) if (!p.isEmpty()) patList.add(p);
                List<String> urlList = new ArrayList<>();
                for (String p : urlParts) if (!p.isEmpty()) urlList.add(p);

                if (patList.size() != urlList.size()) continue;

                boolean ok = true;
                Map<String, String> tempParams = new HashMap<>();
                for (int i = 0; i < patList.size(); i++) {
                    String pp = patList.get(i);
                    String up = urlList.get(i);
                    if (pp.length() > 0 && pp.charAt(0) == ':') {
                        tempParams.put(pp.substring(1), up);
                    } else {
                        if (!pp.equals(up)) {
                            ok = false;
                            break;
                        }
                    }
                }
                if (ok) {
                    pathParams = tempParams;
                    matchedRoute = re.handler;
                    matchedEntry = re;
                    break;
                }
            }

            if (matchedRoute != null) {
                ResponseImpl res = new ResponseImpl();
                res.setOutputStream(out);

                try {
                    boolean socketIsSecure = (socket instanceof javax.net.ssl.SSLSocket);
                    RequestImpl req = new RequestImpl(method, decodedPath, version, headers, queryParams,
        pathParams, remoteAddr, bodyRaw, Server.getServerInstance(), socketIsSecure);
                    req.setResponse(res);
                    Object routeResult = matchedRoute.handle(req, res);

                    if (!res.isWriteCalled() && !res.isCommitted()) {
                        if (routeResult != null) {
                            res.body(routeResult.toString());
                        }
                        try {
                            res.commitHeaders();
                        } catch (IOException ioe) {
                            logger.debug("Error committing response from route: " + ioe.getMessage());
                            if (!res.isCommitted()) {
                                sendError(out, 500, "Internal Server Error", method);
                            }
                            return false;
                        }
                    }
                } catch (ResponseImpl.HaltException he) {
                    try {
                    } catch (Exception ignored) {}
                } catch (Exception e) {
                    logger.debug("Exception in route handler: " + e.getMessage(), e);
                    if (!res.isWriteCalled() && !res.isCommitted()) {
                        sendError(out, 500, "Internal Server Error", method);
                        return false;
                    } else {
                        return false;
                    }
                }

                if (res.isWriteCalled()) {
                    return false;
                }

                String clientConn = "keep-alive";
                if (headers.containsKey("connection")) clientConn = headers.get("connection").toLowerCase();
                if ("close".equals(clientConn)) return false;
                return true;
            }

            if (!"GET".equals(method) && !"HEAD".equals(method)) {
                if ("POST".equals(method) || "PUT".equals(method)) {
                    sendError(out, 405, "Method Not Allowed", method);
                } else {
                    sendError(out, 501, "Not Implemented", method);
                }
                return false;
            }

            File requestedFile = new File(rootDir, decodedPath);
            String reqCanonical;
            try {
                reqCanonical = requestedFile.getCanonicalPath();
            } catch (IOException e) {
                sendError(out, 404, "Not Found", method);
                return false;
            }

            if (!reqCanonical.equals(rootCanonical) && !reqCanonical.startsWith(rootCanonical + File.separator)) {
                sendError(out, 403, "Forbidden", method);
                return false;
            }

            File f = new File(reqCanonical);
            if (!f.exists()) {
                sendError(out, 404, "Not Found", method);
                return false;
            }

            if (!f.isFile() || !f.canRead()) {
                sendError(out, 403, "Forbidden", method);
                return false;
            }

            if (headers.containsKey("if-modified-since")) {
                String ims = headers.get("if-modified-since");
                SimpleDateFormat rfc1123 = new SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US);
                rfc1123.setTimeZone(TimeZone.getTimeZone("GMT"));
                try {
                    Date sinceDate = rfc1123.parse(ims);
                    long fileLastModified = f.lastModified();
                    if (fileLastModified <= sinceDate.getTime()) {
                        StringBuilder resp = new StringBuilder();
                        resp.append("HTTP/1.1 304 Not Modified\r\n");
                        resp.append("Server: CIS5550Server\r\n");
                        String clientConn = headers.getOrDefault("connection", "").toLowerCase();
                        if ("close".equals(clientConn)) {
                            resp.append("Connection: close\r\n");
                        } else {
                            resp.append("Connection: keep-alive\r\n");
                        }
                        resp.append("\r\n");
                        out.write(resp.toString().getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        return !"close".equals(headers.getOrDefault("connection", "").toLowerCase());
                    }
                } catch (ParseException pe) {
                }
            }

            boolean hasRange = headers.containsKey("range");
            long totalLength = f.length();
            long sendStart = 0;
            long sendEnd = totalLength - 1;
            boolean isPartial = false;

            if (hasRange) {
                String range = headers.get("range").trim();
                if (range.startsWith("bytes=")) {
                    String spec = range.substring(6);
                    try {
                        if (spec.startsWith("-")) {
                            long suffix = Long.parseLong(spec.substring(1));
                            if (suffix > totalLength) suffix = totalLength;
                            sendStart = Math.max(0, totalLength - suffix);
                            sendEnd = totalLength - 1;
                            isPartial = true;
                        } else if (spec.endsWith("-")) {
                            long s = Long.parseLong(spec.substring(0, spec.length() - 1));
                            if (s < totalLength) {
                                sendStart = s;
                                sendEnd = totalLength - 1;
                                isPartial = true;
                            }
                        } else if (spec.contains("-")) {
                            String[] parts = spec.split("-", 2);
                            long s = Long.parseLong(parts[0]);
                            long e = Long.parseLong(parts[1]);
                            if (s <= e && s < totalLength) {
                                sendStart = s;
                                sendEnd = Math.min(e, totalLength - 1);
                                isPartial = true;
                            }
                        }
                    } catch (NumberFormatException nfe) {
                        isPartial = false;
                    }
                }
            }

            String contentType = getContentType(reqCanonical);
            long contentLengthToSend = (sendEnd - sendStart) + 1;

            StringBuilder respHeaders = new StringBuilder();

            if (isPartial) {
                respHeaders.append("HTTP/1.1 206 Partial Content\r\n");
            } else {
                respHeaders.append("HTTP/1.1 200 OK\r\n");
            }

            respHeaders.append("Server: CIS5550Server\r\n");
            respHeaders.append("Content-Type: ").append(contentType).append("\r\n");
            respHeaders.append("Content-Length: ").append(contentLengthToSend).append("\r\n");

            if (isPartial) {
                respHeaders.append("Content-Range: bytes ")
                           .append(sendStart).append("-").append(sendEnd)
                           .append("/").append(totalLength).append("\r\n");
            }

            String clientConn = headers.getOrDefault("connection", "").toLowerCase();
            if ("close".equals(clientConn)) {
                respHeaders.append("Connection: close\r\n");
            } else {
                respHeaders.append("Connection: keep-alive\r\n");
            }

            respHeaders.append("\r\n");
            out.write(respHeaders.toString().getBytes(StandardCharsets.UTF_8));

            if ("GET".equals(method)) {
                try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
                    raf.seek(sendStart);

                    byte[] buf = new byte[16 * 1024];
                    long remaining = contentLengthToSend;

                    while (remaining > 0) {
                        int toRead = (int) Math.min(buf.length, remaining);
                        int actuallyRead = raf.read(buf, 0, toRead);
                        if (actuallyRead == -1) break;
                        out.write(buf, 0, actuallyRead);
                        remaining -= actuallyRead;
                    }
                }
            }

            out.flush();

            return !"close".equals(clientConn);

        } catch (IOException ioe) {
            logger.debug("IOE in processRequest: " + ioe.getMessage());
            return false;
        }
    }

    private void parseQueryString(String queryString, Map<String, String> queryParams) {
        if (queryString == null || queryString.isEmpty()) return;

        for (String pair : queryString.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                try {
                    queryParams.put(
                        URLDecoder.decode(kv[0], "UTF-8"),
                        URLDecoder.decode(kv[1], "UTF-8")
                    );
                } catch (Exception ignored) {
                }
            }
        }
    }



    private boolean discardRequestBody(BufferedInputStream input, int contentLength) throws IOException {
        int bytesRemaining  =  contentLength; 
        byte[] buffer = new byte[8192]; 

        while (bytesRemaining > 0) {
            int bytesToRead = Math.min(buffer.length, bytesRemaining);
            int bytesRead = input.read(buffer, 0, bytesToRead);
            if (bytesRead == -1) {
                return false; 
            }
            bytesRemaining -= bytesRead;
        }
        return true;
    }

    //     private boolean discardRequestBody(BufferedInputStream input, int contentLength) throws IOException {
    //     int bytesRemaining = contentLength;
    //     byte[] buffer = new byte[8192];

    //     while (bytesRemaining > 0) {
    //         int bytesRead = input.read(buffer, 0, buffer.length);
    //         if (bytesRead == -1) return false;

    //    
    //         bytesRemaining -= buffer.length;
    //     }
    //     return true;
    // }

    private void sendError(BufferedOutputStream out, int code, String message, String method) throws IOException {
        String body = code + " " + message;
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        int bodyLen = "HEAD".equals(method) ? 0 : bodyBytes.length;

        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(code).append(" ").append(message).append("\r\n");
        sb.append("Server: CIS5550Server\r\n");
        sb.append("Content-Type: text/plain\r\n");
        sb.append("Content-Length: ").append(bodyLen).append("\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");

        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        if (bodyLen > 0) out.write(bodyBytes);
        out.flush();
    }

    private String getContentType(String urlOrPath) {
        String lower = urlOrPath.toLowerCase();

        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (lower.endsWith(".txt")) {
            return "text/plain";
        }
        if (lower.endsWith(".html") || lower.endsWith(".htm")) {
            return "text/html";
        }

        return "application/octet-stream";
    }
}


