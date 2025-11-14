package cis5550.webserver;

public class RouteEntry {
    public final String method;
    public final String pathPattern;
    public final Route handler;

    public RouteEntry(String method, String pathPattern, Route handler) {
        this.method = method;
        this.pathPattern = pathPattern;
        this.handler = handler;
    }
}


