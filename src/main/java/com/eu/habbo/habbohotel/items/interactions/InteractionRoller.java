package com.eu.habbo.habbohotel.items.interactions;

import com.eu.habbo.habbohotel.gameclients.GameClient;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.rooms.Room;
import com.eu.habbo.habbohotel.rooms.RoomUnit;
import com.eu.habbo.habbohotel.users.HabboItem;
import com.eu.habbo.messages.ServerMessage;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

public class InteractionRoller extends HabboItem {
    public static boolean NO_RULES = false;
    public static int DELAY = 400;

    public InteractionRoller(ResultSet set, Item baseItem) throws SQLException {
        super(set, baseItem);
        this.setExtradata("0");
    }

    public InteractionRoller(int id, int userId, Item item, String extradata, int limitedStack, int limitedSells) {
        super(id, userId, item, extradata, limitedStack, limitedSells);
        this.setExtradata("0");
    }

    @Override
    public void serializeExtradata(ServerMessage serverMessage) {
        serverMessage.appendInt((this.isLimited() ? 256 : 0));
        serverMessage.appendString(this.getExtradata());

        super.serializeExtradata(serverMessage);
    }

    @Override
    public boolean canWalkOn(RoomUnit roomUnit, Room room, Object[] objects) {
        return true;
    }

    @Override
    public boolean isWalkable() {
        return true;
    }

    @Override
    public void onClick(GameClient client, Room room, Object[] objects) throws Exception {
        super.onClick(client, room, objects);
    }

    @Override
    public void onWalk(RoomUnit roomUnit, Room room, Object[] objects) throws Exception {

    }

    @Override
    public void onWalkOn(RoomUnit roomUnit, Room room, Object[] objects) throws Exception {
        super.onWalkOn(roomUnit, room, objects);
    }

    @Override
    public void onWalkOff(RoomUnit roomUnit, Room room, Object[] objects) throws Exception {
        super.onWalkOff(roomUnit, room, objects);
    }


    /**
     * Rollers may be placed on top of anything, including other rollers. When several rollers share a tile,
     * only the topmost one works: this returns it (highest z, then highest id), or null if there is none.
     */
    public static InteractionRoller topRoller(Collection<HabboItem> tileItems) {
        InteractionRoller top = null;
        for (HabboItem item : tileItems) {
            if (!(item instanceof InteractionRoller)) continue;
            if (top == null || item.getZ() > top.getZ() || (item.getZ() == top.getZ() && item.getId() > top.getId())) {
                top = (InteractionRoller) item;
            }
        }
        return top;
    }
}
