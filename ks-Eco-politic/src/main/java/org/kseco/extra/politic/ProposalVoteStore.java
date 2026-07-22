package org.kseco.extra.politic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ProposalVoteStore {

    private ProposalVoteStore() {}

    static List<Map<String, Object>> readStageVotes(
            Connection conn, String proposalId, String stage) throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT v.voter_uuid, v.voter_name, v.voter_office, v.vote, v.cast_at
                FROM ks_politic_votes v
                JOIN ks_politic_electorate_members e
                  ON e.proposal_id=v.proposal_id
                 AND e.vote_stage=v.vote_stage
                 AND e.voter_uuid=v.voter_uuid
                WHERE v.proposal_id=? AND v.vote_stage=?
                ORDER BY v.cast_at
                """)) {
            ps.setString(1, proposalId);
            ps.setString(2, stage);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> vote = new LinkedHashMap<>();
                    vote.put("voterUuid", rs.getString("voter_uuid"));
                    vote.put("voterName", rs.getString("voter_name"));
                    vote.put("voterOffice", rs.getString("voter_office"));
                    vote.put("vote", rs.getString("vote"));
                    vote.put("castAt", rs.getLong("cast_at"));
                    out.add(vote);
                }
            }
        }
        return out;
    }

    static List<Map<String, Object>> readPlayerVotes(Connection conn, UUID playerUuid)
            throws SQLException {
        List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement("""
                SELECT v.proposal_id, v.vote, v.vote_stage, v.cast_at
                FROM ks_politic_votes v
                JOIN ks_politic_electorate_members e
                  ON e.proposal_id=v.proposal_id
                 AND e.vote_stage=v.vote_stage
                 AND e.voter_uuid=v.voter_uuid
                WHERE v.voter_uuid=?
                ORDER BY v.cast_at DESC
                LIMIT 50
                """)) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> vote = new LinkedHashMap<>();
                    vote.put("proposalId", rs.getString("proposal_id"));
                    vote.put("vote", rs.getString("vote"));
                    vote.put("voteStage", rs.getString("vote_stage"));
                    vote.put("castAt", rs.getLong("cast_at"));
                    out.add(vote);
                }
            }
        }
        return out;
    }
}
