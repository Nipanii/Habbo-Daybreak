package com.eu.habbo.habbohotel.games.battleball;

import com.eu.habbo.Emulator;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallFullGameStatusComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallGameEndComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallGameInstanceComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallGameLocationComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallGamePlayerInfoComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallGameStartComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallGameStatusComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallInstanceListComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallLoungeInfoComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallSignalComposer;
import com.eu.habbo.messages.outgoing.users.HabboActivityPointNotificationMessageComposer;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class BattleBallManager {
    public static final int FAILURE_UNKNOWN = 0;
    public static final int FAILURE_NOT_FOUND = 1;
    public static final int FAILURE_NOT_OWNER = 2;
    public static final int FAILURE_BAD_STATE = 3;
    public static final int FAILURE_FULL = 4;
    public static final int FAILURE_BAD_TEAM = 5;
    public static final int FAILURE_BAD_PARAMETERS = 6;
    public static final int FAILURE_INSUFFICIENT_TICKETS = 7;
    public static final int FAILURE_ALREADY_IN_GAME = 8;
    public static final int FAILURE_NOT_ENOUGH_TEAMS = 9;
    public static final int FAILURE_NOT_A_PLAYER = 10;
    public static final int FAILURE_NOT_ALLOWED = 11;

    public record Creation(String name, int mapId, int roundSeconds, int teamCount, int teamCapacity,
                           int selectedTeam, boolean privateGame, Set<BattleBallPowerType> powers) {}
    public record Result(BattleBallGame game, int failure) {
        public boolean success() { return this.failure == -1; }
        static Result success(BattleBallGame game) { return new Result(game, -1); }
        static Result failed(int reason) { return new Result(null, reason); }
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(BattleBallManager.class);
    private final BattleBallRepository repository = new BattleBallRepository();
    private final Map<Integer, BattleBallGame> games = new ConcurrentHashMap<>();
    private final Map<Integer, Integer> userGames = new ConcurrentHashMap<>();
    /** Hotel room each participant occupied before entering an isolated BattleBall arena. */
    private final Map<Integer, Integer> returnRooms = new ConcurrentHashMap<>();
    /** Players currently watching the games list, so newly created games appear without a room reload. */
    private final Set<Integer> lounge = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Integer> lastSequences = new ConcurrentHashMap<>();
    private final Map<Integer, BattleBallState> lastStates = new ConcurrentHashMap<>();
    private final AtomicInteger nextInstanceId = new AtomicInteger(1);
    private Map<Integer, BattleBallMap> maps = Map.of();
    private Map<Integer, Map<Integer, List<BattleBallPosition>>> spawns = Map.of();
    private ScheduledFuture<?> tickTask;
    private volatile boolean enabled;

    public BattleBallManager() {
        this.enabled = Emulator.getConfig().getBoolean("battleball.enabled", false);
        if (!this.enabled) return;
        try {
            BattleBallRepository.LoadedData loaded = this.repository.load();
            this.maps = loaded.maps();
            this.spawns = loaded.spawns();
            this.nextInstanceId.set(this.repository.nextInstanceId());
            this.tickTask = Emulator.getThreading().getService().scheduleAtFixedRate(this::tick, 500, 500, TimeUnit.MILLISECONDS);
            LOGGER.info("BattleBall -> Loaded all five maps and started the authoritative 500 ms loop.");
        } catch (SQLException exception) {
            this.enabled = false;
            LOGGER.error("BattleBall -> Disabled because its SQL data is unavailable.", exception);
        }
    }

    public boolean enabled() { return this.enabled; }
    public int currencyType() { return Emulator.getConfig().getInt("battleball.ticket.currency.type", 0); }
    public int ticketCost() { return Math.max(0, Emulator.getConfig().getInt("battleball.ticket.cost", 2)); }
    public int skillLevel(int userId) { return this.repository.skillLevel(userId); }

    public List<BattleBallGame> instancesFor(Habbo habbo) {
        return this.games.values().stream().filter(game -> !game.privateGame() || game.ownerUserId() == habbo.getHabboInfo().getId()
                        || game.players().stream().anyMatch(player -> player.userId() == habbo.getHabboInfo().getId()))
                .sorted(Comparator.comparingInt(BattleBallGame::id)).toList();
    }

    public BattleBallGame get(int instanceId) { return this.games.get(instanceId); }

    public void sendLounge(Habbo habbo) {
        // Asking for the lounge is what marks a player as watching the games list. Without this, a new
        // game only reached players and watchers of games that already existed, so anyone standing in the
        // lounge kept an empty list until they re-entered the room.
        this.lounge.add(habbo.getHabboInfo().getId());
        habbo.getClient().sendResponse(new BattleBallLoungeInfoComposer(this.currencyType(), this.ticketCost(), this.skillLevel(habbo.getHabboInfo().getId())));
        habbo.getClient().sendResponse(new BattleBallInstanceListComposer(this.instancesFor(habbo)));
    }

    /** Drops a player from the games-list audience; they re-subscribe by asking for the lounge again. */
    public void leaveLounge(int userId) { this.lounge.remove(userId); }

    public synchronized Result create(Habbo habbo, Creation creation) {
        if (!this.enabled) return Result.failed(FAILURE_NOT_ALLOWED);
        int userId = habbo.getHabboInfo().getId();
        Integer existingId = this.userGames.get(userId);
        if (existingId != null) {
            BattleBallGame existing = this.games.get(existingId);
            return existing != null && existing.ownerUserId() == userId && existing.state() == BattleBallState.LOBBY
                    ? Result.success(existing) : Result.failed(FAILURE_ALREADY_IN_GAME);
        }
        if (!valid(creation) || this.games.size() >= Emulator.getConfig().getInt("battleball.max.instances", 100)) return Result.failed(FAILURE_BAD_PARAMETERS);
        BattleBallMap map = this.maps.get(creation.mapId());
        Map<Integer, List<BattleBallPosition>> mapSpawns = this.spawns.getOrDefault(creation.mapId(), Map.of());
        for (int team = 0; team < creation.teamCount(); team++) if (mapSpawns.getOrDefault(team, List.of()).size() < creation.teamCapacity()) return Result.failed(FAILURE_BAD_PARAMETERS);

        int id = this.nextInstanceId.getAndIncrement();
        BattleBallGame game = new BattleBallGame(id, creation.name(), userId, habbo.getHabboInfo().getUsername(), map,
                creation.roundSeconds(), creation.teamCount(), creation.teamCapacity(), creation.privateGame(), creation.powers(), mapSpawns,
                Emulator.getConfig().getInt("battleball.idle.timeout.seconds", 60),
                Emulator.getConfig().getInt("battleball.idle.warning.seconds", 15));
        if (!game.addPlayer(userId, habbo.getHabboInfo().getUsername(), habbo.getHabboInfo().getMotto(), habbo.getHabboInfo().getLook(),
                habbo.getHabboInfo().getGender().name(), creation.selectedTeam())) return Result.failed(FAILURE_BAD_TEAM);
        this.games.put(id, game); this.userGames.put(userId, id); this.lastSequences.put(id, game.sequence()); this.lastStates.put(id, game.state());
        // The creator stays in the lounge. In the original game a created game sat in its lobby window
        // gathering players, and only the start of a round took anyone out of the room they were in.
        this.broadcastLobby(game);
        return Result.success(game);
    }

    public synchronized int join(Habbo habbo, int instanceId, int teamId) {
        BattleBallGame game = this.games.get(instanceId);
        int userId = habbo.getHabboInfo().getId();
        if (game == null) return FAILURE_NOT_FOUND;
        Integer current = this.userGames.get(userId);
        if (current != null) {
            // Already in this game: picking another team is a switch, not a second join. Rejecting it
            // outright meant the team links did nothing at all once a player had joined.
            if (current != instanceId) return FAILURE_ALREADY_IN_GAME;
            if (!game.changeTeam(userId, teamId)) return FAILURE_BAD_TEAM;
            BattleBallPlayer moved = game.players().stream().filter(p -> p.userId() == userId).findFirst().orElse(null);
            if (moved != null) this.sendToGame(game, ignored -> new BattleBallGamePlayerInfoComposer(instanceId, moved));
            this.sendFull(game);
            this.broadcastLobby(game);
            return -1;
        }
        if (game.state() != BattleBallState.LOBBY) return FAILURE_BAD_STATE;
        if (teamId < 0 || teamId >= game.teams().size()) return FAILURE_BAD_TEAM;
        if (game.teams().get(teamId).full()) return FAILURE_FULL;
        if (!game.addPlayer(userId, habbo.getHabboInfo().getUsername(), habbo.getHabboInfo().getMotto(), habbo.getHabboInfo().getLook(),
                habbo.getHabboInfo().getGender().name(), teamId)) return FAILURE_FULL;
        this.userGames.put(userId, instanceId);
        // Joining only puts the player in a team; they remain in the lounge until the round starts.
        BattleBallPlayer joined = game.players().stream().filter(player -> player.userId() == userId).findFirst().orElse(null);
        if (joined != null) this.sendToGame(game, ignored -> new BattleBallGamePlayerInfoComposer(instanceId, joined));
        this.broadcastLobby(game); return -1;
    }

    public synchronized int leave(Habbo habbo, int instanceId) {
        BattleBallGame game = this.games.get(instanceId);
        int userId = habbo.getHabboInfo().getId();
        if (game == null) return FAILURE_NOT_FOUND;
        // An owner's explicit Leave/Close terminates their instance in every state. Keeping a running
        // ownerless game alive leaves userGames membership behind and rejects their next creation as
        // "already in a game" even though the client has fully exited the arena.
        if (game.ownerUserId() == userId) {
            this.deleteInternal(game);
            return -1;
        }
        if (game.removePlayer(userId) == null) {
            game.removeWatcher(userId);
            this.userGames.remove(userId, instanceId);
            this.returnToHotelRoom(userId);
            return -1;
        }
        this.userGames.remove(userId, instanceId);
        this.returnToHotelRoom(userId);
        this.broadcastLobby(game);
        return -1;
    }

    public synchronized int kick(Habbo actor, int instanceId, int targetUserId) {
        BattleBallGame game = this.games.get(instanceId);
        if (game == null) return FAILURE_NOT_FOUND;
        if (game.ownerUserId() != actor.getHabboInfo().getId()) return FAILURE_NOT_OWNER;
        if (game.state() != BattleBallState.LOBBY || targetUserId == game.ownerUserId()) return FAILURE_BAD_STATE;
        if (game.removePlayer(targetUserId) == null) return FAILURE_NOT_A_PLAYER;
        this.userGames.remove(targetUserId, instanceId);
        this.returnToHotelRoom(targetUserId);
        this.broadcastLobby(game); return -1;
    }

    public synchronized int delete(Habbo actor, int instanceId) {
        BattleBallGame game = this.games.get(instanceId);
        if (game == null) return FAILURE_NOT_FOUND;
        if (game.ownerUserId() != actor.getHabboInfo().getId()) return FAILURE_NOT_OWNER;
        if (game.state() != BattleBallState.LOBBY) return FAILURE_BAD_STATE;
        this.deleteInternal(game); return -1;
    }

    public synchronized int start(Habbo actor, int instanceId) {
        BattleBallGame game = this.games.get(instanceId);
        if (game == null) return FAILURE_NOT_FOUND;
        int userId = actor.getHabboInfo().getId();
        if (game.ownerUserId() != userId) return FAILURE_NOT_OWNER;
        // Treat a repeated Start from the owner as a state-resync request. A delayed/doubled UI click can
        // arrive after the first request has already moved the authoritative game into COUNTDOWN; returning
        // BAD_STATE leaves only that host displaying the lobby while every other participant starts. A full
        // snapshot is idempotent and repairs either COUNTDOWN or RUNNING without restarting the round.
        if (game.state() == BattleBallState.COUNTDOWN || game.state() == BattleBallState.RUNNING) {
            this.sendFull(actor, game);
            return -1;
        }
        if (game.state() != BattleBallState.LOBBY) return FAILURE_BAD_STATE;
        if (!game.canStart(userId)) return FAILURE_NOT_ENOUGH_TEAMS;
        List<Habbo> participants = new ArrayList<>();
        for (BattleBallPlayer player : game.players()) {
            Habbo habbo = Emulator.getGameEnvironment().getHabboManager().getHabbo(player.userId());
            if (habbo == null) return FAILURE_BAD_STATE;
            participants.add(habbo);
        }
        BattleBallRepository.ChargeResult charge = this.repository.chargeRound(game, participants, this.currencyType(), this.ticketCost());
        if (!charge.success()) return FAILURE_INSUFFICIENT_TICKETS;
        if (!game.start(userId, System.currentTimeMillis())) return FAILURE_BAD_STATE;
        // Everyone leaves their room together, at the moment the round begins rather than when they
        // joined it, so the pre-game lobby window can sit over the lounge the way Shockwave's did.
        for (Habbo habbo : participants) this.enterArena(habbo);
        for (Habbo habbo : participants) {
            Integer balance = charge.balances().get(habbo.getHabboInfo().getId());
            if (balance != null) {
                habbo.getHabboInfo().applyCommittedCurrencyBalance(this.currencyType(), balance);
                int charged = charge.costs().getOrDefault(habbo.getHabboInfo().getId(), this.ticketCost());
                habbo.getClient().sendResponse(new HabboActivityPointNotificationMessageComposer(balance, -charged, this.currencyType()));
            }
        }
        this.sendToGame(game, ignored -> new BattleBallGameStartComposer(game));
        this.sendFull(game);
        this.lastSequences.put(game.id(), game.sequence());
        return -1;
    }

    public int move(Habbo habbo, int instanceId, int x, int y) {
        BattleBallGame game = this.games.get(instanceId);
        if (game == null) return FAILURE_NOT_FOUND;
        return game.requestMove(habbo.getHabboInfo().getId(), x, y, System.currentTimeMillis()) ? -1 : FAILURE_NOT_ALLOWED;
    }

    public int activate(Habbo habbo, int instanceId, int powerObjectId) {
        BattleBallGame game = this.games.get(instanceId);
        if (game == null) return FAILURE_NOT_FOUND;
        return game.activatePower(habbo.getHabboInfo().getId(), powerObjectId, System.currentTimeMillis()) ? -1 : FAILURE_NOT_ALLOWED;
    }

    public int watch(Habbo habbo, int instanceId) {
        BattleBallGame game = this.games.get(instanceId);
        if (game == null) return FAILURE_NOT_FOUND;
        if (game.watchers().size() >= Emulator.getConfig().getInt("battleball.max.spectators", 50)) return FAILURE_FULL;
        int userId = habbo.getHabboInfo().getId();
        Integer existing = this.userGames.putIfAbsent(userId, instanceId);
        if (existing != null) return FAILURE_ALREADY_IN_GAME;
        this.enterArena(habbo);
        game.addWatcher(userId);
        habbo.getClient().sendResponse(new BattleBallGameLocationComposer(game, true));
        this.sendFull(habbo, game); return -1;
    }

    public void unwatch(Habbo habbo, int instanceId) {
        int userId = habbo.getHabboInfo().getId();
        BattleBallGame game = this.games.get(instanceId); if (game != null) game.removeWatcher(userId);
        this.userGames.remove(userId, instanceId);
        this.returnToHotelRoom(userId);
    }

    public int rejoin(Habbo habbo, int instanceId) {
        BattleBallGame game = this.games.get(instanceId);
        if (game == null) return FAILURE_NOT_FOUND;
        if (!game.rejoin(habbo.getHabboInfo().getId())) return FAILURE_NOT_A_PLAYER;
        this.userGames.put(habbo.getHabboInfo().getId(), instanceId);
        habbo.getClient().sendResponse(new BattleBallSignalComposer(Outgoing.BattleBallPlayerRejoinedComposer, instanceId, habbo.getHabboInfo().getId()));
        this.sendFull(habbo, game); return -1;
    }

    public void requestFull(Habbo habbo, int instanceId) {
        BattleBallGame game = this.games.get(instanceId);
        if (game != null && this.hasArenaAccess(habbo, game)) this.sendFull(habbo, game);
    }

    public synchronized void disconnected(int userId) {
        Integer instanceId = this.userGames.get(userId); if (instanceId == null) return;
        // A disconnected client cannot consume a RoomForward packet. Forget its return target while
        // still releasing every other participant if this disconnect terminates the instance.
        this.returnRooms.remove(userId);
        BattleBallGame game = this.games.get(instanceId);
        if (game == null) {
            this.userGames.remove(userId, instanceId);
            return;
        }
        // Closing the client is a definitive leave. Retaining an unreachable player for a reconnect grace
        // period creates a ghost which cannot move and leaves the two clients with different player sets.
        if (game.ownerUserId() == userId) {
            this.deleteInternal(game);
            return;
        }
        game.removePlayer(userId);
        game.removeWatcher(userId);
        this.userGames.remove(userId, instanceId);
        this.broadcastLobby(game);
    }

    public synchronized void dispose() {
        this.enabled = false;
        if (this.tickTask != null) this.tickTask.cancel(false);
        this.games.values().forEach(BattleBallGame::dispose); this.games.clear(); this.userGames.clear(); this.returnRooms.clear();
        this.lastSequences.clear(); this.lastStates.clear();
    }

    private void tick() {
        if (!this.enabled) return;
        long now = System.currentTimeMillis();
        try {
            for (BattleBallGame game : List.copyOf(this.games.values())) {
                Set<Integer> recipientsBeforeTick = this.recipientIds(game);
                BattleBallState before = game.state();
                game.tick(now);
                boolean ownerPresent = game.players().stream().anyMatch(player -> player.userId() == game.ownerUserId());
                if (!ownerPresent) {
                    // Owner leave, disconnect and idle removal all have the same terminal meaning. Do not
                    // reset an active round into an ownerless lobby which can never be started or deleted.
                    this.deleteInternal(game, recipientsBeforeTick);
                    continue;
                }
                for (Map.Entry<Integer, Integer> warning : game.consumeIdleWarnings().entrySet()) {
                    Habbo warned = Emulator.getGameEnvironment().getHabboManager().getHabbo(warning.getKey());
                    if (warned != null) warned.getClient().sendResponse(new BattleBallSignalComposer(
                            Outgoing.BattleBallIdleWarningComposer, game.id(), warning.getValue()));
                }
                this.reconcileMembership(game);
                BattleBallState after = game.state();
                if (after == BattleBallState.ENDING && before != BattleBallState.ENDING) {
                    this.repository.saveResults(game);
                    this.sendToRecipients(game, recipientsBeforeTick, ignored -> new BattleBallGameEndComposer(game));
                    for (BattleBallPlayer player : game.players()) {
                        Habbo participant = Emulator.getGameEnvironment().getHabboManager().getHabbo(player.userId());
                        if (participant != null) participant.getClient().sendResponse(new BattleBallSignalComposer(
                                Outgoing.BattleBallSkillLevelChangedComposer, this.repository.skillLevel(player.userId())));
                    }
                }
                // A finished match is NOT torn down on a timer. deleteInternal() ends by forwarding
                // every participant back to the lounge room, which evicted players from the arena five
                // seconds after the whistle and took the final standings with it. The game stays in its
                // reset lobby state until the players leave of their own accord; leave() disposes it and
                // sends them home, and the ownerless check above still collects abandoned games.
                int last = this.lastSequences.getOrDefault(game.id(), -1);
                if (game.sequence() != last) {
                    BattleBallUpdate update = game.consumeUpdate(now, false);
                    this.sendToRecipients(game, recipientsBeforeTick, ignored -> new BattleBallGameStatusComposer(game, update, now, last));
                    this.lastSequences.put(game.id(), game.sequence());
                }
                this.lastStates.put(game.id(), after);
            }
        } catch (Exception exception) {
            LOGGER.error("BattleBall -> Authoritative tick failed.", exception);
        }
    }

    private void sendFull(BattleBallGame game) {
        long now = System.currentTimeMillis();
        BattleBallUpdate update = game.consumeUpdate(now, true);
        this.sendToGame(game, ignored -> new BattleBallFullGameStatusComposer(game, update, now));
    }

    private void sendFull(Habbo habbo, BattleBallGame game) {
        long now = System.currentTimeMillis();
        habbo.getClient().sendResponse(new BattleBallFullGameStatusComposer(game, game.consumeUpdate(now, true), now));
    }

    private boolean hasArenaAccess(Habbo habbo, BattleBallGame game) {
        int userId = habbo.getHabboInfo().getId();
        return game.watchers().contains(userId)
                || game.players().stream().anyMatch(player -> player.userId() == userId);
    }

    private void broadcastLobby(BattleBallGame game) {
        for (Habbo habbo : onlineRecipients()) habbo.getClient().sendResponse(new BattleBallGameInstanceComposer(game));
    }

    private void sendToGame(BattleBallGame game, java.util.function.Function<Habbo, MessageComposer> composer) {
        this.sendToRecipients(game, Set.of(), composer);
    }

    private Set<Integer> recipientIds(BattleBallGame game) {
        Set<Integer> recipients = new HashSet<>(game.watchers());
        game.players().forEach(player -> recipients.add(player.userId()));
        return recipients;
    }

    private void sendToRecipients(BattleBallGame game, Set<Integer> additionalRecipients,
                                  java.util.function.Function<Habbo, MessageComposer> composer) {
        Set<Integer> recipients = this.recipientIds(game);
        recipients.addAll(additionalRecipients);
        for (int userId : recipients) {
            Habbo habbo = Emulator.getGameEnvironment().getHabboManager().getHabbo(userId);
            if (habbo != null && habbo.getClient() != null) habbo.getClient().sendResponse(composer.apply(habbo));
        }
    }

    private Collection<Habbo> onlineRecipients() {
        List<Habbo> result = new ArrayList<>();
        for (BattleBallGame game : this.games.values()) {
            for (BattleBallPlayer player : game.players()) {
                Habbo habbo = Emulator.getGameEnvironment().getHabboManager().getHabbo(player.userId());
                if (habbo != null && !result.contains(habbo)) result.add(habbo);
            }
            for (int userId : game.watchers()) {
                Habbo habbo = Emulator.getGameEnvironment().getHabboManager().getHabbo(userId);
                if (habbo != null && !result.contains(habbo)) result.add(habbo);
            }
        }
        // Everyone with the games list open, whether or not they are in a game yet. Offline ids are
        // dropped as they are found so the set cannot grow without bound across sessions.
        for (Integer userId : this.lounge) {
            Habbo habbo = Emulator.getGameEnvironment().getHabboManager().getHabbo(userId);
            if (habbo == null) { this.lounge.remove(userId); continue; }
            if (!result.contains(habbo)) result.add(habbo);
        }
        return result;
    }

    private void deleteInternal(BattleBallGame game) {
        this.deleteInternal(game, Set.of());
    }

    private void deleteInternal(BattleBallGame game, Set<Integer> additionalRecipients) {
        Set<Integer> participants = this.recipientIds(game);
        participants.addAll(additionalRecipients);
        this.games.remove(game.id()); this.lastSequences.remove(game.id()); this.lastStates.remove(game.id());
        participants.forEach(userId -> this.userGames.remove(userId, game.id()));
        this.sendToRecipients(game, additionalRecipients,
                ignored -> new BattleBallSignalComposer(Outgoing.BattleBallGameDeletedComposer, game.id()));
        // Everyone watching the games list has to lose the row too, not just the players who were in it.
        // Without this an owner cancelling their game left it listed for every other player in the
        // lounge until they re-entered the room.
        Set<Integer> notified = new HashSet<>(participants);
        for (Habbo habbo : this.onlineRecipients()) {
            if (notified.add(habbo.getHabboInfo().getId())) {
                habbo.getClient().sendResponse(
                        new BattleBallSignalComposer(Outgoing.BattleBallGameDeletedComposer, game.id()));
            }
        }
        game.dispose();
        participants.forEach(this::returnToHotelRoom);
    }

    private void reconcileMembership(BattleBallGame game) {
        Set<Integer> current = new HashSet<>();
        game.players().forEach(player -> current.add(player.userId()));
        current.addAll(game.watchers());
        List<Integer> removed = this.userGames.entrySet().stream()
                .filter(entry -> entry.getValue() == game.id() && !current.contains(entry.getKey()))
                .map(Map.Entry::getKey).toList();
        removed.forEach(userId -> {
            this.userGames.remove(userId, game.id());
            this.returnToHotelRoom(userId);
        });
    }

    private void enterArena(Habbo habbo) {
        if (habbo == null || habbo.getHabboInfo() == null) return;
        int userId = habbo.getHabboInfo().getId();
        Room currentRoom = habbo.getHabboInfo().getCurrentRoom();
        if (currentRoom == null) return;
        this.returnRooms.putIfAbsent(userId, currentRoom.getId());
        // Close the ordinary room connection before the BattleBall location composer opens the arena.
        Emulator.getGameEnvironment().getRoomManager().leaveRoom(habbo, currentRoom, true);
    }

    private void returnToHotelRoom(int userId) {
        Integer roomId = this.returnRooms.remove(userId);
        if (roomId == null || roomId <= 0) return;
        Habbo habbo = Emulator.getGameEnvironment().getHabboManager().getHabbo(userId);
        if (habbo == null || habbo.getClient() == null) return;
        habbo.goToRoom(roomId);
    }

    private static boolean valid(Creation creation) {
        if (creation == null || creation.name() == null || creation.name().trim().isEmpty() || creation.name().trim().length() > 32
                || creation.mapId() < 1 || creation.mapId() > 5 || creation.roundSeconds() < 60 || creation.roundSeconds() > 600
                || creation.teamCount() < 2 || creation.teamCount() > 4 || creation.teamCapacity() < 1 || creation.teamCapacity() > 5
                || creation.selectedTeam() < 0 || creation.selectedTeam() >= creation.teamCount() || creation.powers() == null || creation.powers().size() > 8) return false;
        return creation.powers().stream().allMatch(power -> power != null && power.id() >= 1 && power.id() <= 8);
    }
}
