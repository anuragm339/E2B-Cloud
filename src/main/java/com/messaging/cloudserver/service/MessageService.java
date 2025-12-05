package com.messaging.cloudserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.messaging.cloudserver.model.Event;
import com.messaging.cloudserver.model.Message;
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
    @Scheduled(fixedDelay = "5s", initialDelay = "2s")
    public void pollData() {
        if ("PRODUCTION".equalsIgnoreCase(dataMode)) {
            injectActualData();
        } else {
            generateTestData();
        }
    }

    /**
     * Read events from SQLite database and convert to message format
     */
    private void injectActualData() {
        long lastOffset = messages.size();
        List<Event> events = eventRepository.fetchEvents(lastOffset, 100);

        if (events.isEmpty()) {
            LOG.debug("[SQLITE] No new events found (last offset: {})", lastOffset);
            return;
        }

        LOG.info("[SQLITE] Found {} new events to load", events.size());

        for (Event event : events) {
            try {
                JsonNode dataJson = objectMapper.readTree(event.getData());

                // Extract topic from data or use default
                String topic = dataJson.has("topic")
                        ? dataJson.get("topic").asText()
                        : "prices-v1";

                // Ensure topic is in allowed list
                if (!ALL_TOPICS.contains(topic)) {
                    LOG.warn("[SQLITE] Unknown topic '{}', using 'prices-v1'", topic);
                    topic = "prices-v1";
                }

                // Convert created_at to ISO format if needed
                String createdAt = event.getCreatedAt();
                if (createdAt != null && !createdAt.endsWith("Z")) {
                    if (createdAt.contains("T")) {
                        createdAt = createdAt.endsWith("Z") ? createdAt : createdAt + "Z";
                    } else {
                        createdAt = Instant.now().toString();
                    }
                } else if (createdAt == null) {
                    createdAt = Instant.now().toString();
                }

                // Create message in expected format
                Message message = new Message(
                        "sqlite_" + event.getMsgOffset(),
                        event.getType() != null ? event.getType().toUpperCase() : "MESSAGE",
                        topic,
                        event.getData(),
                        createdAt
                );

                messages.add(message);

            } catch (JsonProcessingException e) {
                LOG.error("[SQLITE] Error parsing JSON for offset {}: {}",
                        event.getMsgOffset(), e.getMessage());
            }
        }

        LOG.info("[SQLITE] Loaded {} events. Total messages: {}", events.size(), messages.size());
    }

    /**
     * Generate random test messages
     */
    private void generateTestData() {
        int batchSize = ThreadLocalRandom.current().nextInt(10, 31);
        int currentSize = messages.size();

        for (int i = 0; i < batchSize; i++) {
            Message message = generateTestMessage(currentSize + i);
            messages.add(message);
        }

        LOG.debug("[GENERATOR] Generated {} test messages. Total: {}", batchSize, messages.size());
    }

    /**
     * Generate a single test message
     */
    private Message generateTestMessage(int index) {
        String topic = ALL_TOPICS.get(ThreadLocalRandom.current().nextInt(ALL_TOPICS.size()));
        String eventType = ThreadLocalRandom.current().nextDouble() < 0.25 ? "DELETE" : "MESSAGE";

        Map<String, Object> data = new HashMap<>();
        data.put("topic", topic);
        data.put("messageId", index);
        data.put("price", ThreadLocalRandom.current().nextDouble(10, 1000));
        data.put("currency", "GBP");
        data.put("timestamp", Instant.now().toString());
        data.put("padding", "x".repeat(ThreadLocalRandom.current().nextInt(100, 500)));

        String dataJson;
        try {
            dataJson = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            dataJson = "{}";
        }

        return new Message(
                topic + "_" + index,
                eventType,
                topic,
                dataJson,
                Instant.now().toString()
        );
    }

    /**
     * Get messages starting from offset
     */
    public List<Message> getMessages(int offset, int limit) {
        List<Message> result = new ArrayList<>();
        Iterator<Message> iterator = messages.iterator();

        // Skip to offset
        int current = 0;
        while (iterator.hasNext() && current < offset) {
            iterator.next();
            current++;
        }

        // Collect up to limit
        int collected = 0;
        while (iterator.hasNext() && collected < limit) {
            result.add(iterator.next());
            collected++;
        }

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
