package org.kseco.extra.politic;

import org.kseco.database.BusinessSchemaDialect;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

final class PoliticSchema {
    private PoliticSchema() {}

    static int initialize(Connection connection) throws SQLException {
        try (Statement s = connection.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS ks_politic_offices (id VARCHAR(64) PRIMARY KEY, player_uuid VARCHAR(64) NOT NULL, player_name VARCHAR(64) NOT NULL DEFAULT '', office_type VARCHAR(32) NOT NULL, enterprise_id VARCHAR(64) DEFAULT '', elected_at BIGINT NOT NULL, term_ends_at BIGINT DEFAULT 0, elected_by VARCHAR(64) DEFAULT '', is_active INTEGER DEFAULT 1, seat_index INTEGER DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS ks_politic_proposals (id VARCHAR(64) PRIMARY KEY, title VARCHAR(256) NOT NULL, description TEXT DEFAULT '', proposal_type VARCHAR(64) NOT NULL, target_endpoint VARCHAR(512) DEFAULT '', payload_json TEXT DEFAULT '', proposer_uuid VARCHAR(64) NOT NULL, proposer_name VARCHAR(64) NOT NULL DEFAULT '', proposer_office VARCHAR(32) NOT NULL, status VARCHAR(32) NOT NULL DEFAULT 'PROPOSED', result_summary TEXT DEFAULT '', created_at BIGINT NOT NULL, enacted_at BIGINT DEFAULT 0)");
            s.execute("CREATE TABLE IF NOT EXISTS ks_politic_votes (id VARCHAR(64) PRIMARY KEY, proposal_id VARCHAR(64) NOT NULL, voter_uuid VARCHAR(64) NOT NULL, voter_name VARCHAR(64) NOT NULL DEFAULT '', voter_office VARCHAR(32) NOT NULL, vote VARCHAR(16) NOT NULL, vote_stage VARCHAR(32) NOT NULL, cast_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS ks_politic_appeals (id VARCHAR(64) PRIMARY KEY, proposal_id VARCHAR(64) NOT NULL, actor_uuid VARCHAR(64) NOT NULL, actor_name VARCHAR(64) NOT NULL DEFAULT '', proposal_stage VARCHAR(32) NOT NULL, appealed_at BIGINT NOT NULL)");
            s.execute("CREATE TABLE IF NOT EXISTS ks_politic_election_votes (id VARCHAR(128) PRIMARY KEY, election_id VARCHAR(64) NOT NULL, voter_uuid VARCHAR(64) NOT NULL, candidate_uuid VARCHAR(64) NOT NULL, candidate_name VARCHAR(64) NOT NULL DEFAULT '', cast_at BIGINT NOT NULL, UNIQUE(election_id, voter_uuid))");
            s.execute("CREATE TABLE IF NOT EXISTS ks_politic_config (config_key VARCHAR(128) PRIMARY KEY, config_value TEXT NOT NULL)");
        }
        BusinessSchemaDialect.renameColumnIfPresent(connection, "ks_politic_config", "key", "config_key");
        BusinessSchemaDialect.renameColumnIfPresent(connection, "ks_politic_config", "value", "config_value");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_politic_offices", "seat_index", "INTEGER DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_politic_proposals", "stage_started_at", "BIGINT DEFAULT 0");
        BusinessSchemaDialect.addColumnIfMissing(connection, "ks_politic_proposals", "stage_deadline_at", "BIGINT DEFAULT 0");
        int removed = removeDuplicateVotes(connection);
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_pv_proposal", "ks_politic_votes", "proposal_id", "vote_stage");
        BusinessSchemaDialect.createUniqueIndexIfMissing(connection, "idx_pv_unique_voter_stage", "ks_politic_votes", "proposal_id", "voter_uuid", "vote_stage");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_pa_proposal_time", "ks_politic_appeals", "proposal_id", "appealed_at");
        BusinessSchemaDialect.createIndexIfMissing(connection, "idx_pa_actor_time", "ks_politic_appeals", "actor_uuid", "appealed_at");
        return removed;
    }

    private static int removeDuplicateVotes(Connection connection) throws SQLException {
        Set<String> seen = new HashSet<>();
        int removed = 0;
        try (PreparedStatement read = connection.prepareStatement("SELECT id,proposal_id,voter_uuid,vote_stage FROM ks_politic_votes ORDER BY cast_at DESC,id DESC");
             ResultSet rows = read.executeQuery();
             PreparedStatement delete = connection.prepareStatement("DELETE FROM ks_politic_votes WHERE id=?")) {
            while (rows.next()) {
                String key = rows.getString(2) + '\u0000' + rows.getString(3) + '\u0000' + rows.getString(4);
                if (seen.add(key)) continue;
                delete.setString(1, rows.getString(1));
                removed += delete.executeUpdate();
            }
        }
        return removed;
    }
}
