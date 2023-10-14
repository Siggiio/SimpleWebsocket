package io.siggi.simplewebsocket;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

public class Util {
    private Util() {
    }

    static String generateNonce() {
        byte[] randomStuff = new byte[16];
        new SecureRandom().nextBytes(randomStuff);
        return Base64.getEncoder().encodeToString(randomStuff);
    }

    static String generateExpectedResponse(String nonce) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            md.update((nonce + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes());
            byte[] sha1Nonce = md.digest();
            return Base64.getEncoder().encodeToString(sha1Nonce);
        } catch (Exception e) {
            throw new RuntimeException("Something went wrong", e);
        }
    }
}
