package cis5550.webserver;

import cis5550.tools.Logger;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.security.KeyStore;
import java.security.SecureRandom;


import javax.net.ssl.SSLContext;
import javax.net.ssl.KeyManagerFactory;


public class Server {

    private static final Logger logger = Logger.getLogger(Server.class);

    public static final int NUM_WORKERS = 100;

    private final int port;
    private final String rootDir;
    private final ConnectionQueue connQueue = new ConnectionQueue();

    static final List<RouteEntry> routes = new CopyOnWriteArrayList<>();

    //    private long startTime = System.currentTimeMillis();  
    // private int connectionCount = 0;                      
    // private boolean allowDirectoryListing = false; 
    
    private static volatile Server serverInstance = null;
    private static volatile boolean serverLaunched = false;
    private static int configuredPort = 80;
    private static String configuredRoot = ".";

    private static int configuredSecurePort = 0;

    private static final String KEYSTORE_FILENAME = "keystore.jks";
    private static final String KEYSTORE_PASSWORD = "secret";

    private static final Map<String, SessionImpl> sessions = new ConcurrentHashMap<>();

    public static Server getServerInstance() {
        return serverInstance;
    }

    public static void port(int p) {
        configuredPort = p;
    }

    public static void securePort(int p) {
        configuredSecurePort = p;
    }

    public static synchronized void host(String hostname, String keystoreFile, String password) {
        SniManager.host(hostname, keystoreFile, password);
    }

    public static void get(String path, Route r) {
        addRoute("GET", path, r);
    }

    public static void post(String path, Route r) {
        addRoute("POST", path, r);
    }

    public static void put(String path, Route r) {
        addRoute("PUT", path, r);
    }

    public static class staticFiles {
        public static void location(String p) {
            configuredRoot = p;
            launchIfNeeded();
        }
    }

    private static void addRoute(String method, String path, Route r) {
        routes.add(new RouteEntry(method, path, r));
        launchIfNeeded();
    }

    private static synchronized void launchIfNeeded() {
        if (!serverLaunched) {
            serverLaunched = true;
            serverInstance = new Server(configuredPort, configuredRoot);
            Thread t = new Thread(new Runnable() {
                public void run() {
                    serverInstance.start();
                }
            }, "CIS5550-Server-Main");
            t.setDaemon(false);
            t.start();
        }
    }

    public Server(int port, String rootDir) {
        this.port = port;
        this.rootDir = rootDir;
    }

    public void start() {
        for (int i = 0; i < NUM_WORKERS; i++) {
            Thread w = new Thread(new Worker(connQueue, rootDir), "Worker-" + i);
            w.setDaemon(true);
            w.start();
        }

        Thread sessionCleanup = new Thread(() -> {
            while (true) {
                try {
                    long now = System.currentTimeMillis();
                    for (Map.Entry<String, SessionImpl> e : sessions.entrySet()) {
                        SessionImpl s = e.getValue();
                        int tmax = s.getMaxActiveInterval();
                        if (tmax >= 0) {
                            long ageMs = now - s.lastAccessedTime();
                            if (ageMs > (long) tmax * 1000L) {
                                sessions.remove(e.getKey());
                            }
                        }
                    }
                    TimeUnit.SECONDS.sleep(5);
                } catch (InterruptedException ie) {
                } catch (Throwable t) {
                    logger.debug("Session cleanup error: " + t.getMessage(), t);
                }
            }
        }, "Session-Cleanup");
        sessionCleanup.setDaemon(true);
        sessionCleanup.start();

        ServerSocket serverSocket = null;
        ServerSocket tlsServerSocket = null;

        try {
            serverSocket = new ServerSocket(port);
            logger.info("Server started on port " + port + " serving directory " + rootDir);

            if (configuredSecurePort > 0) {
                try {
                    tlsServerSocket = SniManager.createTlsServerSocket(configuredSecurePort, KEYSTORE_FILENAME, KEYSTORE_PASSWORD);
                     logger.info("TLS server started on port " + configuredSecurePort);
                } catch (Exception e) {
                    logger.error("Failed to start TLS server socket on port " + configuredSecurePort + ": " + e.getMessage(), e);
                }
            }

            if (tlsServerSocket != null) {
                final ServerSocket finalTls = tlsServerSocket;
                Thread acceptTls = new Thread(() -> {
                    try {
                        while (true) {
                            try {
                                Socket client = finalTls.accept();
                                connQueue.enqueue(client);
                            } catch (IOException ioe) {
                                logger.debug("TLS accept error: " + ioe.getMessage());
                            }
                        }
                    } finally {
                        try { finalTls.close(); } catch (IOException ignored) {}
                    }
                }, "TLS-Acceptor");
                acceptTls.setDaemon(true);
                acceptTls.start();
            }

            try {
                while (true) {
                    Socket client = serverSocket.accept();
                    connQueue.enqueue(client);
                }
            } finally {
                try { serverSocket.close(); } catch (IOException ignored) {}
            }

        } catch (IOException e) {
            logger.error("Server socket error: " + e.getMessage(), e);
        } finally {
            if (tlsServerSocket != null) {
                try { tlsServerSocket.close(); } catch (IOException ignored) {}
            }
        }
    }

    static SessionImpl getSessionById(String id) {
        return sessions.get(id);
    }

    static void putSession(String id, SessionImpl s) {
        sessions.put(id, s);
    }

    static void removeSession(String id) {
        sessions.remove(id);
    }

    static String generateSessionId() {
        byte[] b = new byte[16];
        new SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) {
            String hx = Integer.toHexString((x & 0xFF));
            if (hx.length() == 1) sb.append('0');
            sb.append(hx);
        }
        return sb.toString();
    }

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Written by Vijay Chandar");
            System.exit(1);
        }

        int port;
        try {
            port = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            System.out.println("Written by Vijay Chandar");
            System.exit(1);
            return;
        }
        
        String rootDir = args[1];
        Server server = new Server(port, rootDir);
        server.start();
    }
}