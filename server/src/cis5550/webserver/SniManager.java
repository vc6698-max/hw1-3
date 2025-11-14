package cis5550.webserver;

import java.io.FileInputStream;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Principal;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;

public class SniManager {

    static class Host {
        String file, pass;
        Host(String file, String pass) {
            this.file = file;
            this.pass = pass;
        }
    }

    static Map<String,Host> hosts = new HashMap<>();

    public static void host(String name, String file, String pw) {
        if (name == null) return;
        hosts.put(name.toLowerCase(Locale.ROOT), new Host(file, pw));
    }

    public static ServerSocket createTlsServerSocket(int port, String defFile, String defPw) throws Exception {
        KeyMgr def = load(defFile, defPw);
        Map<String,KeyMgr> others = new HashMap<>();

        for (String n : hosts.keySet()) {
            Host h = hosts.get(n);
            try {
                others.put(n, load(h.file, h.pass));
            } catch (Exception e) {
                System.err.println("SniManager: failed for " + n);
            }
        }

        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(new KeyManager[]{ new MyMgr(def, others) }, null, null);
        return ctx.getServerSocketFactory().createServerSocket(port);
    }

    static KeyMgr load(String f, String p) throws Exception {
        Exception last = null;
        try {
            KeyStore ks = KeyStore.getInstance("JKS");
            try (FileInputStream fis = new FileInputStream(f)) {
                ks.load(fis, p.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, p.toCharArray());
            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509ExtendedKeyManager) return new KeyMgr((X509ExtendedKeyManager) km);
            }
        } catch (Exception e) {
            last = e;
        }

        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (FileInputStream fis = new FileInputStream(f)) {
                ks.load(fis, p.toCharArray());
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, p.toCharArray());
            for (KeyManager km : kmf.getKeyManagers()) {
                if (km instanceof X509ExtendedKeyManager) return new KeyMgr((X509ExtendedKeyManager) km);
            }
        } catch (Exception e) {
            if (last != null) e.addSuppressed(last);
            throw e;
        }

        throw new RuntimeException("no key manager found");
    }

    static class KeyMgr {
        X509ExtendedKeyManager base;
        KeyMgr(X509ExtendedKeyManager base) {
            this.base = base;
        }
    }

    static class MyMgr extends X509ExtendedKeyManager {
        KeyMgr def;
        Map<String,KeyMgr> others;

        MyMgr(KeyMgr def, Map<String,KeyMgr> others) {
            this.def = def;
            this.others = others;
        }

        KeyMgr pick(Socket s) {
            try {
                String host = getHost(s);
                if (host != null) {
                    KeyMgr m = others.get(host.toLowerCase(Locale.ROOT));
                    if (m != null) return m;
                }
            } catch (Throwable ignored) {}
            return def;
        }

        public String[] getClientAliases(String k, Principal[] p) {
            return def.base.getClientAliases(k, p);
        }

        public String chooseClientAlias(String[] k, Principal[] p, Socket s) {
            return def.base.chooseClientAlias(k, p, s);
        }

        public String[] getServerAliases(String k, Principal[] p) {
            return def.base.getServerAliases(k, p);
        }

        public String chooseServerAlias(String k, Principal[] p, Socket s) {
            return pick(s).base.chooseServerAlias(k, p, s);
        }

        public X509Certificate[] getCertificateChain(String a) {
            X509Certificate[] c = def.base.getCertificateChain(a);
            if (c != null) return c;
            for (KeyMgr m : others.values()) {
                X509Certificate[] cc = m.base.getCertificateChain(a);
                if (cc != null) return cc;
            }
            return null;
        }

        public PrivateKey getPrivateKey(String a) {
            PrivateKey pk = def.base.getPrivateKey(a);
            if (pk != null) return pk;
            for (KeyMgr m : others.values()) {
                PrivateKey p = m.base.getPrivateKey(a);
                if (p != null) return p;
            }
            return null;
        }

        public String chooseEngineClientAlias(String[] k, Principal[] p, SSLEngine e) {
            return def.base.chooseEngineClientAlias(k, p, e);
        }

        public String chooseEngineServerAlias(String k, Principal[] p, SSLEngine e) {
            return def.base.chooseEngineServerAlias(k, p, e);
        }
    }

    private static String getHost(Socket s) {
        if (s == null) return null;
        try {
            Class<?> c = Class.forName("cis5550.tools.SNIInspector");
            for (Method m : c.getMethods()) {
                Class<?>[] pts = m.getParameterTypes();
                if (pts.length == 1 && pts[0].isAssignableFrom(s.getClass()) && m.getReturnType() == String.class) {
                    try {
                        Object r;
                        if (java.lang.reflect.Modifier.isStatic(m.getModifiers())) {
                            r = m.invoke(null, s);
                        } else {
                            Object inst = c.getDeclaredConstructor().newInstance();
                            r = m.invoke(inst, s);
                        }
                        if (r instanceof String) return (String) r;
                    } catch (Throwable t) {}
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }
}