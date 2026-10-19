package com.eu.habbo.habbohotel.items.interactions;

import com.eu.habbo.habbohotel.users.HabboItem;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class InteractionRollerTest {
    private static InteractionRoller roller(int id, double z) {
        InteractionRoller roller = new InteractionRoller(id, 1, null, "0", 0, 0);
        roller.setZ(z);
        return roller;
    }

    private static HabboItem rug(int id, double z) {
        HabboItem rug = new InteractionDefault(id, 1, null, "0", 0, 0);
        rug.setZ(z);
        return rug;
    }

    @Test
    void rollerMayBePlacedOnARugOrAnotherRoller() {
        assertTrue(roller(1, 0).canStackAt(null, new ArrayList<>()), "placement is no longer restricted by the roller itself");
    }

    @Test
    void onlyTheTopmostRollerOnATileWorks() {
        InteractionRoller bottom = roller(1, 0), middle = roller(2, 0.5), top = roller(3, 1.0);
        assertSame(top, InteractionRoller.topRoller(List.of(middle, top, bottom)));
        assertSame(top, InteractionRoller.topRoller(List.of(rug(4, 2.0), top, bottom)), "a non-roller above does not count");
    }

    @Test
    void aRollerOnARugIsTheTopRoller() {
        InteractionRoller onRug = roller(2, 0.01);
        assertSame(onRug, InteractionRoller.topRoller(List.of(rug(1, 0), onRug)));
    }

    @Test
    void rollersAtTheSameHeightPickOneDeterministically() {
        InteractionRoller a = roller(5, 0), b = roller(9, 0);
        assertSame(b, InteractionRoller.topRoller(List.of(a, b)));
        assertSame(b, InteractionRoller.topRoller(List.of(b, a)));
    }

    @Test
    void noRollerOnTheTile() {
        assertNull(InteractionRoller.topRoller(List.of(rug(1, 0))));
        assertNull(InteractionRoller.topRoller(List.of()));
    }
}
