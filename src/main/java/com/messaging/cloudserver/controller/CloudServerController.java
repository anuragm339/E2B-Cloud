package com.messaging.cloudserver.controller;

import com.messaging.cloudserver.model.Message;
import com.messaging.cloudserver.service.MessageService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.QueryValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for cloud server endpoints
 */
@Controller
public class CloudServerController {
    private static final Logger LOG = LoggerFactory.getLogger(CloudServerController.class);

    private final MessageService messageService;

    public CloudServerController(MessageService messageService) {
        this.messageService = messageService;
    }

    /**
     * Poll endpoint for child brokers to retrieve messages
     * GET /pipe/poll?offset=0&limit=100&topic=price-topic
     */
    @Get("/pipe/poll")
    public HttpResponse<?> pollMessages(
            @QueryValue(defaultValue = "0") int offset,
            @QueryValue(defaultValue = "100") int limit,
            @QueryValue(defaultValue = "price-topic") String topic) {

        LOG.info("[CLOUD] Poll request: offset={}, limit={}, topic={}", offset, limit, topic);

        int totalMessages = messageService.getTotalMessages();

        // No new messages
        if (offset >= totalMessages) {
            LOG.info("[CLOUD] No new messages (offset {} >= {} total messages)", offset, totalMessages);
            return HttpResponse.status(HttpStatus.NO_CONTENT);
        }

        // Get messages
        List<Message> result = messageService.getMessages(offset, limit);

        int endOffset = offset + result.size();
        LOG.info("[CLOUD] Serving {} messages (offset {} to {})", result.size(), offset, endOffset - 1);

        // Log first 5 messages
        int logCount = Math.min(5, result.size());
        for (int i = 0; i < logCount; i++) {
            LOG.info("  - {}", result.get(i).getMsgKey());
        }
        if (result.size() > 5) {
            LOG.info("  ... and {} more", result.size() - 5);
        }

        return HttpResponse.ok(result);
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
