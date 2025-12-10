package com.dev.cord.controller;

import com.dev.cord.dto.TurnCredentials;
import com.dev.cord.service.TurnService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/turn")
public class TurnController {

    private final TurnService turnService;

    public TurnController(TurnService turnService) {
        this.turnService = turnService;
    }

    @GetMapping("/credentials")
    public Map<String, Object> getTurnCredentials(Principal principal) {
        String userId = principal != null ? principal.getName() : "anon";
        TurnCredentials creds = turnService.generateCredentials(userId);

        return Map.of(
                "ttl", creds.ttlSeconds(),
                "iceServers", List.of(
                        Map.of("urls", List.of(
                                "stun:stun.l.google.com:19302",
                                "stun:stun1.l.google.com:19302"
                        )),
                        Map.of(
                                "urls", List.of(
                                        "turn:nikg.tech:3478?transport=udp",
                                        "turns:nikg.tech:5349?transport=tcp"
                                ),
                                "username", creds.username(),
                                "credential", creds.password()
                        )
                )
        );
    }
}

