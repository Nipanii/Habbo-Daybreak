package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallEvent;
import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.habbohotel.games.battleball.BattleBallPin;
import com.eu.habbo.habbohotel.games.battleball.BattleBallPlayer;
import com.eu.habbo.habbohotel.games.battleball.BattleBallPowerUp;
import com.eu.habbo.habbohotel.games.battleball.BattleBallTile;
import com.eu.habbo.habbohotel.games.battleball.BattleBallUpdate;
import com.eu.habbo.messages.ServerMessage;
import java.util.Map;

final class BattleBallWire {
    private BattleBallWire() {}

    static void writeInstance(ServerMessage response, BattleBallGame game) {
        response.appendInt(game.id()); response.appendString(game.name()); response.appendInt(game.ownerUserId());
        response.appendString(game.ownerName()); response.appendInt(game.map().id()); response.appendInt(game.state().id());
        response.appendBoolean(game.privateGame()); response.appendInt(game.teams().size()); response.appendInt(game.players().size());
        response.appendInt(game.capacity()); response.appendInt(game.roundSeconds());
    }

    static void writePlayer(ServerMessage response, BattleBallPlayer player) {
        response.appendInt(0); response.appendInt(player.objectId()); response.appendInt(player.userId());
        response.appendInt(player.position().x()); response.appendInt(player.position().y()); response.appendDouble(player.position().z());
        response.appendInt(player.position().direction()); response.appendInt(player.state().id());
        response.appendInt(player.colouringForUserId()); response.appendString(player.name()); response.appendString(player.motto());
        response.appendString(player.figure()); response.appendString(player.sex()); response.appendInt(player.teamId()); response.appendInt(player.objectId());
    }

    static void writePower(ServerMessage response, BattleBallPowerUp power, long now) {
        response.appendInt(1); response.appendInt(power.objectId());
        response.appendInt((int) Math.max(0, (power.expiresAt() - now + 499) / 500));
        response.appendInt(power.holderObjectId()); response.appendInt(power.type().id());
        response.appendInt(power.position().x()); response.appendInt(power.position().y()); response.appendDouble(power.position().z());
    }

    static void writePin(ServerMessage response, BattleBallPin pin, long now) {
        response.appendInt(2); response.appendInt(pin.objectId()); response.appendInt(pin.position().x());
        response.appendInt(pin.position().y()); response.appendDouble(pin.position().z());
        response.appendInt((int) Math.max(0, (pin.expiresAt() - now + 499) / 500));
    }

    static void writeObjects(ServerMessage response, BattleBallUpdate update, long now) {
        response.appendInt(update.players().size() + update.powers().size() + update.pins().size());
        update.players().forEach(player -> writePlayer(response, player));
        update.powers().forEach(power -> writePower(response, power, now));
        update.pins().forEach(pin -> writePin(response, pin, now));
    }

    static void writeTiles(ServerMessage response, Iterable<BattleBallTile> tiles) {
        if (tiles instanceof java.util.Collection<?> collection) response.appendInt(collection.size());
        else throw new IllegalArgumentException("BattleBall tiles must be countable");
        for (BattleBallTile tile : tiles) {
            response.appendInt(tile.x()); response.appendInt(tile.y()); response.appendInt(tile.colour());
            response.appendInt(tile.state().id()); response.appendInt(tile.floorHeight());
            response.appendBoolean(tile.walkable());
        }
    }

    static void writeScores(ServerMessage response, Map<Integer, Integer> scores) {
        response.appendInt(scores.size());
        scores.forEach((team, score) -> { response.appendInt(team); response.appendInt(score); });
    }

    static void writeEvents(ServerMessage response, BattleBallUpdate update, long now) {
        response.appendInt(update.events().size());
        for (BattleBallEvent event : update.events()) {
            response.appendInt(event.type());
            switch (event.type()) {
                case 0 -> writeSpawn(response, update, event.objectId(), now);
                case 1 -> response.appendInt(event.objectId());
                case 2 -> { response.appendInt(event.objectId()); response.appendInt(event.x()); response.appendInt(event.y()); }
                case 3 -> { response.appendInt(event.objectId()); response.appendInt(event.secondaryId()); response.appendInt(event.value()); }
                case 4 -> { response.appendInt(event.objectId()); response.appendInt(event.value()); response.appendInt(event.x()); }
                case 5 -> { response.appendInt(event.objectId()); response.appendInt(event.secondaryId()); response.appendInt(event.x()); response.appendInt(event.value()); }
                default -> throw new IllegalStateException("Unknown BattleBall event type " + event.type());
            }
        }
    }

    private static void writeSpawn(ServerMessage response, BattleBallUpdate update, int objectId, long now) {
        for (BattleBallPlayer player : update.players()) if (player.objectId() == objectId) { writePlayer(response, player); return; }
        for (BattleBallPowerUp power : update.powers()) if (power.objectId() == objectId) { writePower(response, power, now); return; }
        for (BattleBallPin pin : update.pins()) if (pin.objectId() == objectId) { writePin(response, pin, now); return; }
        // Spawn and despawn can occur within one tick; encode a harmless despawn-shaped placeholder.
        response.appendInt(2); response.appendInt(objectId); response.appendInt(0); response.appendInt(0); response.appendDouble(0); response.appendInt(0);
    }
}
