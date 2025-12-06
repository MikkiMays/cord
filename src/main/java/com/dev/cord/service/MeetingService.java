package com.dev.cord.service;

import com.dev.cord.exception.MeetingNotFoundException;
import com.dev.cord.model.Meeting;
import com.dev.cord.repository.MeetingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Optional;

@Service
@RequiredArgsConstructor
public class MeetingService {

    private final MeetingRepository meetingRepository;

    public Meeting createMeeting(String meetingId) {
        return meetingRepository.findByMeetingId(meetingId)
                .orElseGet(() -> {
                    Meeting meeting = new Meeting();
                    meeting.setMeetingId(meetingId);
                    meeting.setStartTime(System.currentTimeMillis());
                    return meetingRepository.save(meeting);
                });
    }

    public Meeting requireMeeting(String meetingId) {
        return meetingRepository.findByMeetingId(meetingId)
                .orElseThrow(() -> new MeetingNotFoundException(meetingId));
    }

    public Optional<Meeting> getMeeting(String meetingId) {
        return meetingRepository.findByMeetingId(meetingId);
    }

    public void endMeeting(String meetingId) {
        meetingRepository.findByMeetingId(meetingId).ifPresent(meeting -> {
            meeting.setEndTime(System.currentTimeMillis());
            meetingRepository.save(meeting);
        });
    }
}
