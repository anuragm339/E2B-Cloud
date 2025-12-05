package com.messaging.cloudserver.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Event model representing a database event record
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Event {
    private Long msgOffset;
    private String data;
    private String createdAt;
    private String type;
}
