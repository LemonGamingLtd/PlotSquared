/*
 * PlotSquared, a land and world management plugin for Minecraft.
 * Copyright (C) IntellectualSites <https://intellectualsites.com>
 * Copyright (C) IntellectualSites team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.plotsquared.bukkit.listener;

import com.plotsquared.bukkit.player.BukkitPlayer;
import com.plotsquared.bukkit.util.BukkitUtil;
import com.plotsquared.core.location.Location;
import com.plotsquared.core.permissions.Permission;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotArea;
import com.plotsquared.core.plot.flag.implementations.EditSignFlag;
import com.plotsquared.core.util.PlotFlagUtil;
import io.papermc.paper.event.player.PlayerOpenSignEvent;
import org.bukkit.block.Sign;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * For events since 1.20.1
 * @since 7.2.1
 */
public class PlayerEventListener1201 implements Listener {

    @EventHandler(ignoreCancelled = true)
    public void onPlayerOpenSignEvent(PlayerOpenSignEvent event) {
        if (!canEditSign(event.getPlayer(), event.getSign(), "edit")) {
            event.setCancelled(true);
        }
    }

    private boolean canEditSign(Player eventPlayer, Sign sign, String action) {
        Location location = BukkitUtil.adapt(sign.getLocation());
        PlotArea area = location.getPlotArea();
        if (area == null) {
            return true;
        }
        Plot plot = location.getOwnedPlot();
        if (plot == null) {
            if (PlotFlagUtil.isAreaRoadFlagsAndFlagEquals(area, EditSignFlag.class, false)
                    && !eventPlayer.hasPermission(Permission.PERMISSION_ADMIN_INTERACT_ROAD.toString())) {
                return false;
            }
            return true;
        }
        BukkitPlayer player = BukkitUtil.adapt(eventPlayer);
        if (plot.isAdded(player.getUUID())) {
            return true; // allow for added players
        }
        if (!plot.getFlag(EditSignFlag.class)
                && !eventPlayer.hasPermission(Permission.PERMISSION_ADMIN_INTERACT_OTHER.toString())) {
            plot.debug(eventPlayer.getName() + " could not " + action + " the sign because of edit-sign = false");
            return false;
        }
        return true;
    }

}
