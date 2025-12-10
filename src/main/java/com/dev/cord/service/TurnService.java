package com.dev.cord.service;

import com.dev.cord.dto.TurnCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

@Service
public class TurnService {

    @Value("${turn.secret}")
    private String turnSecret;

    private static final long TTL_SECONDS = 3600;

    public TurnCredentials generateCredentials(String userId) {
        long now = Instant.now().getEpochSecond();
        long expires = now + TTL_SECONDS;
        String username = expires + ":" + userId;
        String password = hmacSha1Base64(turnSecret, username);
        return new TurnCredentials(username, password, TTL_SECONDS);
    }

    private String hmacSha1Base64(String secret, String msg) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            byte[] raw = mac.doFinal(msg.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(raw);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

