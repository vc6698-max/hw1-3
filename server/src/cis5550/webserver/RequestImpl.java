package cis5550.webserver;

import java.util.*;
import java.net.*;
import java.nio.charset.*;

// Provided as part of the framework code

class RequestImpl implements Request {
  String method;
  String url;
  String protocol;
  InetSocketAddress remoteAddr;
  Map<String,String> headers;
  Map<String,String> queryParams;
  Map<String,String> params;
  byte bodyRaw[];
  Server server;

  ResponseImpl response = null;



  private final boolean isSecure;

  RequestImpl(String methodArg, String urlArg, String protocolArg, Map<String,String> headersArg, Map<String,String> queryParamsArg, Map<String,String> paramsArg, InetSocketAddress remoteAddrArg, byte bodyRawArg[], Server serverArg, boolean isSecureArg) {
    method = methodArg;
    url = urlArg;
    remoteAddr = remoteAddrArg;
    protocol = protocolArg;
    headers = headersArg;
    queryParams = queryParamsArg;
    params = paramsArg;
    bodyRaw = bodyRawArg;
    server = serverArg;
    this.isSecure = isSecureArg;
  }


  public void setResponse(ResponseImpl r) {
    this.response = r;
  }

  public String requestMethod() {
  	return method;
  }
  public void setParams(Map<String,String> paramsArg) {
    params = paramsArg;
  }
  public int port() {
  	return remoteAddr.getPort();
  }
  public String url() {
  	return url;
  }
  public String protocol() {
  	return protocol;
  }
  public String contentType() {
  	return headers.get("content-type");
  }
  public String ip() {
  	return remoteAddr.getAddress().getHostAddress();
  }
  public String body() {
    return new String(bodyRaw, StandardCharsets.UTF_8);
  }
  public byte[] bodyAsBytes() {
  	return bodyRaw;
  }
  public int contentLength() {
  	return bodyRaw.length;
  }
  public String headers(String name) {
  	return headers.get(name.toLowerCase());
  }
  public Set<String> headers() {
  	return headers.keySet();
  }
  public String queryParams(String param) {
  	return queryParams.get(param);
  }
  public Set<String> queryParams() {
  	return queryParams.keySet();
  }
  public String params(String param) {
    return params.get(param);
  }
   
  public Map<String,String> params() {
    return params;
  }


  private SessionImpl cachedSession = null;

  public  Session session() {
    if (cachedSession != null) {
      return cachedSession;
    }


    String cookieHeader  =  headers.get("cookie");
    String sid = null;
    if (cookieHeader != null) {
      String[] parts = cookieHeader.split(";");
      for (String p : parts) {
        String s = p.trim();
        int eq = s.indexOf('=');
        if (eq > 0) {
          String k = s.substring(0, eq).trim();
          String v = s.substring(eq + 1).trim();
          if ("SessionID".equals(k)) {
            sid = v;
            break;
          }
        }
      }
    }


    if (sid != null) {
      SessionImpl existing = Server.getSessionById(sid);
      if (existing != null) {
        int tmax = existing.getMaxActiveInterval();
        if (tmax >= 0) {
          long now = System.currentTimeMillis();
          long ageMs = now - existing.lastAccessedTime();
          if (ageMs <= (long)tmax * 1000L) {
            existing.touch();
            cachedSession = existing;
            return cachedSession;
          } else {
            Server.removeSession(sid);
          }
        } else {
          existing.touch();
          cachedSession = existing;
          return cachedSession;
        }
      }
    }

    String newId = Server.generateSessionId();
    SessionImpl newSession = new SessionImpl(newId);
    Server.putSession(newId, newSession);


    if (this.response != null) {

      StringBuilder cookieBuilder = new StringBuilder();
      cookieBuilder.append("SessionID=").append(newId);
      cookieBuilder.append("; Path=/");
      cookieBuilder.append("; HttpOnly");
      cookieBuilder.append("; SameSite=Strict");

      if (this.isSecure) {
        cookieBuilder.append("; Secure");
      }

      this.response.header("Set-Cookie", cookieBuilder.toString());
    } else {

    }

    cachedSession = newSession;
    return cachedSession;
  }
}
