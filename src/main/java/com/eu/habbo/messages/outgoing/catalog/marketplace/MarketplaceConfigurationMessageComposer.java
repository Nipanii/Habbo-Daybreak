package com.eu.habbo.messages.outgoing.catalog.marketplace;

import com.eu.habbo.habbohotel.catalog.marketplace.MarketPlace;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public class MarketplaceConfigurationMessageComposer extends MessageComposer {
    @Override
    protected ServerMessage composeInternal() {
        this.response.init(Outgoing.MarketplaceConfigurationMessageComposer);
        this.response.appendBoolean(true);
        this.response.appendInt(MarketPlace.COMMISSION_PERCENT); //Commision Percentage.
        this.response.appendInt(10); //Credits
        this.response.appendInt(5); //Advertisements
        this.response.appendInt(1); //Min price
        this.response.appendInt(1000000); //Max price
        this.response.appendInt(48); //Hours in marketplace
        this.response.appendInt(7); //Days to display
        return this.response;
    }
}
