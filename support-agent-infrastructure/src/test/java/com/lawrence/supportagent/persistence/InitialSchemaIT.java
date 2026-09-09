package com.lawrence.supportagent.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** 使用真实 MySQL 8.4 验证初始迁移、七张表和中文字段注释。 */
@Testcontainers
class InitialSchemaIT {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.11")
            .withDatabaseName("support_agent").withUsername("support_agent").withPassword("test_password");

    /** 从空数据库执行迁移并检查业务表、字段、索引、约束及中文注释。 */
    @Test
    void shouldCreateAllTablesWithColumnComments() throws Exception {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        try (Connection connection = MYSQL.createConnection(""); Statement statement = connection.createStatement()) {
            assertEquals(7, count(statement, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='support_agent' AND table_name IN ('ticket','managed_document','resolved_case','async_task','idempotency_record','agent_run','retrieval_trace')"));
            assertEquals(7, count(statement, "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='support_agent' AND table_name IN ('ticket','managed_document','resolved_case','async_task','idempotency_record','agent_run','retrieval_trace') AND table_comment <> ''"));
            long columns = count(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='support_agent' AND table_name IN ('ticket','managed_document','resolved_case','async_task','idempotency_record','agent_run','retrieval_trace')");
            long commented = count(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='support_agent' AND table_name IN ('ticket','managed_document','resolved_case','async_task','idempotency_record','agent_run','retrieval_trace') AND column_comment <> ''");
            assertEquals(132, columns);
            assertEquals(columns, commented);
            assertEquals(27, count(statement, "SELECT COUNT(DISTINCT table_name, index_name) FROM information_schema.statistics WHERE table_schema='support_agent' AND table_name IN ('ticket','managed_document','resolved_case','async_task','idempotency_record','agent_run','retrieval_trace')"));
            assertEquals(16, count(statement, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='support_agent' AND table_name IN ('ticket','managed_document','resolved_case','async_task','idempotency_record','agent_run','retrieval_trace')"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='support_agent' AND table_name='resolved_case' AND constraint_name='uk_resolved_case_source_ticket' AND constraint_type='UNIQUE'"));
            assertEquals(1, count(statement, "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='support_agent' AND table_name='managed_document' AND column_name='active_content_hash' AND extra LIKE '%STORED GENERATED%'"));
            assertTrue(count(statement, "SELECT COUNT(*) FROM information_schema.table_constraints WHERE table_schema='support_agent' AND constraint_type='FOREIGN KEY'") >= 2);
        }
    }

    /** 执行只返回单个计数值的验证 SQL。 */
    private long count(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            resultSet.next();
            return resultSet.getLong(1);
        }
    }
}
