package com.messaging.cloudserver.repository;

import com.messaging.cloudserver.model.Event;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository for reading events from SQLite database
 */
@Singleton
public class EventRepository {
    private static final Logger LOG = LoggerFactory.getLogger(EventRepository.class);

    private final String dbPath;

    public EventRepository(@Property(name = "sqlite.db.path", defaultValue = "/data/events.db") String dbPath) {
        this.dbPath = dbPath;
        LOG.info("EventRepository initialized with database path: {}", dbPath);
    }

    /**
     * Fetch events from the database starting from the given offset
     *
     * @param lastOffset The last processed offset
     * @param limit Maximum number of events to fetch
     * @return List of events
     */
    public List<Event> fetchEvents(long lastOffset, int limit) {
        List<Event> events = new ArrayList<>();
        String url = "jdbc:sqlite:" + dbPath;

        String query = "SELECT msg_offset, data, created_at, type FROM event WHERE msg_offset > ? ORDER BY msg_offset ASC LIMIT ?";

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setLong(1, lastOffset);
            stmt.setInt(2, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Event event = new Event(
                            rs.getLong("msg_offset"),
                            rs.getString("data"),
                            rs.getString("created_at"),
                            rs.getString("type")
                    );
                    events.add(event);
                }
            }

            if (!events.isEmpty()) {
                LOG.info("[SQLITE] Found {} new events to load (offset {} to {})",
                        events.size(), events.get(0).getMsgOffset(),
                        events.get(events.size() - 1).getMsgOffset());
            } else {
                LOG.debug("[SQLITE] No new events found (last offset: {})", lastOffset);
            }

        } catch (SQLException e) {
            LOG.error("[SQLITE] Database error: {}", e.getMessage(), e);
            LOG.error("[SQLITE] Database path: {}", dbPath);
        }

        return events;
    }

    /**
     * Get the total count of events in the database
     */
    public long getEventCount() {
        String url = "jdbc:sqlite:" + dbPath;
        String query = "SELECT COUNT(*) as count FROM event";

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            if (rs.next()) {
                return rs.getLong("count");
            }
        } catch (SQLException e) {
            LOG.error("[SQLITE] Error counting events: {}", e.getMessage());
        }

        return 0;
    }
}
