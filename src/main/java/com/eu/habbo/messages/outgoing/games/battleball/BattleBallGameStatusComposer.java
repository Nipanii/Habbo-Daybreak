package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.habbohotel.games.battleball.BattleBallUpdate;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallGameStatusComposer extends MessageComposer {
    private final BattleBallGame game; private final BattleBallUpdate update; private final long now; private final int baseSequence;
    public BattleBallGameStatusComposer(BattleBallGame game, BattleBallUpdate update, long now, int baseSequence) {
        this.game = game; this.update = update; this.now = now; this.baseSequence = baseSequence;
    }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallGameStatusComposer); this.response.appendInt(1); this.response.appendInt(this.game.id());
        this.response.appendInt(this.baseSequence); this.response.appendInt(this.update.sequence());
        this.response.appendInt(this.update.state().id()); this.response.appendInt((int) this.update.timeRemainingMs());
        BattleBallWire.writeObjects(this.response, this.update, this.now); BattleBallWire.writeTiles(this.response, this.update.priorityTiles());
        BattleBallWire.writeTiles(this.response, this.update.fillTiles()); BattleBallWire.writeScores(this.response, this.update.scores());
        BattleBallWire.writeEvents(this.response, this.update, this.now); return this.response;
    }
}
