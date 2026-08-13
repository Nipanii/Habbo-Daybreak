package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.habbohotel.games.battleball.BattleBallUpdate;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallFullGameStatusComposer extends MessageComposer {
    private final BattleBallGame game; private final BattleBallUpdate update; private final long now;
    public BattleBallFullGameStatusComposer(BattleBallGame game, BattleBallUpdate update, long now) { this.game = game; this.update = update; this.now = now; }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallFullGameStatusComposer); this.response.appendInt(1); this.response.appendInt(this.game.id());
        this.response.appendInt(this.update.sequence()); this.response.appendInt(this.update.state().id()); this.response.appendInt((int) this.update.timeRemainingMs());
        this.response.appendInt(this.game.roundSeconds() * 1000); BattleBallWire.writeObjects(this.response, this.update, this.now);
        this.response.appendInt(this.game.map().width()); this.response.appendInt(this.game.map().height());
        BattleBallWire.writeTiles(this.response, this.update.priorityTiles()); BattleBallWire.writeScores(this.response, this.update.scores());
        BattleBallWire.writeEvents(this.response, this.update, this.now); return this.response;
    }
}
