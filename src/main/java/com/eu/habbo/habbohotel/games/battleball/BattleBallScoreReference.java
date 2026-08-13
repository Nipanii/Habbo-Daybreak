package com.eu.habbo.habbohotel.games.battleball;

public record BattleBallScoreReference(int points, int teamId, int userId) {
    public BattleBallScoreReference {
        if (points < 0 || teamId < 0 || userId <= 0) {
            throw new IllegalArgumentException("Invalid BattleBall score reference");
        }
    }
}
