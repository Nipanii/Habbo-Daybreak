package com.eu.habbo.messages.incoming.games.battleball;

public final class BattleBallInitiateCreateEvent extends BattleBallHandler {
    @Override public void handle() { this.requireEnd(); if (this.habbo() != null) this.manager().sendLounge(this.habbo()); }
}
