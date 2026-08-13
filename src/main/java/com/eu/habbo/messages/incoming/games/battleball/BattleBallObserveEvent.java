package com.eu.habbo.messages.incoming.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.messages.outgoing.Outgoing;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallFailureComposer;
import com.eu.habbo.messages.outgoing.games.battleball.BattleBallGameInstanceComposer;

public final class BattleBallObserveEvent extends BattleBallHandler {
    @Override public void handle() {
        int id = this.packet.readRequiredInt(); this.requireEnd(); if (this.habbo() == null) return;
        BattleBallGame game = this.manager().get(id);
        if (game == null) this.client.sendResponse(new BattleBallFailureComposer(Outgoing.BattleBallInstanceNotAvailableComposer, id, 1));
        else { game.addWatcher(this.habbo().getHabboInfo().getId()); this.client.sendResponse(new BattleBallGameInstanceComposer(game)); }
    }
}
