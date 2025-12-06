package com.dev.cord.repository;

import com.dev.cord.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MeetingRepository extends JpaRepository<Meeting, String> {
    Optional<Meeting> findByMeetingId(String meetingId);
}
