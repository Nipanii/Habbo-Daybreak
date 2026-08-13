package com.eu.habbo.messages.incoming.games.battleball;

import com.eu.habbo.messages.outgoing.Outgoing;
public final class BattleBallRejoinEvent extends BattleBallHandler {
    @Override public void handle() { int id = this.packet.readRequiredInt(); this.requireEnd(); if (this.habbo() != null) this.failure(Outgoing.BattleBallJoinFailedComposer, id, this.manager().rejoin(this.habbo(), id)); }
}
