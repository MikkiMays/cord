package com.dev.cord.controller;

import com.dev.cord.model.Meeting;
import com.dev.cord.service.MeetingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/meetings")
@RequiredArgsConstructor
@CrossOrigin(origins = {"http://localhost:3000", "http://localhost:3001"}, allowCredentials = "true")
public class MeetingController {

    private final MeetingService meetingService;

    @PostMapping
    public Meeting createMeeting() {
        String meetingId = UUID.randomUUID().toString();
        return meetingService.createMeeting(meetingId);
    }

    @PostMapping("/{meetingId}/join")
    public ResponseEntity<Meeting> joinMeeting(@PathVariable String meetingId) {
        return ResponseEntity.ok(meetingService.createMeeting(meetingId));
    }

    @PostMapping("/{meetingId}")
    public ResponseEntity<Meeting> ensureMeeting(@PathVariable String meetingId) {
        return meetingService.getMeeting(meetingId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{meetingId}")
    public ResponseEntity<Meeting> getMeeting(@PathVariable String meetingId) {
        return meetingService.getMeeting(meetingId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @PostMapping("/{meetingId}/end")
    public ResponseEntity<Void> endMeeting(@PathVariable String meetingId) {
        meetingService.endMeeting(meetingId);
        return ResponseEntity.noContent().build();
    }
}
