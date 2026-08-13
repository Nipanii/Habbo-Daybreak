package com.eu.habbo.messages.incoming.games.battleball;

public final class BattleBallRequestFullStatusEvent extends BattleBallHandler {
    @Override public void handle() { int id = this.packet.readRequiredInt(); this.packet.readRequiredInt(); this.requireEnd(); if (this.habbo() != null) this.manager().requestFull(this.habbo(), id); }
}
