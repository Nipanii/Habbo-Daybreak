package com.eu.habbo.habbohotel.games.battleball;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.plugin.events.users.UserPointsEvent;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** All BattleBall persistence, including atomic per-round ticket charging. */
final class BattleBallRepository {
    private static final Logger LOGGER = LoggerFactory.getLogger(BattleBallRepository.class);
    record LoadedData(Map<Integer, BattleBallMap> maps, Map<Integer, Map<Integer, List<BattleBallPosition>>> spawns) {}
    record ChargeResult(boolean success, Map<Integer, Integer> balances, Map<Integer, Integer> costs) {
        static ChargeResult failed() { return new ChargeResult(false, Map.of(), Map.of()); }
    }

    LoadedData load() throws SQLException {
        Map<Integer, BattleBallMap> maps = new LinkedHashMap<>();
        Map<Integer, Map<Integer, List<BattleBallPosition>>> spawns = new HashMap<>();
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT map_id AS id, tile_map, heightmap FROM battleball_maps WHERE enabled = 1 ORDER BY map_id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) maps.put(rows.getInt("id"),
                        new BattleBallMap(rows.getInt("id"), rows.getString("tile_map"), rows.getString("heightmap")));
            }
            try (PreparedStatement statement = connection.prepareStatement("SELECT map_id, team_id, x, y, z, rotation FROM battleball_spawns ORDER BY map_id, team_id, id");
                 ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    spawns.computeIfAbsent(rows.getInt("map_id"), ignored -> new HashMap<>())
                            .computeIfAbsent(rows.getInt("team_id"), ignored -> new ArrayList<>())
                            .add(new BattleBallPosition(rows.getInt("x"), rows.getInt("y"), rows.getDouble("z"), rows.getInt("rotation")));
                }
            }
        }
        if (maps.size() != 5) throw new SQLException("Expected all five original BattleBall maps, loaded " + maps.size());
        return new LoadedData(Map.copyOf(maps), immutableSpawns(spawns));
    }

    ChargeResult chargeRound(BattleBallGame game, List<Habbo> players, int currencyType, int configuredCost) {
        if (configuredCost < 0 || currencyType < 0) return ChargeResult.failed();
        Map<Integer, Integer> effectiveCosts = new LinkedHashMap<>();
        for (Habbo habbo : players) {
            UserPointsEvent event = new UserPointsEvent(habbo, -configuredCost, currencyType);
            if (configuredCost > 0 && Emulator.getPluginManager().fireEvent(event).isCancelled()) return ChargeResult.failed();
            if (event.type < 0) return ChargeResult.failed();
            effectiveCosts.put(habbo.getHabboInfo().getId(), Math.abs(event.points));
            if (event.type != currencyType) return ChargeResult.failed();
        }

        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                int round = game.roundNumber() + 1;
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO battleball_rounds (instance_id, round_number, map_id, started_at) VALUES (?, ?, ?, ?)")) {
                    insert.setInt(1, game.id()); insert.setInt(2, round); insert.setInt(3, game.map().id());
                    insert.setInt(4, Emulator.getIntUnixTimestamp()); insert.executeUpdate();
                }
                Map<Integer, Integer> balances = new LinkedHashMap<>();
                for (Habbo habbo : players) {
                    int userId = habbo.getHabboInfo().getId();
                    int cost = effectiveCosts.get(userId);
                    if (cost > 0) {
                        try (PreparedStatement debit = connection.prepareStatement(
                                "UPDATE users_currency SET amount = amount - ? WHERE user_id = ? AND type = ? AND amount >= ? LIMIT 1")) {
                            debit.setInt(1, cost); debit.setInt(2, userId); debit.setInt(3, currencyType); debit.setInt(4, cost);
                            if (debit.executeUpdate() != 1) { connection.rollback(); return ChargeResult.failed(); }
                        }
                    }
                    try (PreparedStatement participant = connection.prepareStatement(
                            "INSERT INTO battleball_round_players (instance_id, round_number, user_id, team_id, ticket_cost) VALUES (?, ?, ?, ?, ?)")) {
                        participant.setInt(1, game.id()); participant.setInt(2, round); participant.setInt(3, userId);
                        BattleBallPlayer player = game.players().stream().filter(value -> value.userId() == userId).findFirst().orElseThrow();
                        participant.setInt(4, player.teamId()); participant.setInt(5, cost); participant.executeUpdate();
                    }
                    balances.put(userId, readCurrency(connection, userId, currencyType));
                }
                connection.commit();
                return new ChargeResult(true, Map.copyOf(balances), Map.copyOf(effectiveCosts));
            } catch (Exception exception) {
                connection.rollback();
                LOGGER.error("BattleBall -> Failed to charge instance {} round {}.", game.id(), game.roundNumber() + 1, exception);
                return ChargeResult.failed();
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            LOGGER.error("BattleBall -> Could not open the ticket transaction for instance {}.", game.id(), exception);
            return ChargeResult.failed();
        }
    }

    void saveResults(BattleBallGame game) {
        Map<Integer, Integer> scores = game.scores();
        Map<Integer, Integer> playerScores = game.playerScores();
        int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        long winnerCount = scores.values().stream().filter(score -> score == best).count();
        int winner = winnerCount == 1 ? scores.entrySet().stream().filter(entry -> entry.getValue() == best).findFirst().orElseThrow().getKey() : -1;
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection()) {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE battleball_rounds SET ended_at = ?, winning_team = ? WHERE instance_id = ? AND round_number = ? AND ended_at IS NULL")) {
                    update.setInt(1, Emulator.getIntUnixTimestamp()); update.setInt(2, winner);
                    update.setInt(3, game.id()); update.setInt(4, game.roundNumber());
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return;
                    }
                }
                for (BattleBallPlayer player : game.players()) {
                    int playerScore = playerScores.getOrDefault(player.userId(), 0);
                    try (PreparedStatement updatePlayer = connection.prepareStatement(
                            "UPDATE battleball_round_players SET score = ?, winner = ? WHERE instance_id = ? AND round_number = ? AND user_id = ?")) {
                        updatePlayer.setInt(1, playerScore); updatePlayer.setBoolean(2, player.teamId() == winner);
                        updatePlayer.setInt(3, game.id()); updatePlayer.setInt(4, game.roundNumber()); updatePlayer.setInt(5, player.userId()); updatePlayer.executeUpdate();
                    }
                    try (PreparedStatement stats = connection.prepareStatement(
                            "INSERT INTO battleball_player_stats (user_id, games_played, wins, total_score, skill_level) VALUES (?, 1, ?, ?, ?) "
                                    + "ON DUPLICATE KEY UPDATE games_played = games_played + 1, wins = wins + VALUES(wins), total_score = total_score + VALUES(total_score), "
                                    + "skill_level = GREATEST(1, LEAST(10, FLOOR(total_score / 5000) + 1))")) {
                        stats.setInt(1, player.userId()); stats.setInt(2, player.teamId() == winner ? 1 : 0); stats.setInt(3, playerScore);
                        stats.setInt(4, Math.max(1, Math.min(10, playerScore / 5000 + 1))); stats.executeUpdate();
                    }
                }
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                LOGGER.error("BattleBall -> Failed to persist instance {} round {} results.", game.id(), game.roundNumber(), exception);
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        } catch (SQLException exception) {
            LOGGER.error("BattleBall -> Could not open the result transaction for instance {}.", game.id(), exception);
        }
    }

    int skillLevel(int userId) {
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             PreparedStatement statement = connection.prepareStatement("SELECT skill_level FROM battleball_player_stats WHERE user_id = ?")) {
            statement.setInt(1, userId);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 1; }
        } catch (SQLException exception) {
            return 1;
        }
    }

    int nextInstanceId() {
        try (Connection connection = Emulator.getDatabase().getDataSource().getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COALESCE(MAX(instance_id), 0) + 1 FROM battleball_rounds")) {
            return rows.next() ? Math.max(1, rows.getInt(1)) : 1;
        } catch (SQLException exception) {
            return 1;
        }
    }

    private static int readCurrency(Connection connection, int userId, int type) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT amount FROM users_currency WHERE user_id = ? AND type = ? LIMIT 1")) {
            statement.setInt(1, userId); statement.setInt(2, type);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? rows.getInt(1) : 0; }
        }
    }

    private static Map<Integer, Map<Integer, List<BattleBallPosition>>> immutableSpawns(
            Map<Integer, Map<Integer, List<BattleBallPosition>>> input) {
        Map<Integer, Map<Integer, List<BattleBallPosition>>> result = new HashMap<>();
        input.forEach((mapId, teams) -> {
            Map<Integer, List<BattleBallPosition>> teamCopy = new HashMap<>();
            teams.forEach((teamId, positions) -> teamCopy.put(teamId, List.copyOf(positions)));
            result.put(mapId, Map.copyOf(teamCopy));
        });
        return Map.copyOf(result);
    }
}
