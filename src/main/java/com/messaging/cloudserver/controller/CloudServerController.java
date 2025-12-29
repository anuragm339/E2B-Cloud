package com.messaging.cloudserver.controller;

import com.messaging.cloudserver.model.EventType;
import com.messaging.cloudserver.model.Message;
import com.messaging.cloudserver.model.MessageRecord;
import com.messaging.cloudserver.repository.EventRepository;
import com.messaging.cloudserver.scheduler.DataInjectionScheduler;
import com.messaging.cloudserver.service.MessageService;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;
import java.util.stream.LongStream;
import java.util.zip.CRC32;

/**
 * REST controller for cloud server endpoints
 */
@Controller
public class CloudServerController {
    private static final Logger LOG = LoggerFactory.getLogger(CloudServerController.class);

    private final MessageService messageService;
    private final String serverPublicUrl;
    private final EventRepository eventRepository;

    public CloudServerController(MessageService messageService,
                                  @Value("${server.public.url}") String serverPublicUrl,
                                  @Nullable EventRepository eventRepository) {
        this.messageService = messageService;
        this.serverPublicUrl = serverPublicUrl;
        this.eventRepository =eventRepository;
        LOG.info("[CLOUD] Server public URL configured as: {}", serverPublicUrl);
    }

    // ---------- CONFIG ----------
    private static final int MAX_RECORD_SIZE = 30 * 1024;   // 300 KB max per record
    private static final int TARGET_BATCH_SIZE = 1024 * 1024; // ~1 MB per poll
    private static final long TOTAL_DUMMY_MESSAGES = 10_000_000; // or Long.MAX_VALUE for infinite stream

    private static final Random RANDOM = new Random(12345);

    private static final List<String> TOPICS = List.of(
            "prices-v1", "reference-data-v5", "non-promotable-products", "prices-v4",
            "minimum-price", "deposit", "product-base-document", "search-product",
            "location", "location-clusters", "selling-restrictions", "colleague-facts-jobs",
            "colleague-facts-legacy", "loss-prevention-configuration",
            "loss-prevention-store-configuration", "loss-prevention-product",
            "loss-prevention-rule-config", "stored-value-services-banned-promotion",
            "stored-value-services-active-promotion", "colleague-card-pin",
            "colleague-card-pin-v2", "dcxp-content", "restriction-rules", "dcxp-ugc"
    );

    @Get("/pipe/poll")
    public HttpResponse<List<MessageRecord>> pollMessages(
            @QueryValue(defaultValue = "0") long offset
    ) throws SQLException {
        LOG.info("Poll request received offset={}", offset);

        List<MessageRecord> messageRecords = eventRepository.fetchEvents(offset, TARGET_BATCH_SIZE);

        return HttpResponse.ok(messageRecords);
    }

    // ------------ RECORD GENERATOR ------------

    private MessageRecord generateDummyRecord(long offset) {
        Random random = new Random();

        // Generate a random index within the bounds of the list
        int randomIndex = random.nextInt(TOPICS.size());

        // Get the string at the random index
        String topic = TOPICS.get(randomIndex);

        // random small/medium payload < 300KB
        int payloadSize = 5_000 + RANDOM.nextInt(MAX_RECORD_SIZE - 5_000);

        String data = generateJsonPayload(topic, offset, payloadSize);

        MessageRecord r = new MessageRecord();
        r.setOffset(offset);
        r.setTopic(topic);
        r.setPartition(0);
        r.setMsgKey(topic + "-" + offset);
        r.setEventType(EventType.MESSAGE);
        r.setData(data);
        r.setCreatedAt(java.time.Instant.now());
        r.setCrc32(data.hashCode());   // simple stand-in

        return r;
    }

    // Simulate JSON payload of approx N bytes
    private String generateJsonPayload(String topic, long offset, int sizeBytes) {
        String header = "{\"topic\":\"" + topic + "\",\"offset\":" + offset + ",\"data\":\"";
        String footer = "\"}";
        int remaining = sizeBytes - header.length() - footer.length();

        if (remaining < 0) remaining = 0;

        // generate repeated chars
        String body = "X".repeat(remaining);

        return header + body + footer;
    }

    // Estimate size by serialized JSON length
    private int estimateRecordSize(MessageRecord record) {
        // Simple estimate: data size + overhead 200 bytes
        return record.getData().length() + 200;
    }
    /**
     * Health check endpoint
     * GET /health
     */
    @Get("/health")
    public HttpResponse<Map<String, Object>> health() {
        Map<String, Object> response = new HashMap<>();
        response.put("status", "healthy");
        response.put("role", "CLOUD");
        response.put("mode", messageService.getDataMode());
        response.put("totalMessages", messageService.getTotalMessages());

        return HttpResponse.ok(response);
    }

    /**
     * Status endpoint showing available messages
     * GET /status
     */
    @Get("/status")
    public HttpResponse<Map<String, Object>> status() {
        Map<String, Object> response = new HashMap<>();
        response.put("role", "CLOUD");
        response.put("mode", messageService.getDataMode());
        response.put("totalMessages", messageService.getTotalMessages());
        response.put("recentMessages", messageService.getRecentMessages(100));
        response.put("showing", "last 100 messages");

        return HttpResponse.ok(response);
    }

    /**
     * Registry topology endpoint
     * GET /registry/topology?nodeId=broker-001
     */
    @Get("/registry/topology")
    public HttpResponse<Map<String, Object>> getTopology(
            @QueryValue(defaultValue = "unknown") String nodeId) {

        LOG.info("[REGISTRY] Topology request from: {}", nodeId);

        // Default: treat as LOCAL broker polling Cloud directly
        Map<String, Object> response = new HashMap<>();
        response.put("nodeId", nodeId);
        response.put("role", "LOCAL");
        response.put("requestToFollow", List.of("http://cloud-server:8080"));
        response.put("cloudDataUrl", "http://cloud-server:8080");
        response.put("cloudDataUrlFallback", null);
        response.put("topologyVersion", "1.0");
        response.put("topics", List.of("price-topic"));

        LOG.info("[REGISTRY] Assigned role: {}, parent: {}",
                response.get("role"), response.get("requestToFollow"));

        return HttpResponse.ok(response);
    }
}
