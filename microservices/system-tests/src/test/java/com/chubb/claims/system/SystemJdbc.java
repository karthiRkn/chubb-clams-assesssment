package com.chubb.claims.system;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The three service databases behind the {@code jdbc} calls the original tests make. A statement goes to the database that
 * owns the table it names, which is also a check that every table lives in exactly one service.
 */
final class SystemJdbc {

    private static final Pattern ASSESSMENT_TABLES = Pattern.compile("\\b(claim_assessments|information_requests)\\b");
    private static final Pattern USERS = Pattern.compile("\\busers\\b");

    private final JdbcTemplate claim;
    private final JdbcTemplate assessment;
    private final JdbcTemplate user;

    SystemJdbc(JdbcTemplate claim, JdbcTemplate assessment, JdbcTemplate user) {
        this.claim = claim;
        this.assessment = assessment;
        this.user = user;
    }

    private JdbcTemplate owner(String sql) {
        if (ASSESSMENT_TABLES.matcher(sql).find()) {
            return assessment;
        }
        if (USERS.matcher(sql).find()) {
            return user;
        }
        return claim; // claims, claim_status_history, claim_assignment_history, claim_update_history
    }

    int update(String sql, Object... args) {
        return owner(sql).update(sql, args);
    }

    <T> T queryForObject(String sql, Class<T> type, Object... args) {
        return owner(sql).queryForObject(sql, type, args);
    }

    Map<String, Object> queryForMap(String sql, Object... args) {
        return owner(sql).queryForMap(sql, args);
    }

    <T> List<T> queryForList(String sql, Class<T> type, Object... args) {
        return owner(sql).queryForList(sql, type, args);
    }
}
