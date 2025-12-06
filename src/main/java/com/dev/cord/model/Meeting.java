package com.dev.cord.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "cord_meeting")
@NoArgsConstructor
@EqualsAndHashCode
@Data
public class Meeting {

    @Id
    @Column(name = "meeting_id", nullable = false, unique = true, updatable = false)
    private String meetingId;
    @Column(name = "start_time")
    private Long startTime;
    @Column(name = "end_time")
    private Long endTime;

}
