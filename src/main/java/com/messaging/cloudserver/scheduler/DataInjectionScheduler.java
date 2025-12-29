package com.messaging.cloudserver.scheduler;

import io.micronaut.context.annotation.Property;
import io.micronaut.scheduling.annotation.Scheduled;
import jakarta.annotation.PostConstruct;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Instant;
import java.util.List;
import java.util.Random;
import java.util.UUID;


public class DataInjectionScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(DataInjectionScheduler.class);

    private final String dbPath;

    public DataInjectionScheduler(
            @Property(name = "sqlite.db.path",
                    defaultValue = "/Users/anuragmishra/Desktop/workspace/messaging/data/events.db")
            String dbPath
    ) {
        this.dbPath = dbPath;
    }

    /* --------------------------------------------------------
     * CONFIG
     * -------------------------------------------------------- */

    private static final List<String> TOPICS = List.of(
            "prices-v1", "reference-data-v5", "non-promotable-products", "prices-v4",
            "minimum-price", "deposit", "product-base-document", "search-product",
            "location", "location-clusters", "selling-restrictions",
            "colleague-facts-jobs", "colleague-facts-legacy",
            "loss-prevention-configuration", "loss-prevention-store-configuration",
            "loss-prevention-product", "loss-prevention-rule-config",
            "stored-value-services-banned-promotion",
            "stored-value-services-active-promotion",
            "colleague-card-pin", "colleague-card-pin-v2",
            "dcxp-content", "restriction-rules", "dcxp-ugc"
    );

    private static final long START_OFFSET = 1_000_000L;

    private static final int TARGET_RECORD_COUNT = 250_000;

    // 0.1% + 0.1%
    private static final int BIG_TARGET_COUNT = (int) (TARGET_RECORD_COUNT * 0.001);
    private static final int MED_TARGET_COUNT = (int) (TARGET_RECORD_COUNT * 0.001);

    private static final int MAX_RECORD_BYTES = 1_000_000;

    private static final int BIG_TARGET_BYTES = 980_000;     // ~1MB
    private static final int MED_TARGET_BYTES = 512_000;     // ~512KB
    private static final int SMALL_TARGET_BYTES = 20000;    // tune for total size

    private static final int BATCH_SIZE = 500;

    private final Random random = new Random();

    /* --------------------------------------------------------
     * INIT
     * -------------------------------------------------------- */


    void init() {
        LOG.info("Initializing DataInjectionScheduler → {}", dbPath);
        createSchema();
    }

    private void createSchema() {
        String url = "jdbc:sqlite:" + dbPath;

        String ddl = """
                CREATE TABLE IF NOT EXISTS event (
                    msg_offset INTEGER PRIMARY KEY NOT NULL,
                    msg_key    TEXT NOT NULL,
                    type       TEXT NOT NULL,
                    data       TEXT NOT NULL,
                    event_size INTEGER NOT NULL,
                    created_at TEXT NOT NULL
                );
                """;

        String idx1 = "CREATE INDEX IF NOT EXISTS idx_event_offset ON event(msg_offset);";
        String idx2 = "CREATE INDEX IF NOT EXISTS idx_event_type_offset ON event(type, msg_offset);";

        try (Connection c = DriverManager.getConnection(url);
             Statement s = c.createStatement()) {

            s.execute(ddl);
            s.execute(idx1);
            s.execute(idx2);

            LOG.info("SQLite schema ready");

        } catch (SQLException e) {
            throw new RuntimeException("Schema init failed", e);
        }
    }

    /* --------------------------------------------------------
     * SCHEDULER
     * -------------------------------------------------------- */


    void generate() {

        String url = "jdbc:sqlite:" + dbPath;

        try (Connection conn = DriverManager.getConnection(url)) {

            conn.setAutoCommit(false);

            long totalCount = queryLong(conn, "SELECT COUNT(*) FROM event");
            if (totalCount >= TARGET_RECORD_COUNT) {
                LOG.info("Target reached → totalCount={}", totalCount);
                return;
            }

            long bigCount = queryLong(conn,
                    "SELECT COUNT(*) FROM event WHERE event_size >= 900000");

            long medCount = queryLong(conn,
                    "SELECT COUNT(*) FROM event WHERE event_size >= 450000 AND event_size < 900000");

            long nextOffset = queryNextOffset(conn);

            String sql = """
                    INSERT INTO event
                    (msg_offset, msg_key, type, data, event_size, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """;

            try (PreparedStatement ps = conn.prepareStatement(sql)) {

                for (int i = 0; i < BATCH_SIZE && totalCount < TARGET_RECORD_COUNT; i++) {

                    SizeTier tier = chooseTier(bigCount, medCount);

                    DummyRecord record = createRecord(randomTopic(), tier);

                    ps.setLong(1, nextOffset++);
                    ps.setString(2, UUID.randomUUID().toString());
                    ps.setString(3, record.topic);
                    ps.setString(4, record.json);
                    ps.setInt(5, record.sizeBytes);
                    ps.setString(6, Instant.now().toString());
                    ps.addBatch();

                    totalCount++;

                    if (tier == SizeTier.BIG) bigCount++;
                    if (tier == SizeTier.MEDIUM) medCount++;
                }

                ps.executeBatch();
                conn.commit();

                LOG.info("Inserted batch → total={}, big={}, med={}",
                        totalCount, bigCount, medCount);

            } catch (Exception e) {
                conn.rollback();
                throw e;
            }

        } catch (Exception e) {
            LOG.error("Scheduler failure", e);
        }
    }

    /* --------------------------------------------------------
     * TIER LOGIC
     * -------------------------------------------------------- */

    private SizeTier chooseTier(long bigCount, long medCount) {

        if (bigCount >= BIG_TARGET_COUNT && medCount >= MED_TARGET_COUNT) {
            return SizeTier.SMALL;
        }

        double r = random.nextDouble();

        if (bigCount < BIG_TARGET_COUNT && r < 0.001) {
            return SizeTier.BIG;
        }

        if (medCount < MED_TARGET_COUNT && r < 0.002) {
            return SizeTier.MEDIUM;
        }

        return SizeTier.SMALL;
    }

    /* --------------------------------------------------------
     * RECORD GENERATION
     * -------------------------------------------------------- */

    private enum SizeTier { BIG, MEDIUM, SMALL }

    private static class DummyRecord {
        final String topic;
        final String json;
        final int sizeBytes;

        DummyRecord(String topic, String json, int sizeBytes) {
            this.topic = topic;
            this.json = json;
            this.sizeBytes = sizeBytes;
        }
    }

    private DummyRecord createRecord(String topic, SizeTier tier) {

        int targetBytes = switch (tier) {
            case BIG -> BIG_TARGET_BYTES;
            case MEDIUM -> MED_TARGET_BYTES;
            default -> SMALL_TARGET_BYTES;
        };

        long now = System.currentTimeMillis();

        String base = """
                {"topic":"%s","ts":%d,"tier":"%s","payload":""}
                """.formatted(topic, now, tier);

        int baseSize = base.getBytes(StandardCharsets.UTF_8).length;
        int payloadSize = Math.max(0, targetBytes - baseSize - 10);

        String payload = randomAscii(payloadSize);

        String json = """
                {"topic":"%s","ts":%d,"tier":"%s","payload":"%s"}
                """.formatted(topic, now, tier, payload);

        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);

        if (bytes.length > MAX_RECORD_BYTES) {
            payload = payload.substring(0, payload.length() - 1024);
            json = """
                    {"topic":"%s","ts":%d,"tier":"%s","payload":"%s"}
                    """.formatted(topic, now, tier, payload);
            bytes = json.getBytes(StandardCharsets.UTF_8);
        }

        return new DummyRecord(topic, json, bytes.length);
    }

    private String randomAscii(int len) {
        if (len <= 0) return "";
        String chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString();
    }

    private String randomTopic() {
        return TOPICS.get(random.nextInt(TOPICS.size()));
    }

    /* --------------------------------------------------------
     * DB HELPERS
     * -------------------------------------------------------- */

    private long queryLong(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    private long queryNextOffset(Connection c) throws SQLException {
        String sql = "SELECT COALESCE(MAX(msg_offset) + 1, ?) FROM event";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setLong(1, START_OFFSET);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : START_OFFSET;
            }
        }
    }
}
