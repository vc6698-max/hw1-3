package cis5550.test;

import cis5550.webserver.Server;

public class MyTestServer {
    public static void main(String[] args) {
        Server.port(80);          // HTTP 
        Server.securePort(443);    // HTTPS
        Server.get("/", (req, res) -> {
            return "Hello World - this is Vijay Chandar";
        });
    }
}
