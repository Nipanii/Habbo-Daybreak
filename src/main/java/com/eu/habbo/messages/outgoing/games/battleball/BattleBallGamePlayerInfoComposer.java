package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallPlayer;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallGamePlayerInfoComposer extends MessageComposer {
    private final int instanceId;
    private final BattleBallPlayer player;

    public BattleBallGamePlayerInfoComposer(int instanceId, BattleBallPlayer player) {
        this.instanceId = instanceId;
        this.player = player;
    }

    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallGamePlayerInfoComposer);
        this.response.appendInt(this.instanceId);
        BattleBallWire.writePlayer(this.response, this.player);
        return this.response;
    }
}
