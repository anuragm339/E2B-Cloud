package com.messaging.cloudserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messaging.cloudserver.model.Event;
import com.messaging.cloudserver.model.Message;
import com.messaging.cloudserver.model.MessageRecord;
import com.messaging.cloudserver.repository.EventRepository;
import io.micronaut.context.annotation.Property;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service for managing messages from database or generating test data
 */
@Singleton
public class MessageService {
    private static final Logger LOG = LoggerFactory.getLogger(MessageService.class);

    private static final List<String> ALL_TOPICS = Arrays.asList(
            "prices-v1", "reference-data-v5", "non-promotable-products", "prices-v4",
            "minimum-price", "deposit", "product-base-document", "search-product",
            "location", "location-clusters", "selling-restrictions", "colleague-facts-jobs",
            "colleague-facts-legacy", "loss-prevention-configuration",
            "loss-prevention-store-configuration", "loss-prevention-product",
            "loss-prevention-rule-config", "stored-value-services-banned-promotion",
            "stored-value-services-active-promotion", "colleague-card-pin",
            "colleague-card-pin-v2", "dcxp-content", "restriction-rules", "dcxp-ugc"
    );

    private final Queue<Message> messages = new ConcurrentLinkedQueue<>();
    private final EventRepository eventRepository;
    private final ObjectMapper objectMapper;
    private final String dataMode;

    public MessageService(
            EventRepository eventRepository,
            ObjectMapper objectMapper,
            @Property(name = "data.mode", defaultValue = "TEST") String dataMode) {
        this.eventRepository = eventRepository;
        this.objectMapper = objectMapper;
        this.dataMode = dataMode;
    }

    @PostConstruct
    public void init() {
        LOG.info("=".repeat(70));
        LOG.info("  Message Service Initialized");
        LOG.info("=".repeat(70));
        LOG.info("  Mode: {}", dataMode);
        LOG.info("=".repeat(70));

        if ("PRODUCTION".equalsIgnoreCase(dataMode)) {
            LOG.info("[GENERATOR] PRODUCTION MODE - Using inject_actual_data()");
        } else {
            LOG.info("[GENERATOR] TEST MODE - Using random data generation");
        }
    }

    /**
     * Scheduled task to poll database or generate test data
     * Runs every 5 seconds
     */


    /**
     * Read events from SQLite database and convert to message format
     */


    /**
     * Get messages starting from offset
     */
    public List<MessageRecord> getMessages(int offset, int limit) {
        List<MessageRecord> result = eventRepository.fetchEvents(offset, limit);
        return result;
    }

    /**
     * Get total message count
     */
    public int getTotalMessages() {
        return messages.size();
    }

    /**
     * Get recent messages for status display
     */
    public List<Map<String, Object>> getRecentMessages(int count) {
        List<Map<String, Object>> recent = new ArrayList<>();
        List<Message> allMessages = new ArrayList<>(messages);

        int start = Math.max(0, allMessages.size() - count);
        for (int i = start; i < allMessages.size(); i++) {
            Map<String, Object> info = new HashMap<>();
            info.put("offset", i);
            info.put("key", allMessages.get(i).getMsgKey());
            recent.add(info);
        }

        return recent;
    }

    public String getDataMode() {
        return dataMode;
    }
}
