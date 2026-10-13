package com.eu.habbo.habbohotel.catalog.marketplace;

import com.eu.habbo.Emulator;
import com.eu.habbo.database.Database;
import com.eu.habbo.habbohotel.gameclients.GameClient;
import com.eu.habbo.habbohotel.items.Item;
import com.eu.habbo.habbohotel.users.Habbo;
import com.eu.habbo.habbohotel.users.HabboInfo;
import com.eu.habbo.habbohotel.users.HabboInventory;
import com.eu.habbo.habbohotel.users.HabboItem;
import com.eu.habbo.habbohotel.users.inventory.ItemsComponent;
import com.eu.habbo.messages.ServerMessage;
import com.eu.habbo.messages.outgoing.MessageComposer;
import com.eu.habbo.plugin.PluginManager;
import com.eu.habbo.threading.ThreadPooling;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class MarketPlaceSellItemTest {
    private final GameClient client = mock(GameClient.class);
    private final HabboInventory inventory = mock(HabboInventory.class);
    private final ItemsComponent items = mock(ItemsComponent.class);
    private final HabboItem item = mock(HabboItem.class);

    MarketPlaceSellItemTest() {
        Habbo habbo = mock(Habbo.class);
        HabboInfo info = mock(HabboInfo.class);
        Item baseItem = mock(Item.class);

        when(client.getHabbo()).thenReturn(habbo);
        when(habbo.getHabboInfo()).thenReturn(info);
        when(info.getId()).thenReturn(7);
        when(habbo.getInventory()).thenReturn(inventory);
        when(inventory.getItemsComponent()).thenReturn(items);
        when(item.getId()).thenReturn(216516);
        when(item.getBaseItem()).thenReturn(baseItem);
        when(baseItem.allowMarketplace()).thenReturn(true);
    }

    private MockedStatic<Emulator> emulator(Connection connection) throws SQLException {
        PluginManager plugins = mock(PluginManager.class);
        when(plugins.fireEvent(any())).thenAnswer(invocation -> invocation.getArgument(0));

        HikariDataSource dataSource = mock(HikariDataSource.class);
        if (connection == null) {
            when(dataSource.getConnection()).thenThrow(new SQLException("database unavailable"));
        } else {
            when(dataSource.getConnection()).thenReturn(connection);
        }

        Database database = mock(Database.class);
        when(database.getDataSource()).thenReturn(dataSource);

        MockedStatic<Emulator> emulator = mockStatic(Emulator.class);
        emulator.when(Emulator::getPluginManager).thenReturn(plugins);
        emulator.when(Emulator::getDatabase).thenReturn(database);
        emulator.when(Emulator::getThreading).thenReturn(mock(ThreadPooling.class));
        return emulator;
    }

    @Test
    void keepsTheItemWhenTheOfferRowCannotBeInserted() throws SQLException {
        try (MockedStatic<Emulator> ignored = emulator(null)) {
            assertFalse(MarketPlace.sellItem(client, item, 10));
        }

        verify(client, never()).sendResponse(any(MessageComposer.class));
        verify(client, never()).sendResponse(any(ServerMessage.class));
        verify(inventory, never()).addMarketplaceOffer(any());
        verify(items, never()).removeHabboItem(any(HabboItem.class));
        verify(item, never()).setUserId(anyInt());
    }

    @Test
    void movesTheItemToTheMarketplaceWhenTheOfferRowIsInserted() throws SQLException {
        ResultSet keys = mock(ResultSet.class);
        when(keys.next()).thenReturn(true, false);
        when(keys.getInt(1)).thenReturn(42);

        PreparedStatement statement = mock(PreparedStatement.class);
        when(statement.getGeneratedKeys()).thenReturn(keys);

        Connection connection = mock(Connection.class);
        when(connection.prepareStatement(anyString(), anyInt())).thenReturn(statement);

        try (MockedStatic<Emulator> ignored = emulator(connection)) {
            assertTrue(MarketPlace.sellItem(client, item, 10));
        }

        verify(inventory).addMarketplaceOffer(any());
        verify(items).removeHabboItem(item);
        verify(item).setUserId(-1);
    }
}
