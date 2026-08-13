package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallGameInstanceComposer extends MessageComposer {
    private final BattleBallGame game;
    public BattleBallGameInstanceComposer(BattleBallGame game) { this.game = game; }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallGameInstanceComposer); BattleBallWire.writeInstance(this.response, this.game); return this.response;
    }
}
