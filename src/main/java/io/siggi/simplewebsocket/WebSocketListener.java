package io.siggi.simplewebsocket;

public interface WebSocketListener {
    public void receivedMessage(SimpleWebsocket socket, WebSocketMessage message);

    public void socketClosed(SimpleWebsocket socket);
}
