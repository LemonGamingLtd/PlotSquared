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
package com.plotsquared.core.command;

import com.google.inject.Inject;
import com.plotsquared.core.configuration.caption.TranslatableCaption;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.Plot;
import com.plotsquared.core.plot.PlotArea;
import com.plotsquared.core.plot.PlotId;
import com.plotsquared.core.plot.world.PlotAreaManager;
import com.plotsquared.core.util.MathMan;
import com.plotsquared.core.util.WorldUtil;
import com.plotsquared.core.util.task.TaskManager;
import com.plotsquared.core.util.task.TaskTime;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@CommandDeclaration(command = "condense",
        permission = "plots.admin",
        usage = "/plot condense <area> <start|stop|info> [radius]",
        category = CommandCategory.ADMINISTRATION,
        requiredType = RequiredType.CONSOLE)
public class Condense extends SubCommand {

    public static boolean TASK = false;

    private final PlotAreaManager plotAreaManager;
    private final WorldUtil worldUtil;

    @Inject
    public Condense(
            final @NonNull PlotAreaManager plotAreaManager,
            final @NonNull WorldUtil worldUtil
    ) {
        this.plotAreaManager = plotAreaManager;
        this.worldUtil = worldUtil;
    }

    @SuppressWarnings("unchecked")
    @Override
    public boolean onCommand(final PlotPlayer<?> player, String[] args) {
        if (args.length != 2 && args.length != 3) {
            player.sendMessage(
                    TranslatableCaption.of("commandconfig.command_syntax"),
                    TagResolver.resolver("value", Tag.inserting(Component.text(
                            "/plot condense <area> <start | stop | info> [radius]"
                    )))
            );
            return false;
        }
        PlotArea area = this.plotAreaManager.getPlotAreaByString(args[0]);
        if (area == null || !this.worldUtil.isWorld(area.getWorldName())) {
            player.sendMessage(TranslatableCaption.of("invalid.invalid_area"));
            return false;
        }
        switch (args[1].toLowerCase()) {
            case "start" -> {
                if (args.length == 2) {
                    player.sendMessage(
                            TranslatableCaption.of("commandconfig.command_syntax"),
                            TagResolver.resolver(
                                    "value",
                                    Tag.inserting(Component.text("/plot condense" + area + " start " + "<radius>"))
                            )
                    );
                    return false;
                }
                if (Condense.TASK) {
                    player.sendMessage(TranslatableCaption.of("condense.task_already_started"));
                    return false;
                }
                if (!MathMan.isInteger(args[2])) {
                    player.sendMessage(TranslatableCaption.of("condense.invalid_radius"));
                    return false;
                }
                int radius = Integer.parseInt(args[2]);

                final Collection<Plot> claimedPlots = area.getPlots();
                int size = claimedPlots.size();
                int minimumRadius = (int) Math.ceil(Math.sqrt(size) / 2 + 1);
                if (radius < minimumRadius) {
                    player.sendMessage(TranslatableCaption.of("condense.radius_too_small"));
                    return false;
                }
                final ArrayList<Plot> allPlots = getOrderedBasePlots(claimedPlots);
                allPlots.removeIf(plot -> !isOutsideRadius(plot, radius));

                final List<PlotId> free = getFreePlots(area, radius);
                if (free.isEmpty() || allPlots.isEmpty()) {
                    player.sendMessage(TranslatableCaption.of("condense.no_free_plots_found"));
                    return false;
                }
                player.sendMessage(TranslatableCaption.of("condense.task_started"));
                Condense.TASK = true;
                Runnable run = new Runnable() {
                    @Override
                    public void run() {
                        if (!Condense.TASK) {
                            player.sendMessage(TranslatableCaption.of("debugexec.task_cancelled"));
                        }
                        if (allPlots.isEmpty()) {
                            Condense.TASK = false;
                            player.sendMessage(TranslatableCaption.of("condense.task_complete"));
                            return;
                        }
                        final Runnable task = this;
                        final Plot origin = allPlots.remove(0);
                        final String originId = origin.toString();
                        if (!isWithinSafeEditRegion(origin)) {
                            player.sendMessage(
                                    TranslatableCaption.of("condense.skipping"),
                                    TagResolver.resolver(
                                            "plot",
                                            Tag.inserting(Component.text(originId + " (outside safe edit region)"))
                                    )
                            );
                            TaskManager.runTaskLater(task, TaskTime.ticks(1L));
                            return;
                        }
                        int i = 0;
                        while (free.size() > i) {
                            final Plot possible = origin.getArea().getPlotAbs(free.get(i));
                            if (possible == null || possible.hasOwner()) {
                                free.remove(i);
                                continue;
                            }
                            if (!canMoveToRadius(origin, possible, radius)) {
                                i++;
                                continue;
                            }
                            i++;
                            final String destinationId = possible.toString();
                            final AtomicBoolean result = new AtomicBoolean(false);
                            try {
                                result.set(origin.getPlotModificationManager().move(possible, player, () -> {
                                    if (result.get()) {
                                        markDestinationOccupied(origin, possible, free);
                                        addFreedPlots(origin, radius, free);
                                        player.sendMessage(
                                                TranslatableCaption.of("condense.moving"),
                                                TagResolver.builder()
                                                        .tag("origin", Tag.inserting(Component.text(originId)))
                                                        .tag("possible", Tag.inserting(Component.text(destinationId)))
                                                        .build()
                                        );
                                        TaskManager.runTaskLater(task, TaskTime.ticks(1L));
                                    }
                                }, false).get());
                            } catch (InterruptedException | ExecutionException e) {
                                e.printStackTrace();
                            }
                            if (result.get()) {
                                break;
                            }
                        }
                        if (free.isEmpty()) {
                            Condense.TASK = false;
                            player.sendMessage(TranslatableCaption.of("condense.task_failed"));
                            return;
                        }
                        if (i >= free.size()) {
                            player.sendMessage(
                                    TranslatableCaption.of("condense.skipping"),
                                    TagResolver.resolver("plot", Tag.inserting(Component.text(origin.toString())))
                            );
                            TaskManager.runTaskLater(task, TaskTime.ticks(1L));
                        }
                    }
                };
                TaskManager.runTaskAsync(run);
                return true;
            }
            case "stop" -> {
                if (!Condense.TASK) {
                    player.sendMessage(TranslatableCaption.of("condense.task_stopped"));
                    return false;
                }
                Condense.TASK = false;
                player.sendMessage(TranslatableCaption.of("condense.task_stopped"));
                return true;
            }
            case "info" -> {
                if (args.length == 2) {
                    player.sendMessage(
                            TranslatableCaption.of("commandconfig.command_syntax"),
                            TagResolver.resolver(
                                    "value",
                                    Tag.inserting(Component.text("/plot condense " + area + " info <radius>"))
                            )
                    );
                    return false;
                }
                if (!MathMan.isInteger(args[2])) {
                    player.sendMessage(TranslatableCaption.of("condense.invalid_radius"));
                    return false;
                }
                int radius = Integer.parseInt(args[2]);
                Collection<Plot> plots = area.getPlots();
                int size = plots.size();
                int minimumRadius = (int) Math.ceil(Math.sqrt(size) / 2 + 1);
                if (radius < minimumRadius) {
                    player.sendMessage(TranslatableCaption.of("condense.radius_too_small"));
                    return false;
                }
                int maxMove = getPlots(plots, minimumRadius).size();
                int userMove = getPlots(plots, radius).size();
                player.sendMessage(TranslatableCaption.of("condense.default_eval"));
                player.sendMessage(
                        TranslatableCaption.of("condense.minimum_radius"),
                        TagResolver.resolver("minimum_radius", Tag.inserting(Component.text(minimumRadius)))
                );
                player.sendMessage(
                        TranslatableCaption.of("condense.maximum_moved"),
                        TagResolver.resolver("maximum_moves", Tag.inserting(Component.text(maxMove)))
                );
                player.sendMessage(TranslatableCaption.of("condense.input_eval"));
                player.sendMessage(
                        TranslatableCaption.of("condense.input_radius"),
                        TagResolver.resolver("radius", Tag.inserting(Component.text(radius)))
                );
                player.sendMessage(
                        TranslatableCaption.of("condense.estimated_moves"),
                        TagResolver.resolver("user_move", Tag.inserting(Component.text(userMove)))
                );
                player.sendMessage(TranslatableCaption.of("condense.eta"));
                player.sendMessage(TranslatableCaption.of("condense.radius_measured"));
                return true;
            }
        }
        player.sendMessage(
                TranslatableCaption.of("commandconfig.command_syntax"),
                TagResolver.resolver(
                        "value",
                        Tag.inserting(Component.text("/plot condense " + area.getWorldName() + " <start | stop | info> [radius]"))
                )
        );
        return false;
    }

    public Set<PlotId> getPlots(Collection<Plot> plots, int radius) {
        HashSet<PlotId> outside = new HashSet<>();
        for (Plot plot : plots) {
            if (isOutsideRadius(plot.getId(), radius)) {
                outside.add(plot.getId());
            }
        }
        return outside;
    }

    private ArrayList<Plot> getOrderedBasePlots(final Collection<Plot> plots) {
        final ArrayList<Plot> basePlots = new ArrayList<>();
        for (final Plot plot : plots) {
            if (plot.isBasePlot()) {
                basePlots.add(plot);
            }
        }
        basePlots.sort(Comparator.comparingInt((Plot plot) -> plot.getConnectedPlots().size()).reversed());
        return basePlots;
    }

    private List<PlotId> getFreePlots(final PlotArea area, final int radius) {
        final List<PlotId> free = new ArrayList<>();
        PlotId current = PlotId.of(0, 0);
        while (current.getX() <= radius && current.getY() <= radius) {
            final Plot plot = area.getPlotAbs(current);
            if (plot != null && !plot.hasOwner()) {
                free.add(plot.getId());
            }
            current = current.getNextId();
        }
        return free;
    }

    private boolean canMoveToRadius(final Plot origin, final Plot destination, final int radius) {
        final PlotId offset = PlotId.of(
                destination.getId().getX() - origin.getId().getX(),
                destination.getId().getY() - origin.getId().getY()
        );
        for (final Plot connected : origin.getConnectedPlots()) {
            final PlotId translated = PlotId.of(
                    connected.getId().getX() + offset.getX(),
                    connected.getId().getY() + offset.getY()
            );
            if (isOutsideRadius(translated, radius)) {
                return false;
            }
            final Plot translatedPlot = origin.getArea().getPlotAbs(translated);
            if (translatedPlot == null) {
                return false;
            }
        }
        return true;
    }

    private void markDestinationOccupied(final Plot origin, final Plot destination, final List<PlotId> free) {
        final PlotId offset = PlotId.of(
                destination.getId().getX() - origin.getId().getX(),
                destination.getId().getY() - origin.getId().getY()
        );
        for (final Plot connected : origin.getConnectedPlots()) {
            free.remove(PlotId.of(
                    connected.getId().getX() + offset.getX(),
                    connected.getId().getY() + offset.getY()
            ));
        }
    }

    private void addFreedPlots(final Plot origin, final int radius, final List<PlotId> free) {
        for (final Plot connected : origin.getConnectedPlots()) {
            final PlotId id = connected.getId();
            if (!isOutsideRadius(id, radius) && !free.contains(id)) {
                free.add(id);
            }
        }
    }

    private boolean isOutsideRadius(final Plot plot, final int radius) {
        for (final Plot connected : plot.getConnectedPlots()) {
            if (isOutsideRadius(connected.getId(), radius)) {
                return true;
            }
        }
        return false;
    }

    private boolean isOutsideRadius(final PlotId id, final int radius) {
        return id.getX() > radius || id.getX() < -radius || id.getY() > radius || id.getY() < -radius;
    }

    private boolean isWithinSafeEditRegion(final Plot plot) {
        final com.plotsquared.core.location.Location[] corners = plot.getCorners();
        return WorldUtil.isValidLocation(corners[0]) && WorldUtil.isValidLocation(corners[1]);
    }

}
