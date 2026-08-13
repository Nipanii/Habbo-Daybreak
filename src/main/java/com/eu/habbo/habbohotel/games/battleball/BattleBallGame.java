package com.eu.habbo.habbohotel.games.battleball;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.rooms.RoomLayout;
import com.eu.habbo.habbohotel.rooms.pathfinding.impl.GridPathfinder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Server-authoritative original BattleBall match. One manager-owned 500 ms task calls {@link #tick}. */
public final class BattleBallGame {
    public static final int MAX_ACTIVE_POWERS = 2;
    private static final long TICK_MS = 500L;
    private static final int[][] DIRECTIONS = {{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-1,-1}};

    private final int id;
    private final String name;
    private final int ownerUserId;
    private final String ownerName;
    private final BattleBallMap map;
    private final int roundSeconds;
    private final boolean privateGame;
    private final List<BattleBallTeam> teams;
    private final Set<BattleBallPowerType> enabledPowers;
    private final Map<Integer, List<BattleBallPosition>> spawns;
    private final BattleBallTile[][] tiles;
    private final Map<Integer, BattleBallPlayer> players = new LinkedHashMap<>();
    private final Set<Integer> watchers = new HashSet<>();
    private final Map<Integer, BattleBallPowerUp> powers = new LinkedHashMap<>();
    private final Map<Integer, BattleBallPin> pins = new LinkedHashMap<>();
    private final Map<Integer, Long> cannonStunAt = new HashMap<>();
    private final LinkedHashSet<BattleBallTile> priorityTiles = new LinkedHashSet<>();
    private final LinkedHashSet<BattleBallTile> fillTiles = new LinkedHashSet<>();
    private final List<BattleBallEvent> events = new ArrayList<>();
    private final Random random;
    private final long idleTimeoutMs;
    private final long idleWarningMs;
    private final Map<Integer, Integer> idleWarnings = new LinkedHashMap<>();
    private BattleBallState state = BattleBallState.LOBBY;
    private int nextObjectId = 1;
    private int sequence;
    private long stateEndsAt;
    private boolean initialPowersSpawned;
    private int roundNumber;

    public BattleBallGame(int id, String name, int ownerUserId, String ownerName, BattleBallMap map,
                          int roundSeconds, int teamCount, int teamCapacity, boolean privateGame,
                          Collection<BattleBallPowerType> enabledPowers,
                          Map<Integer, List<BattleBallPosition>> spawns, int idleTimeoutSeconds, int idleWarningSeconds) {
        if (id <= 0 || ownerUserId <= 0 || name == null || name.isBlank() || name.trim().length() > 32) {
            throw new IllegalArgumentException("Invalid BattleBall identity");
        }
        if (roundSeconds < 60 || roundSeconds > 600 || teamCount < 2 || teamCount > 4
                || teamCapacity < 1 || teamCapacity > 5) {
            throw new IllegalArgumentException("Invalid BattleBall parameters");
        }
        this.id = id;
        this.name = name.trim();
        this.ownerUserId = ownerUserId;
        this.ownerName = ownerName;
        this.map = map;
        this.roundSeconds = roundSeconds;
        this.privateGame = privateGame;
        this.idleTimeoutMs = Math.max(30, idleTimeoutSeconds) * 1_000L;
        this.idleWarningMs = Math.min(this.idleTimeoutMs, Math.max(5, idleWarningSeconds) * 1_000L);
        this.enabledPowers = Set.copyOf(enabledPowers);
        this.spawns = new HashMap<>();
        spawns.forEach((team, positions) -> this.spawns.put(team, List.copyOf(positions)));
        this.teams = new ArrayList<>(teamCount);
        for (int team = 0; team < teamCount; team++) this.teams.add(new BattleBallTeam(team, teamCapacity));
        this.tiles = new BattleBallTile[map.width()][map.height()];
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) this.tiles[x][y] = new BattleBallTile(x, y, map.isPlayable(x, y), map.floorHeight(x, y), map.isWalkable(x, y));
        }
        this.random = new Random(((long) id << 32) ^ ownerUserId);
    }

    public synchronized int id() { return this.id; }
    public synchronized String name() { return this.name; }
    public synchronized int ownerUserId() { return this.ownerUserId; }
    public synchronized String ownerName() { return this.ownerName; }
    public synchronized BattleBallMap map() { return this.map; }
    public synchronized int roundSeconds() { return this.roundSeconds; }
    public synchronized boolean privateGame() { return this.privateGame; }
    public synchronized BattleBallState state() { return this.state; }
    public synchronized int sequence() { return this.sequence; }
    public synchronized int roundNumber() { return this.roundNumber; }
    public synchronized List<BattleBallTeam> teams() { return List.copyOf(this.teams); }
    public synchronized Collection<BattleBallPlayer> players() { return List.copyOf(this.players.values()); }
    public synchronized Collection<BattleBallPowerUp> powers() { return List.copyOf(this.powers.values()); }
    public synchronized Collection<BattleBallPin> pins() { return List.copyOf(this.pins.values()); }
    public synchronized Set<Integer> watchers() { return Set.copyOf(this.watchers); }
    public synchronized Set<BattleBallPowerType> enabledPowers() { return this.enabledPowers; }
    public synchronized int capacity() { return this.teams.stream().mapToInt(BattleBallTeam::capacity).sum(); }

    public synchronized BattleBallTile tile(int x, int y) {
        return this.map.isPlayable(x, y) ? this.tiles[x][y] : null;
    }

    /** Movement target lookup: ramps are stood on but never scored, so this is wider than {@link #tile}. */
    public synchronized BattleBallTile walkableTile(int x, int y) {
        return this.map.isWalkable(x, y) ? this.tiles[x][y] : null;
    }

    public synchronized boolean addPlayer(int userId, String name, String motto, String figure, String sex, int teamId) {
        if (this.state != BattleBallState.LOBBY || this.players.containsKey(userId) || teamId < 0 || teamId >= this.teams.size()) return false;
        BattleBallTeam team = this.teams.get(teamId);
        BattleBallPosition spawn = this.nextSpawn(teamId, team.players().size());
        if (team.full() || spawn == null) return false;
        BattleBallPlayer player = new BattleBallPlayer(this.nextObjectId++, userId, name, motto, figure, sex, teamId, spawn);
        if (!team.add(player)) return false;
        this.players.put(userId, player);
        this.sequence++;
        this.events.add(BattleBallEvent.spawn(player.objectId()));
        return true;
    }

    public synchronized boolean changeTeam(int userId, int teamId) {
        if (this.state != BattleBallState.LOBBY || teamId < 0 || teamId >= this.teams.size()) return false;
        BattleBallPlayer player = this.players.get(userId);
        BattleBallTeam target = this.teams.get(teamId);
        if (player == null || player.teamId() == teamId || target.full()) return false;
        this.teams.get(player.teamId()).remove(userId);
        if (!target.add(player)) return false;
        player.setTeamId(teamId);
        player.setPosition(this.nextSpawn(teamId, target.players().size() - 1));
        this.sequence++;
        return true;
    }

    public synchronized BattleBallPlayer removePlayer(int userId) {
        BattleBallPlayer player = this.players.remove(userId);
        if (player == null) return null;
        this.teams.get(player.teamId()).remove(userId);
        this.events.add(BattleBallEvent.despawn(player.objectId()));
        this.sequence++;
        return player;
    }

    public synchronized void addWatcher(int userId) { this.watchers.add(userId); }
    public synchronized void removeWatcher(int userId) { this.watchers.remove(userId); }

    public synchronized boolean start(int actorUserId, long now) {
        if (!this.canStart(actorUserId)) return false;
        this.resetBoard();
        this.roundNumber++;
        this.state = BattleBallState.COUNTDOWN;
        this.stateEndsAt = now + 3_000L;
        this.sequence++;
        return true;
    }

    public synchronized boolean canStart(int actorUserId) {
        return actorUserId == this.ownerUserId && this.state == BattleBallState.LOBBY
                && this.teams.stream().filter(team -> !team.players().isEmpty()).count() >= 2;
    }

    public synchronized boolean requestMove(int userId, int x, int y, long now) {
        if (this.state != BattleBallState.RUNNING) return false;
        BattleBallPlayer player = this.players.get(userId);
        BattleBallTile target = this.walkableTile(x, y);
        if (player == null || target == null || player.disconnectedUntil() > 0 || player.state() == BattleBallPlayerState.STUNNED
                || player.state() == BattleBallPlayerState.BALL_BROKEN) return false;
        List<BattleBallPosition> path = this.findPath(player.position(), x, y);
        if (path.isEmpty()) return false;
        player.replaceMovementPath(path, new BattleBallPosition(x, y));
        player.markActive(now);
        return true;
    }

    public synchronized boolean activatePower(int userId, int powerObjectId, long now) {
        if (this.state != BattleBallState.RUNNING) return false;
        BattleBallPlayer player = this.players.get(userId);
        BattleBallPowerUp power = this.powers.get(powerObjectId);
        if (player == null || power == null || power.holderObjectId() != player.objectId()
                || player.heldPowerObjectId() != powerObjectId || power.expiresAt() <= now) return false;
        player.setHeldPowerObjectId(-1);
        player.markActive(now);
        this.powers.remove(powerObjectId);
        this.events.add(BattleBallEvent.activation(player.objectId(), powerObjectId, player.position().direction(), power.type().id()));
        this.events.add(BattleBallEvent.despawn(powerObjectId));
        this.applyPower(player, power.type(), now);
        this.sequence++;
        return true;
    }

    public synchronized boolean markDisconnected(int userId, long until) {
        BattleBallPlayer player = this.players.get(userId);
        if (player == null) return false;
        player.clearMovementPath();
        player.setDisconnectedUntil(until);
        return true;
    }

    public synchronized boolean rejoin(int userId) {
        BattleBallPlayer player = this.players.get(userId);
        if (player == null || player.disconnectedUntil() == 0) return false;
        player.setDisconnectedUntil(0);
        this.sequence++;
        return true;
    }

    public synchronized void tick(long now) {
        if (this.state == BattleBallState.DISPOSED || this.state == BattleBallState.LOBBY) return;
        boolean changed = this.expireTemporaryState(now) | this.expireObjects(now) | this.expireDisconnected(now);
        if ((this.state == BattleBallState.COUNTDOWN || this.state == BattleBallState.RUNNING) && this.players.size() < 2) {
            this.state = BattleBallState.ENDING;
            this.stateEndsAt = now + 5_000L;
            for (BattleBallPlayer player : this.players.values()) player.clearMovementPath();
            changed = true;
        } else if (this.state == BattleBallState.COUNTDOWN && now >= this.stateEndsAt) {
            this.state = BattleBallState.RUNNING;
            this.stateEndsAt = now + this.roundSeconds * 1_000L;
            this.initialPowersSpawned = false;
            changed = true;
        } else if (this.state == BattleBallState.RUNNING) {
            changed |= this.checkIdle(now);
            if (now < this.stateEndsAt) changed |= this.advanceMovingPlayers(now);
            if (!this.initialPowersSpawned) {
                int count = this.random.nextInt(MAX_ACTIVE_POWERS + 1);
                for (int i = 0; i < count; i++) this.spawnPower(now);
                this.initialPowersSpawned = true;
                changed = true;
            } else if (now >= this.stateEndsAt) {
                this.state = BattleBallState.ENDING;
                this.stateEndsAt = now + 5_000L;
                changed = true;
            } else if (this.random.nextDouble() < 0.06) {
                changed |= this.spawnPower(now);
            }
        } else if (this.state == BattleBallState.ENDING && now >= this.stateEndsAt) {
            this.state = BattleBallState.LOBBY;
            this.resetBoard();
            changed = true;
        }
        if (changed) this.sequence++;
    }

    public synchronized BattleBallUpdate consumeUpdate(long now, boolean full) {
        List<BattleBallTile> priority = full ? this.allTiles() : List.copyOf(this.priorityTiles);
        List<BattleBallTile> fill = full ? List.of() : List.copyOf(this.fillTiles);
        BattleBallUpdate update = new BattleBallUpdate(this.sequence, this.state, this.timeRemaining(now),
                List.copyOf(this.players.values()), List.copyOf(this.powers.values()), List.copyOf(this.pins.values()),
                priority, fill, this.scores(), full ? List.of() : List.copyOf(this.events));
        if (!full) {
            this.priorityTiles.clear();
            this.fillTiles.clear();
            this.events.clear();
        }
        return update;
    }

    public synchronized void dispose() {
        if (this.state == BattleBallState.DISPOSED) return;
        this.state = BattleBallState.DISPOSED;
        this.powers.clear();
        this.pins.clear();
        this.cannonStunAt.clear();
        this.watchers.clear();
        this.idleWarnings.clear();
        this.sequence++;
    }

    public synchronized Map<Integer, Integer> consumeIdleWarnings() {
        Map<Integer, Integer> result = Map.copyOf(this.idleWarnings);
        this.idleWarnings.clear();
        return result;
    }

    public synchronized Map<Integer, Integer> scores() {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        for (BattleBallTeam team : this.teams) result.put(team.id(), 0);
        for (int y = 0; y < this.map.height(); y++) {
            for (int x = 0; x < this.map.width(); x++) {
                for (BattleBallScoreReference reference : this.tiles[x][y].scoreReferences()) {
                    result.computeIfPresent(reference.teamId(), (ignored, score) -> score + reference.points());
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    public synchronized Map<Integer, Integer> playerScores() {
        Map<Integer, Integer> result = new LinkedHashMap<>();
        this.players.values().forEach(player -> result.put(player.userId(), 0));
        for (int y = 0; y < this.map.height(); y++) {
            for (int x = 0; x < this.map.width(); x++) {
                for (BattleBallScoreReference reference : this.tiles[x][y].scoreReferences()) {
                    result.computeIfPresent(reference.userId(), (ignored, score) -> score + reference.points());
                }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    private void collectPower(BattleBallPlayer player, int x, int y, long now) {
        BattleBallPowerUp found = null;
        for (BattleBallPowerUp power : this.powers.values()) {
            if (power.holderObjectId() < 0 && power.position().x() == x && power.position().y() == y) { found = power; break; }
        }
        if (found == null) return;
        if (player.heldPowerObjectId() > 0) {
            BattleBallPowerUp old = this.powers.remove(player.heldPowerObjectId());
            if (old != null) this.events.add(BattleBallEvent.despawn(old.objectId()));
        }
        BattleBallPowerType type = found.type();
        if (type == BattleBallPowerType.QUESTION_MARK) {
            List<BattleBallPowerType> choices = this.enabledPowers.stream().filter(value -> value != BattleBallPowerType.QUESTION_MARK).toList();
            if (choices.isEmpty()) return;
            type = choices.get(this.random.nextInt(choices.size()));
        }
        found.collect(player, type, now + 7_500L);
        player.setHeldPowerObjectId(found.objectId());
        this.events.add(BattleBallEvent.pickup(player.objectId(), found.objectId(), type.id()));
    }

    private boolean hitPin(BattleBallPlayer player, int x, int y, long now) {
        BattleBallPin hit = this.pins.values().stream().filter(pin -> pin.position().x() == x && pin.position().y() == y).findFirst().orElse(null);
        if (hit == null) return false;
        this.pins.remove(hit.objectId());
        this.events.add(BattleBallEvent.despawn(hit.objectId()));
        this.setState(player, BattleBallPlayerState.BALL_BROKEN, now + 5_000L, now);
        return true;
    }

    private void applyPower(BattleBallPlayer player, BattleBallPowerType type, long now) {
        switch (type) {
            case LIGHT_BULB -> this.paintRadius(player, 5);
            case SPRING -> this.setState(player, BattleBallPlayerState.HIGH_JUMPS, now + 10_000L, now);
            case FLASHLIGHT -> this.paintLine(player, false, now);
            case CANNON -> this.paintLine(player, true, now);
            case BOX_OF_PINS -> this.spawnPins(player, now);
            case HARLEQUIN -> this.harlequin(player, now);
            case BOMB -> this.bomb(player, now);
            case DRILL -> this.setState(player, BattleBallPlayerState.CLEANING_TILES, now + 10_000L, now);
            case QUESTION_MARK -> { }
        }
    }

    private void paintRadius(BattleBallPlayer player, int radius) {
        int radiusSquared = radius * radius;
        for (BattleBallPosition position : this.map.playablePositions()) {
            if (position.distanceSquared(player.position()) > radiusSquared) continue;
            BattleBallTile tile = this.tiles[position.x()][position.y()];
            if (tile.state() == BattleBallTileState.SEALED) continue;
            BattleBallTileState target = tile.colour() == player.teamId()
                    ? (tile.state() == BattleBallTileState.DEFAULT ? BattleBallTileState.CLICKED : tile.state().next())
                    : BattleBallTileState.CLICKED;
            if (tile.paint(player.teamId(), target, this.scoringUser(player))) {
                this.priorityTiles.add(tile);
                if (target == BattleBallTileState.SEALED) this.fillFrom(tile, player.teamId(), this.scoringUser(player));
            }
        }
    }

    private void paintLine(BattleBallPlayer player, boolean cannon, long now) {
        int[] vector = directionVector(player.position().direction());
        int x = player.position().x();
        int y = player.position().y();
        BattleBallPosition last = player.position();
        while (this.map.isPlayable(x + vector[0], y + vector[1])) {
            x += vector[0]; y += vector[1];
            BattleBallTile tile = this.tiles[x][y];
            if (tile.state() != BattleBallTileState.SEALED) {
                BattleBallTileState target = cannon ? BattleBallTileState.SEALED
                        : (tile.colour() == player.teamId()
                            ? (tile.state() == BattleBallTileState.DEFAULT ? BattleBallTileState.CLICKED : tile.state().next())
                            : BattleBallTileState.CLICKED);
                if (tile.paint(player.teamId(), target, this.scoringUser(player))) {
                    this.priorityTiles.add(tile);
                    if (target == BattleBallTileState.SEALED) this.fillFrom(tile, player.teamId(), this.scoringUser(player));
                }
            }
            if (cannon) {
                for (BattleBallPlayer other : this.players.values()) {
                    if (other.userId() != player.userId() && other.position().x() == x && other.position().y() == y) {
                        this.pushSideways(other, player.position().direction());
                        this.setState(other, BattleBallPlayerState.STUNNED, now + 5_000L, now);
                    }
                }
            }
            last = new BattleBallPosition(x, y, 0.0, player.position().direction());
        }
        if (cannon && last != player.position()) {
            player.clearMovementPath();
            player.setState(BattleBallPlayerState.FLYING_THROUGH_AIR, 0);
            this.events.add(BattleBallEvent.state(player.objectId(), BattleBallPlayerState.FLYING_THROUGH_AIR.id(), 800));
            this.cannonStunAt.put(player.objectId(), now + 800L);
            player.setPosition(last);
            this.events.add(BattleBallEvent.move(player.objectId(), last.x(), last.y()));
        }
    }

    private void spawnPins(BattleBallPlayer player, long now) {
        int[] vector = directionVector(player.position().direction());
        int centreX = player.position().x() + vector[0] * 6;
        int centreY = player.position().y() + vector[1] * 6;
        List<BattleBallPosition> candidates = new ArrayList<>();
        for (BattleBallPosition position : this.map.playablePositions()) {
            int dx = position.x() - centreX;
            int dy = position.y() - centreY;
            if (dx * dx + dy * dy <= 9 && (position.x() != player.position().x() || position.y() != player.position().y())) candidates.add(position);
        }
        Collections.shuffle(candidates, this.random);
        int count = Math.min(candidates.size(), 8 + this.random.nextInt(8));
        for (int i = 0; i < count; i++) {
            int objectId = this.nextObjectId++;
            BattleBallPin pin = new BattleBallPin(objectId, candidates.get(i), now + (12 + this.random.nextInt(5)) * 1_000L);
            this.pins.put(objectId, pin);
            this.events.add(BattleBallEvent.spawn(objectId));
        }
    }

    private void harlequin(BattleBallPlayer activator, long now) {
        for (BattleBallPlayer player : this.players.values()) {
            if (player.teamId() == activator.teamId() || player.state() != BattleBallPlayerState.NORMAL) continue;
            player.setColouringForUserId(activator.userId());
            this.setState(player, BattleBallPlayerState.COLORING_FOR_OPPONENT, now + 10_000L, now);
        }
    }

    private void bomb(BattleBallPlayer activator, long now) {
        for (BattleBallPosition position : this.map.playablePositions()) {
            int distance = position.distanceSquared(activator.position());
            if (distance <= 9) {
                BattleBallTile tile = this.tiles[position.x()][position.y()];
                if (tile.reset()) this.priorityTiles.add(tile);
            }
        }
        for (BattleBallPlayer player : this.players.values()) {
            if (player.position().distanceSquared(activator.position()) <= 49) {
                if (player != activator) this.pushAway(player, activator.position());
                this.setState(player, BattleBallPlayerState.STUNNED, now + 5_000L, now);
            }
        }
    }

    private void pushAway(BattleBallPlayer player, BattleBallPosition centre) {
        int dx = Integer.compare(player.position().x(), centre.x());
        int dy = Integer.compare(player.position().y(), centre.y());
        int x = player.position().x() + dx;
        int y = player.position().y() + dy;
        if (this.map.isPlayable(x, y)) {
            player.setPosition(new BattleBallPosition(x, y, 0.0, direction(centre.x(), centre.y(), x, y)));
            this.events.add(BattleBallEvent.move(player.objectId(), x, y));
        }
    }

    private void pushSideways(BattleBallPlayer player, int cannonDirection) {
        int[] right = directionVector(cannonDirection + 2);
        int x = player.position().x() + right[0];
        int y = player.position().y() + right[1];
        if (!this.map.isPlayable(x, y)) {
            int[] left = directionVector(cannonDirection - 2);
            x = player.position().x() + left[0]; y = player.position().y() + left[1];
        }
        if (this.map.isPlayable(x, y)) {
            player.setPosition(new BattleBallPosition(x, y, 0.0, player.position().direction()));
            this.events.add(BattleBallEvent.move(player.objectId(), x, y));
        }
    }

    private void setState(BattleBallPlayer player, BattleBallPlayerState state, long until, long now) {
        if (state == BattleBallPlayerState.STUNNED || state == BattleBallPlayerState.BALL_BROKEN
                || state == BattleBallPlayerState.FLYING_THROUGH_AIR) player.clearMovementPath();
        player.setState(state, until);
        this.events.add(BattleBallEvent.state(player.objectId(), state.id(), (int) Math.max(0, until - now)));
    }

    private boolean expireTemporaryState(long now) {
        boolean changed = false;
        List<Integer> cannonDue = this.cannonStunAt.entrySet().stream().filter(entry -> entry.getValue() <= now).map(Map.Entry::getKey).toList();
        for (int objectId : cannonDue) {
            this.cannonStunAt.remove(objectId);
            this.players.values().stream().filter(player -> player.objectId() == objectId).findFirst()
                    .ifPresent(player -> this.setState(player, BattleBallPlayerState.STUNNED, now + 5_000L, now));
            changed = true;
        }
        for (BattleBallPlayer player : this.players.values()) {
            if (player.state() != BattleBallPlayerState.NORMAL && player.stateUntil() > 0 && player.stateUntil() <= now) {
                player.setState(BattleBallPlayerState.NORMAL, 0);
                player.setColouringForUserId(-1);
                this.events.add(BattleBallEvent.state(player.objectId(), BattleBallPlayerState.NORMAL.id(), 0));
                changed = true;
            }
        }
        return changed;
    }

    private boolean expireObjects(long now) {
        boolean changed = false;
        List<Integer> expiredPowers = this.powers.values().stream().filter(power -> power.expiresAt() <= now).map(BattleBallPowerUp::objectId).toList();
        for (int id : expiredPowers) {
            BattleBallPowerUp power = this.powers.remove(id);
            if (power != null) {
                if (power.holderObjectId() > 0) this.players.values().stream().filter(player -> player.objectId() == power.holderObjectId()).findFirst().ifPresent(player -> player.setHeldPowerObjectId(-1));
                this.events.add(BattleBallEvent.despawn(id));
                changed = true;
            }
        }
        List<Integer> expiredPins = this.pins.values().stream().filter(pin -> pin.expiresAt() <= now).map(BattleBallPin::objectId).toList();
        for (int id : expiredPins) { this.pins.remove(id); this.events.add(BattleBallEvent.despawn(id)); changed = true; }
        return changed;
    }

    private boolean expireDisconnected(long now) {
        List<Integer> expired = this.players.values().stream().filter(player -> player.disconnectedUntil() > 0 && player.disconnectedUntil() <= now).map(BattleBallPlayer::userId).toList();
        for (int userId : expired) this.removePlayer(userId);
        return !expired.isEmpty();
    }

    private boolean checkIdle(long now) {
        boolean changed = false;
        List<Integer> remove = new ArrayList<>();
        for (BattleBallPlayer player : this.players.values()) {
            if (player.disconnectedUntil() > 0) continue;
            if (player.lastActionAt() == 0) player.markActive(now);
            long remaining = this.idleTimeoutMs - (now - player.lastActionAt());
            if (remaining <= 0) remove.add(player.userId());
            else if (remaining <= this.idleWarningMs && !player.idleWarningSent()) {
                player.setIdleWarningSent(true);
                this.idleWarnings.put(player.userId(), (int) Math.max(1, (remaining + 999) / 1_000));
            }
        }
        for (int userId : remove) { this.removePlayer(userId); changed = true; }
        return changed;
    }

    private boolean spawnPower(long now) {
        if (this.map.id() == 5 || this.enabledPowers.isEmpty()) return false;
        long active = this.powers.values().stream().filter(power -> power.holderObjectId() < 0).count();
        if (active >= MAX_ACTIVE_POWERS) return false;
        List<BattleBallPowerType> choices = new ArrayList<>(this.enabledPowers);
        if (choices.size() >= 2) choices.add(BattleBallPowerType.QUESTION_MARK);
        List<BattleBallPosition> free = this.map.playablePositions().stream()
                .filter(position -> this.powers.values().stream().noneMatch(power -> power.holderObjectId() < 0 && power.position().x() == position.x() && power.position().y() == position.y()))
                .toList();
        if (free.isEmpty()) return false;
        BattleBallPowerUp power = new BattleBallPowerUp(this.nextObjectId++, choices.get(this.random.nextInt(choices.size())),
                free.get(this.random.nextInt(free.size())), now + (20 + this.random.nextInt(11)) * TICK_MS);
        this.powers.put(power.objectId(), power);
        this.events.add(BattleBallEvent.spawn(power.objectId()));
        return true;
    }

    private void fillFrom(BattleBallTile sealed, int teamId, int scoringUserId) {
        for (int[] direction : DIRECTIONS) {
            int startX = sealed.x() + direction[0];
            int startY = sealed.y() + direction[1];
            BattleBallTile start = this.tile(startX, startY);
            if (start == null || (start.colour() == teamId && start.state() == BattleBallTileState.SEALED)) continue;
            Set<BattleBallTile> region = new LinkedHashSet<>();
            ArrayDeque<BattleBallTile> open = new ArrayDeque<>();
            open.add(start);
            boolean bounded = true;
            while (!open.isEmpty() && bounded) {
                BattleBallTile current = open.removeFirst();
                if (!region.add(current)) continue;
                for (int[] step : DIRECTIONS) {
                    int x = current.x() + step[0];
                    int y = current.y() + step[1];
                    BattleBallTile neighbour = this.tile(x, y);
                    if (neighbour == null) { bounded = false; break; }
                    if (neighbour.colour() == teamId && neighbour.state() == BattleBallTileState.SEALED) continue;
                    if (!region.contains(neighbour)) open.addLast(neighbour);
                }
            }
            if (bounded) {
                for (BattleBallTile tile : region) if (tile.sealFromFill(teamId, scoringUserId)) this.fillTiles.add(tile);
            }
        }
    }

    private void resetBoard() {
        for (int y = 0; y < this.map.height(); y++) for (int x = 0; x < this.map.width(); x++) this.tiles[x][y].reset();
        this.powers.clear(); this.pins.clear(); this.cannonStunAt.clear(); this.idleWarnings.clear(); this.priorityTiles.clear(); this.fillTiles.clear(); this.events.clear();
        this.initialPowersSpawned = false;
        for (BattleBallTeam team : this.teams) {
            int index = 0;
            for (BattleBallPlayer player : team.players()) {
                player.setPosition(this.nextSpawn(team.id(), index++));
                player.setState(BattleBallPlayerState.NORMAL, 0);
                player.setHeldPowerObjectId(-1);
                player.setColouringForUserId(-1);
                player.clearMovementPath();
            }
        }
    }

    /** Advances every requested destination by one tile per game tick, applying normal landing rules at each step. */
    private boolean advanceMovingPlayers(long now) {
        boolean changed = false;
        for (BattleBallPlayer player : this.players.values()) {
            if (player.disconnectedUntil() > 0 || player.state() == BattleBallPlayerState.STUNNED
                    || player.state() == BattleBallPlayerState.BALL_BROKEN
                    || player.state() == BattleBallPlayerState.FLYING_THROUGH_AIR) {
                player.clearMovementPath();
                continue;
            }
            BattleBallPosition next = player.pollMovementStep();
            if (next == null) continue;
            if (!this.map.isWalkable(next.x(), next.y()) || this.isOccupiedByOther(player, next.x(), next.y())) {
                BattleBallPosition goal = player.movementGoal();
                List<BattleBallPosition> replacement = goal == null ? List.of()
                        : this.findPath(player.position(), goal.x(), goal.y());
                if (replacement.isEmpty()) {
                    player.clearMovementPath();
                    continue;
                }
                player.replaceMovementPath(replacement, goal);
                next = player.pollMovementStep();
                if (next == null || this.isOccupiedByOther(player, next.x(), next.y())) continue;
            }
            int facing = direction(player.position().x(), player.position().y(), next.x(), next.y());
            player.setPosition(new BattleBallPosition(next.x(), next.y(), 0.0, facing));
            player.markActive(now);
            this.events.add(BattleBallEvent.move(player.objectId(), next.x(), next.y()));
            this.collectPower(player, next.x(), next.y(), now);
            changed = true;
            if (this.hitPin(player, next.x(), next.y(), now)) continue;

            BattleBallTile tile = this.walkableTile(next.x(), next.y());
            if (tile == null || !tile.playable()) continue;
            int effectiveTeam = this.scoringTeam(player);
            boolean landed = tile.land(this.map.id(), effectiveTeam, this.scoringUser(player),
                    player.state() == BattleBallPlayerState.HIGH_JUMPS,
                    player.state() == BattleBallPlayerState.CLEANING_TILES);
            if (landed) {
                this.priorityTiles.add(tile);
                if (tile.state() == BattleBallTileState.SEALED) {
                    this.fillFrom(tile, effectiveTeam, this.scoringUser(player));
                }
            }
        }
        return changed;
    }

    private boolean isOccupiedByOther(BattleBallPlayer movingPlayer, int x, int y) {
        return this.players.values().stream().anyMatch(player -> player != movingPlayer
                && player.position().x() == x && player.position().y() == y);
    }

    /** Uses the hotel's shared grid pathfinder over the arena floor, including non-scoring ramps. */
    private List<BattleBallPosition> findPath(BattleBallPosition start, int targetX, int targetY) {
        GridPathfinder.Grid grid = new GridPathfinder.Grid() {
            @Override public int width() { return map.width(); }
            @Override public int height() { return map.height(); }
            @Override public boolean isWalkable(int x, int y) { return map.isWalkable(x, y); }
            @Override public double floorHeight(int x, int y) { return map.floorHeight(x, y); }
            @Override public boolean isOccupied(int x, int y) {
                return players.values().stream().anyMatch(player -> player.position().x() == x
                        && player.position().y() == y && player.position() != start);
            }
        };
        boolean allowFalling = Emulator.getConfig() == null
                || Emulator.getConfig().getBoolean("pathfinder.step.allow.falling", true);
        boolean retroStyleDiagonals = Emulator.getConfig() != null
                && Emulator.getConfig().getBoolean("pathfinder.retro-style.diagonals", false);
        return GridPathfinder.findPath(grid, start.x(), start.y(), targetX, targetY,
                        RoomLayout.MAXIMUM_STEP_HEIGHT, allowFalling, retroStyleDiagonals)
                .stream().map(step -> new BattleBallPosition(step.x(), step.y(), 0.0, 0)).toList();
    }

    private List<BattleBallTile> allTiles() {
        List<BattleBallTile> result = new ArrayList<>(this.map.width() * this.map.height());
        for (int y = 0; y < this.map.height(); y++) for (int x = 0; x < this.map.width(); x++) result.add(this.tiles[x][y]);
        return result;
    }

    private BattleBallPosition nextSpawn(int teamId, int index) {
        List<BattleBallPosition> teamSpawns = this.spawns.getOrDefault(teamId, List.of());
        return teamSpawns.isEmpty() ? null : teamSpawns.get(index % teamSpawns.size());
    }

    private int scoringUser(BattleBallPlayer player) {
        return player.colouringForUserId() > 0 ? player.colouringForUserId() : player.userId();
    }

    private int scoringTeam(BattleBallPlayer player) {
        if (player.colouringForUserId() <= 0) return player.teamId();
        BattleBallPlayer activator = this.players.get(player.colouringForUserId());
        return activator == null ? player.teamId() : activator.teamId();
    }

    private long timeRemaining(long now) {
        return this.stateEndsAt == 0 ? 0 : Math.max(0, this.stateEndsAt - now);
    }

    private static int direction(int fromX, int fromY, int toX, int toY) {
        int dx = Integer.compare(toX, fromX);
        int dy = Integer.compare(toY, fromY);
        for (int i = 0; i < DIRECTIONS.length; i++) if (DIRECTIONS[i][0] == dx && DIRECTIONS[i][1] == dy) return i;
        return 0;
    }

    private static int[] directionVector(int direction) {
        return DIRECTIONS[Math.floorMod(direction, DIRECTIONS.length)];
    }
}
