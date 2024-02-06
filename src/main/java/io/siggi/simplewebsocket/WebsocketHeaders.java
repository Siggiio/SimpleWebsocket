package io.siggi.simplewebsocket;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class WebsocketHeaders {
    private final Map<String, List<String>> headers = new HashMap<>();

    public WebsocketHeaders() {
        setHeader("User-Agent", "Siggi-SimpleWebsocketClient");
        setHeader("Connection", "Upgrade");
        setHeader("Upgrade", "websocket");
    }

    public WebsocketHeaders setHeader(String key, String value) {
        List<String> list = new ArrayList<>();
        list.add(value);
        headers.put(key, list);
        return this;
    }

    public WebsocketHeaders addHeader(String key, String value) {
        List<String> list = headers.computeIfAbsent(key, k -> new ArrayList<>());
        list.add(value);
        return this;
    }

    public String getHeader(String key) {
        List<String> list = headers.get(key);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    public List<String> getHeaderList(String key) {
        return headers.get(key);
    }

    public Map<String, List<String>> getHeaders() {
        return headers;
    }
}
