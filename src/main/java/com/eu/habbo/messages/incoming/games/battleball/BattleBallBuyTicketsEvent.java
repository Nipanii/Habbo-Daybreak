package com.eu.habbo.messages.incoming.games.battleball;

public final class BattleBallBuyTicketsEvent extends BattleBallHandler {
    @Override public int getRatelimit() { return 1_000; }
    @Override public void handle() {
        int bundleId = this.packet.readRequiredInt();
        String recipient = this.packet.readBoundedString(32);
        this.requireEnd();
        if (this.habbo() != null) this.manager().buyTickets(this.habbo(), bundleId, recipient);
    }
}
