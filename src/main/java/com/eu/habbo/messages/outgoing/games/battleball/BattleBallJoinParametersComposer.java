package com.eu.habbo.messages.outgoing.games.battleball;

import com.eu.habbo.habbohotel.games.battleball.BattleBallGame;
import com.eu.habbo.habbohotel.games.battleball.BattleBallTeam;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.messages.outgoing.Outgoing;

public final class BattleBallJoinParametersComposer extends MessageComposer {
    private final BattleBallGame game;
    public BattleBallJoinParametersComposer(BattleBallGame game) { this.game = game; }
    @Override protected ServerMessage composeInternal() {
        this.response.init(Outgoing.BattleBallJoinParametersComposer); this.response.appendInt(this.game.id());
        this.response.appendInt(this.game.teams().size());
        for (BattleBallTeam team : this.game.teams()) {
            this.response.appendInt(team.id()); this.response.appendInt(team.players().size()); this.response.appendInt(team.capacity());
        }
        return this.response;
    }
}
