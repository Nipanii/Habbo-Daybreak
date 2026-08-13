package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;
import java.util.List;

public final class BattleBallInstanceListComposer extends MessageComposer {
    private final List<BattleBallGame> games;
    public BattleBallInstanceListComposer(List<BattleBallGame> games) { this.games = games; }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallInstanceListComposer); this.response.appendInt(this.games.size());
        this.games.forEach(game -> BattleBallWire.writeInstance(this.response, game)); return this.response;
    }
}
