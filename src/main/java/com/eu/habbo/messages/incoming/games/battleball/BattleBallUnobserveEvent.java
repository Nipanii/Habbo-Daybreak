package com.eu.habbo.messages.incoming.games.battleball;

public final class BattleBallUnobserveEvent extends BattleBallHandler {
    @Override public void handle() { int id = this.packet.readRequiredInt(); this.requireEnd(); if (this.habbo() != null) this.manager().unwatch(this.habbo(), id); }
}
