package com.dev.cord.exception;

public class MeetingNotFoundException extends RuntimeException {
    public MeetingNotFoundException(String meetingId) {
        super("Meeting with id '%s' not found".formatted(meetingId));
    }
}
