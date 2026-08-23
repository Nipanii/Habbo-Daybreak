package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallTicketPurchaseResultComposer extends MessageComposer {
    private final int status, ticketsAdded;
    private final String recipient;
    public BattleBallTicketPurchaseResultComposer(int status, String recipient, int ticketsAdded) {
        this.status = status; this.recipient = recipient == null ? "" : recipient;
        this.ticketsAdded = ticketsAdded;
    }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallTicketPurchaseResultComposer);
        this.response.appendInt(this.status); this.response.appendString(this.recipient);
        this.response.appendInt(this.ticketsAdded);
        return this.response;
    }
}
