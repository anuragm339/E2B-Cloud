package com.messaging.cloudserver.repository;

import com.messaging.cloudserver.model.Event;
import com.messaging.cloudserver.model.EventType;
import com.messaging.cloudserver.model.Message;
import com.messaging.cloudserver.model.MessageRecord;
import io.micronaut.context.annotation.Property;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Repository for reading events from SQLite database
 */
@Singleton
public class EventRepository {
    private static final Logger LOG = LoggerFactory.getLogger(EventRepository.class);

    private final String dbPath;
    private  Connection connection;

    public EventRepository(@Property(name = "sqlite.db.path", defaultValue = "/data/events.db") String dbPath) throws SQLException {
        this.dbPath = dbPath;
        String url = "jdbc:sqlite:" + dbPath;
        connection=DriverManager.getConnection(url);
        LOG.info("EventRepository initialized with database path: {}", dbPath);
    }

    /**
     * Fetch events from the database starting from the given offset
     *
     * @param lastOffset The last processed offset
     * @return List of events
     */
    public List<MessageRecord> fetchEvents(long lastOffset, long maxBytes) {
        List<MessageRecord> events = new ArrayList<>();
        String url = "jdbc:sqlite:" + dbPath;

        String query = """
                        
                        SELECT msg_offset, data, created_at, type, event_size from event where msg_offset > ? AND event_size <= 19990
                       ORDER BY msg_offset ASC limit 300;
                       
                """;

        try (Connection conn = DriverManager.getConnection(url);
             PreparedStatement stmt = conn.prepareStatement(query)) {

            stmt.setLong(1, lastOffset);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {

                    long msgOffset = rs.getLong("msg_offset");
                    String type = rs.getString("type");
                    String data = rs.getString("data");
                    String createdAtStr = rs.getString("created_at");

                    // If created_at is stored as ISO-8601 string:
                    Instant createdAt = Instant.parse(createdAtStr);

                    MessageRecord event = new MessageRecord(
                            msgOffset,
                            type,
                            0,
                            UUID.randomUUID().toString(),
                            EventType.MESSAGE,
                            data,
                            createdAt
                    );

                    events.add(event);

                }
            }

            if (!events.isEmpty()) {
            } else {
                LOG.debug("[SQLITE] No new events found within byte budget (last offset: {})", lastOffset);
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
    public List<MessageRecord> getMessages(List<String> types, long offset, boolean includeHash) throws SQLException {
        List<MessageRecord> retrievedMessages = new ArrayList<>();
        int typesCount = types == null ? 0 : types.size();
        try (PreparedStatement statement = connection.prepareStatement(getReadEvent(typesCount, 4000000))) {
            int parameterIndex = 1;
            statement.setLong(parameterIndex, offset);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    retrievedMessages.add(mapRetrievedMessageFromResultSet(resultSet, includeHash));
                }
            }
        }
        return retrievedMessages;
    }

    static String getReadEvent(final int typesCount, final long maxBatchSize) {
        final StringBuilder queryBuilder = new StringBuilder()
                .append("SELECT type,  msg_offset, created_at, data, event_size\n" +
                        "FROM (\n" +
                        "    SELECT\n" +
                        "        type,\n" +
                        "        msg_offset,\n" +
                        "        created_at,\n" +
                        "        data,\n" +
                        "        event_size,\n" +
                        "        SUM(event_size) OVER (\n" +
                        "            ORDER BY msg_offset ASC\n" +
                        "            ROWS BETWEEN UNBOUNDED PRECEDING AND CURRENT ROW\n" +
                        "        ) AS running_size\n" +
                        "    FROM event\n" +
                        "    WHERE msg_offset > ? and event_size <= 19990\n" +
                        "" +
                        ") t\n" +
                        "WHERE running_size <1048576 \n" );
        return queryBuilder.toString();
    }

    static void appendFilterByTypes(final StringBuilder queryBuilder, int typesCount) {
        if (typesCount != 0) {
            queryBuilder.append(" AND type IN (").append(generateQuestionMarks(typesCount)).append(")");
        }
    }

    private static String generateQuestionMarks(final int inCount) {
        return Stream.generate(() -> "?").limit(inCount).collect(Collectors.joining(","));
    }// private long offset; // Internal, auto-assigned by broker// private String topic;// private int partition;// private String msgKey; // Business key for compaction// private EventType eventType; // MESSAGE or DELETE// private String data; // JSON payload (null for DELETE events)// private Instant createdAt; private Message mapRetrievedMessageFromResultSet(final ResultSet resultSet, boolean includeHash) throws SQLException { Message retrievedMessage; final ZonedDateTime time = ZonedDateTime.of(resultSet.getTimestamp("created_utc").toLocalDateTime(), ZoneId.of("UTC")); Long hash = resultSet.getLong("hash_column"); if(!includeHash || resultSet.wasNull()) { hash = null; } retrievedMessage = new Message( resultSet.getLong("msg_offset"), resultSet.getString("type"), 0, resultSet.getString("msg_key"), EventType.MESSAGE, resultSet.getString("data"), resultSet.getTimestamp("created_utc").toInstant() ); return retrievedMessage; }

    // Simplified hash handling and ZonedDateTime usage
    private MessageRecord mapRetrievedMessageFromResultSet(final ResultSet resultSet, boolean includeHash) throws SQLException {
        // Use getObject and cast for robust null handling of long types
        // The existing Message constructor uses Instant internally, so getting the instant directly is cleaner
        final Instant createdAtInstant =Instant.now();

        // Assuming topic and partition are defined elsewhere or acceptable as defaults (0) based on context
        MessageRecord event = new MessageRecord(
                resultSet.getLong("msg_offset"),
                resultSet.getString("type"),
                0,
                UUID.randomUUID().toString(),
                EventType.MESSAGE,
                resultSet.getString("data"),
                createdAtInstant
        );
        return event;
    }

}
