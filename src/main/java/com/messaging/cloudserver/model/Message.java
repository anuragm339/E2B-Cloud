package com.messaging.cloudserver.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message model representing a message to be consumed by brokers
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @JsonProperty("msgKey")
    private String msgKey;

    @JsonProperty("eventType")
    private String eventType;

    @JsonProperty("topic")
    private String topic;

    @JsonProperty("data")
    private String data;

    @JsonProperty("createdAt")
    private String createdAt;
}
