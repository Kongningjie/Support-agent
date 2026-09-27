package com.lawrence.supportagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 验证已有 V8 数据升级 V9 后完整归入固定 GLOBAL 空间。 */
@Testcontainers
class KnowledgeSpaceMigrationIT {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("support_agent_migration")
            .withUsername("support_agent")
            .withPassword("test_password");

    /** 从 V8 构造既有文档、工单和案例，再验证 V9 回填及约束。 */
    @Test
    void shouldMigrateExistingResourcesFromV8ToGlobal() throws Exception {
        flyway("8").migrate();
        try (Connection connection = connection()) {
            long ticketId = insertTicket(connection);
            insertDocument(connection);
            insertCase(connection, ticketId);
        }

        flyway(null).migrate();

        try (Connection connection = connection()) {
            assertEquals("00000000000000000000000000000001",
                    scalar(connection, "SELECT HEX(space_id) FROM knowledge_space WHERE code='GLOBAL'"));
            assertEquals("00000000000000000000000000000001",
                    scalar(connection, "SELECT HEX(space_id) FROM managed_document LIMIT 1"));
            assertEquals("00000000000000000000000000000001",
                    scalar(connection, "SELECT HEX(space_id) FROM ticket LIMIT 1"));
            assertEquals("00000000000000000000000000000001",
                    scalar(connection, "SELECT HEX(space_id) FROM resolved_case LIMIT 1"));
        }
    }

    /** 创建指定目标版本的 Flyway。 */
    private Flyway flyway(String target) {
        org.flywaydb.core.api.configuration.FluentConfiguration configuration = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    /** 打开测试数据库连接。 */
    private Connection connection() throws Exception {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    /** 插入一条 V8 工单并返回内部主键。 */
    private long insertTicket(Connection connection) throws Exception {
        String sql = "INSERT INTO ticket (title,problem_description,status,version,created_by,created_at,updated_by,updated_at)"
                + " VALUES ('历史工单','历史问题','OPEN',0,'migration',UTC_TIMESTAMP(6),'migration',UTC_TIMESTAMP(6))";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                java.sql.Statement.RETURN_GENERATED_KEYS)) {
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                keys.next();
                return keys.getLong(1);
            }
        }
    }

    /** 插入一条 V8 托管文档。 */
    private void insertDocument(Connection connection) throws Exception {
        String sql = "INSERT INTO managed_document (title,input_type,media_type,raw_content,content_hash,status,version,deleted,created_by,created_at,updated_by,updated_at)"
                + " VALUES ('历史文档','DIRECT_TEXT','text/plain','历史正文',REPEAT('a',64),'DRAFT',0,FALSE,'migration',UTC_TIMESTAMP(6),'migration',UTC_TIMESTAMP(6))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.executeUpdate();
        }
    }

    /** 插入一条引用既有工单的 V8 案例。 */
    private void insertCase(Connection connection, long ticketId) throws Exception {
        String sql = "INSERT INTO resolved_case (source_ticket_id,title,problem,cause,solution,status,content_hash,version,deleted,created_by,created_at,updated_by,updated_at)"
                + " VALUES (?,'历史案例','问题','根因','方案','DRAFT',REPEAT('b',64),0,FALSE,'migration',UTC_TIMESTAMP(6),'migration',UTC_TIMESTAMP(6))";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, ticketId);
            statement.executeUpdate();
        }
    }

    /** 查询单个字符串值。 */
    private String scalar(Connection connection, String sql) throws Exception {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet result = statement.executeQuery()) {
            result.next();
            return result.getString(1);
        }
    }
}
