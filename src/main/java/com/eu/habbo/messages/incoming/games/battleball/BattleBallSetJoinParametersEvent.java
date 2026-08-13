package com.eu.habbo.messages.incoming.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.messages.outgoing.Outgoing;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallGameLocationComposer;
public final class BattleBallSetJoinParametersEvent extends BattleBallHandler {
    @Override public void handle() { int id = this.packet.readRequiredInt(); int team = this.packet.readRequiredInt(); this.requireEnd(); if (this.habbo() == null) return;
        int result = this.manager().join(this.habbo(), id, team); this.failure(Outgoing.BattleBallJoinFailedComposer, id, result);
        if (result < 0) { BattleBallGame game = this.manager().get(id); this.client.sendResponse(new BattleBallGameLocationComposer(game, false)); this.manager().requestFull(this.habbo(), id); } }
}
