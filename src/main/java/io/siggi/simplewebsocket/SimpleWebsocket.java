package io.siggi.simplewebsocket;

import io.siggi.http.HTTPRequest;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

public class SimpleWebsocket implements Closeable {
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    private long maxPayloadLength = 16384;
    private long pingFrequency = 5000L;
    private boolean sentCloseFrame = false;
    private boolean nonBlockingMode = false;
    private final Object sendQueueLock = new Object();
    private boolean closed = false;
    private final List<WebSocketMessage> sendQueue = new LinkedList<>();

    private final long id;
    private static long nextId = 0;

    private static synchronized long id() {
        return nextId++;
    }

    public SimpleWebsocket(Socket socket) throws IOException {
        this.socket = socket;
        this.in = socket.getInputStream();
        this.out = socket.getOutputStream();
        this.socket.setSoTimeout(15000);
        this.id = id();
    }

    public static SimpleWebsocket connect(URI uri) throws IOException {
        return SimpleWebsocketUtil.connect(uri);
    }

    public static SimpleWebsocket accept(HTTPRequest request) throws IOException {
        return SimpleWebsocketUtil.accept(request);
    }

    public static boolean isWebsocketRequest(HTTPRequest request) {
        return SimpleWebsocketUtil.isWebsocketRequest(request);
    }

    public void useNonBlockingMode(long pingFrequency) {
        synchronized (sendQueueLock) {
            if (nonBlockingMode) {
                return;
            }
            nonBlockingMode = true;
        }
        this.pingFrequency = pingFrequency;
        new Thread(this::incomingThread, "WebSocket-" + id + "-In").start();
        new Thread(this::outgoingThread, "WebSocket-" + id + "-Out").start();
    }

    public WebSocketMessage read() throws IOException {
        if (nonBlockingMode) {
            throw new IllegalStateException("Non blocking mode in use! Add an event listener instead!");
        }
        return read0();
    }

    private WebSocketMessage read0() throws IOException {
        int opcode = 0;
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        while (true) {
            WebSocketPacket pkt = read1();
            if (pkt == null) {
                return null;
            }
            if (pkt.opcode > 0) {
                o.reset();
                opcode = pkt.opcode;
            }
            o.write(pkt.decoded);
            if (pkt.fin) {
                WebSocketMessage msg = new WebSocketMessage(opcode, o.toByteArray());
                o.reset();
                return msg;
            }
        }
    }

    private WebSocketPacket read1() throws IOException {
        int opcode = in.read();
        if (opcode == -1) {
            return null;
        }
        boolean fin = (opcode & 0x80) != 0;
        boolean rsv1 = (opcode & 0x40) != 0;
        boolean rsv2 = (opcode & 0x20) != 0;
        boolean rsv3 = (opcode & 0x10) != 0;
        opcode = opcode & 0x0F;
        long lengthOfPayload = (long) in.read();
        if (opcode == -1L) {
            return null;
        }
        boolean mask = (lengthOfPayload & 0x80L) != 0L;
        lengthOfPayload = lengthOfPayload & 0x7F;
        if (lengthOfPayload == 126L) {
            lengthOfPayload = (long) ((in.read() << 8) + in.read());
        } else if (lengthOfPayload == 127L) {
            lengthOfPayload = ((long) in.read() << 56)
                    + ((long) in.read() << 48)
                    + ((long) in.read() << 40)
                    + ((long) in.read() << 32)
                    + ((long) in.read() << 24)
                    + ((long) in.read() << 16)
                    + ((long) in.read() << 8)
                    + (long) in.read();
        }
        if (lengthOfPayload > maxPayloadLength) {
            return null;
        }
        byte[] maskKey = new byte[4];
        int amountRead = 0;
        int c;
        if (mask) {
            while (amountRead < 4) {
                c = in.read(maskKey, amountRead, 4 - amountRead);
                if (c == -1) {
                    return null;
                }
                amountRead += c;
            }
        }
        byte[] message = new byte[(int) lengthOfPayload];
        amountRead = 0;
        while (amountRead < lengthOfPayload) {
            c = in.read(message, amountRead, message.length - amountRead);
            if (c == -1) {
                return null;
            }
            amountRead += c;
        }
        byte[] decoded = new byte[message.length];
        if (mask) {
            for (int i = 0; i < message.length; i++) {
                decoded[i] = (byte) ((((int) message[i]) & 0xff) ^ (((int) maskKey[i % 4]) & 0xff));
            }
        } else {
            System.arraycopy(message, 0, decoded, 0, message.length);
        }
        return new WebSocketPacket(fin, rsv1, rsv2, rsv3, opcode, mask, maskKey, lengthOfPayload, message, decoded);
    }

    public void send(WebSocketMessage msg) throws IOException {
        synchronized (sendQueueLock) {
            if (msg.getOpcode() == WebSocketMessage.OPCODE_CLOSE) {
                if (sentCloseFrame) {
                    return;
                }
                sentCloseFrame = true;
            } else {
                if (sentCloseFrame) {
                    throw new IllegalStateException("Already sent close frame!");
                }
            }
            if (nonBlockingMode) {
                sendQueue.add(msg);
                sendQueueLock.notifyAll();
            } else {
                send0(msg);
            }
        }
    }

    private void send0(WebSocketMessage msg) throws IOException {
        int headerLength = 2;
        byte[] header = new byte[10];
        header[0] = (byte) (0x80 + (msg.getOpcode() & 0xF));
        int len = msg.getLength();
        if (len >= 126 && len <= 65535) {
            headerLength = 4;
            header[1] = (byte) (126);
            header[2] = (byte) ((len >> 8) & 0xff);
            header[3] = (byte) (len & 0xff);
        } else if (len >= 65536) {
            headerLength = 10;
            header[1] = (byte) 127;
            header[2] = (byte) 0;
            header[3] = (byte) 0;
            header[4] = (byte) 0;
            header[5] = (byte) 0;
            header[6] = (byte) ((len >> 24) & 0xff);
            header[7] = (byte) ((len >> 16) & 0xff);
            header[8] = (byte) ((len >> 8) & 0xff);
            header[9] = (byte) (len & 0xff);
        } else {
            header[1] = (byte) (len);
        }
        out.write(header, 0, headerLength);
        out.write(msg.getBytes());
        out.flush();
    }

    @Override
    public void close() throws IOException {
        synchronized (sendQueueLock) {
            if (closed) {
                return;
            }
            closed = true;
            sendQueueLock.notifyAll();
        }
        send(WebSocketMessage.create(WebSocketMessage.OPCODE_CLOSE));
        socket.close();
    }

    private final Set<WebSocketListener> listeners = new HashSet<>();

    public void addListener(WebSocketListener listener) {
        synchronized (listeners) {
            listeners.add(listener);
        }
    }

    public void removeListener(WebSocketListener listener) {
        synchronized (listeners) {
            listeners.remove(listener);
        }
    }

    private void incomingThread() {
        try {
            while (true) {
                WebSocketMessage msg = read0();
                if (msg == null) {
                    break;
                }
                switch (msg.getOpcode()) {
                    case WebSocketMessage.OPCODE_PING:
                        break;
                    case WebSocketMessage.OPCODE_PONG:
                        continue;
                }
                synchronized (listeners) {
                    for (WebSocketListener listener : listeners) {
                        try {
                            listener.receivedMessage(this, msg);
                        } catch (Exception e) {
                        }
                    }
                }
            }
        } catch (IOException e) {
        }
        try {
            close();
        } catch (Exception e) {
        }
        synchronized (listeners) {
            for (WebSocketListener listener : listeners) {
                listener.socketClosed(this);
            }
        }
    }

    private void outgoingThread() {
        long lastSentPing = System.currentTimeMillis();
        try {
            a:
            while (true) {
                WebSocketMessage msg;
                synchronized (sendQueueLock) {
                    if (closed) {
                        break;
                    }
                    while (sendQueue.isEmpty()) {
                        if (closed) {
                            break a;
                        }
                        long now = System.currentTimeMillis();
                        boolean needToSendPing = pingFrequency > 0L && (now - lastSentPing) >= pingFrequency;
                        long nextPing;
                        if (needToSendPing) {
                            lastSentPing = now;
                            send0(WebSocketMessage.create(WebSocketMessage.OPCODE_PING));
                            nextPing = pingFrequency;
                        } else {
                            nextPing = pingFrequency - (now - lastSentPing);
                        }
                        try {
                            sendQueueLock.wait(nextPing);
                        } catch (InterruptedException e) {
                            break a;
                        }
                        now = System.currentTimeMillis();
                        needToSendPing = (now - lastSentPing) >= pingFrequency;
                        if (needToSendPing) {
                            lastSentPing = now;
                            send0(WebSocketMessage.create(WebSocketMessage.OPCODE_PING));
                        }
                    }
                    msg = sendQueue.remove(0);
                }
                if (msg != null) {
                    send0(msg);
                }
            }
        } catch (IOException e) {
        }
        try {
            close();
        } catch (Exception e) {
        }
    }

    private static class WebSocketPacket {

        private final boolean fin;
        private final boolean rsv1;
        private final boolean rsv2;
        private final boolean rsv3;
        private final int opcode;
        private final boolean mask;
        private final byte[] maskKey;
        private final long lengthOfPayload;
        private final byte[] message;
        private final byte[] decoded;

        private WebSocketPacket(boolean fin, boolean rsv1, boolean rsv2, boolean rsv3, int opcode, boolean mask, byte[] maskKey, long lengthOfPayload, byte[] message, byte[] decoded) {
            this.fin = fin;
            this.rsv1 = rsv1;
            this.rsv2 = rsv2;
            this.rsv3 = rsv3;
            this.opcode = opcode;
            this.mask = mask;
            this.maskKey = maskKey;
            this.lengthOfPayload = lengthOfPayload;
            this.message = message;
            this.decoded = decoded;
        }

    }
}
