package com.eu.habbo.habbohotel.games.battleball;

import java.util.ArrayDeque;
import java.util.Collection;

public final class BattleBallPlayer {
    private final int objectId;
    private final int userId;
    private final String name;
    private final String motto;
    private final String figure;
    private final String sex;
    private int teamId;
    private BattleBallPosition position;
    private BattleBallPlayerState state = BattleBallPlayerState.NORMAL;
    private long stateUntil;
    private int heldPowerObjectId = -1;
    private int colouringForUserId = -1;
    private long disconnectedUntil;
    private long lastActionAt;
    private boolean idleWarningSent;
    private final ArrayDeque<BattleBallPosition> movementPath = new ArrayDeque<>();
    private BattleBallPosition movementGoal;

    public BattleBallPlayer(int objectId, int userId, String name, String motto, String figure, String sex,
                            int teamId, BattleBallPosition position) {
        this.objectId = objectId;
        this.userId = userId;
        this.name = name;
        this.motto = motto;
        this.figure = figure;
        this.sex = sex;
        this.teamId = teamId;
        this.position = position;
    }

    public int objectId() { return this.objectId; }
    public int userId() { return this.userId; }
    public String name() { return this.name; }
    public String motto() { return this.motto; }
    public String figure() { return this.figure; }
    public String sex() { return this.sex; }
    public int teamId() { return this.teamId; }
    public BattleBallPosition position() { return this.position; }
    public BattleBallPlayerState state() { return this.state; }
    public long stateUntil() { return this.stateUntil; }
    public int heldPowerObjectId() { return this.heldPowerObjectId; }
    public int colouringForUserId() { return this.colouringForUserId; }
    public long disconnectedUntil() { return this.disconnectedUntil; }
    public long lastActionAt() { return this.lastActionAt; }
    public boolean idleWarningSent() { return this.idleWarningSent; }

    void setTeamId(int teamId) { this.teamId = teamId; }
    void setPosition(BattleBallPosition position) { this.position = position; }
    void setHeldPowerObjectId(int objectId) { this.heldPowerObjectId = objectId; }
    void setColouringForUserId(int userId) { this.colouringForUserId = userId; }
    void setDisconnectedUntil(long value) { this.disconnectedUntil = value; }
    void setIdleWarningSent(boolean value) { this.idleWarningSent = value; }

    BattleBallPosition movementGoal() { return this.movementGoal; }

    void replaceMovementPath(Collection<BattleBallPosition> path, BattleBallPosition goal) {
        this.movementPath.clear();
        this.movementPath.addAll(path);
        this.movementGoal = goal;
    }

    BattleBallPosition pollMovementStep() { return this.movementPath.pollFirst(); }
    void clearMovementPath() { this.movementPath.clear(); this.movementGoal = null; }

    void markActive(long now) {
        this.lastActionAt = now;
        this.idleWarningSent = false;
    }

    void setState(BattleBallPlayerState state, long until) {
        this.state = state;
        this.stateUntil = until;
    }
}
