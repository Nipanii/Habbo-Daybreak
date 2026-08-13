package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallLoungeInfoComposer extends MessageComposer {
    private final int currencyType, ticketCost, skillLevel;
    public BattleBallLoungeInfoComposer(int currencyType, int ticketCost, int skillLevel) {
        this.currencyType = currencyType; this.ticketCost = ticketCost; this.skillLevel = skillLevel;
    }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallLoungeInfoComposer); this.response.appendInt(1);
        this.response.appendInt(this.currencyType); this.response.appendInt(this.ticketCost); this.response.appendInt(this.skillLevel); return this.response;
    }
}
