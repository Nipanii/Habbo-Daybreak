package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallTicketBalanceComposer extends MessageComposer {
    private final int balance;
    public BattleBallTicketBalanceComposer(int balance) { this.balance = Math.max(0, balance); }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallTicketBalanceComposer);
        this.response.appendInt(this.balance);
        return this.response;
    }
}
