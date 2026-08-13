package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallGameParametersComposer extends MessageComposer {
    private final BattleBallGame game;
    public BattleBallGameParametersComposer(BattleBallGame game) { this.game = game; }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallGameParametersComposer); this.response.appendInt(this.game.id());
        this.response.appendString(this.game.name()); this.response.appendInt(this.game.map().id()); this.response.appendInt(this.game.roundSeconds());
        this.response.appendInt(this.game.teams().size()); this.response.appendInt(this.game.teams().get(0).capacity());
        this.response.appendBoolean(this.game.privateGame()); this.response.appendInt(this.game.enabledPowers().size());
        this.game.enabledPowers().stream().sorted(java.util.Comparator.comparingInt(value -> value.id())).forEach(value -> this.response.appendInt(value.id()));
        return this.response;
    }
}
