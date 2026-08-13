package com.eu.habbo.messages.incoming.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.messages.outgoing.Outgoing;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallFailureComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallJoinParametersComposer;
public final class BattleBallInitiateJoinEvent extends BattleBallHandler {
    @Override public void handle() { int id = this.packet.readRequiredInt(); this.requireEnd(); if (this.habbo() == null) return; BattleBallGame game = this.manager().get(id);
        if (game == null) this.client.sendResponse(new BattleBallFailureComposer(Outgoing.BattleBallJoinFailedComposer, id, 1)); else this.client.sendResponse(new BattleBallJoinParametersComposer(game)); }
}
