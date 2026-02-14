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
package com.plotsquared.core.configuration.caption;

import com.plotsquared.core.configuration.Settings;
import com.plotsquared.core.player.PlotPlayer;
import com.plotsquared.core.plot.flag.PlotFlag;
import com.plotsquared.core.plot.flag.implementations.DescriptionFlag;
import com.plotsquared.core.plot.flag.implementations.FarewellFlag;
import com.plotsquared.core.plot.flag.implementations.GreetingFlag;
import com.plotsquared.core.plot.flag.implementations.PlotTitleFlag;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.ParsingException;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.plotsquared.core.configuration.caption.ComponentTransform.nested;
import static com.plotsquared.core.configuration.caption.ComponentTransform.stripClicks;

public class CaptionUtility {

    private static final Pattern LEGACY_FORMATTING = Pattern.compile("§[a-gklmnor0-9]");
    private static final Pattern LEGACY_AMPERSAND = Pattern.compile("&([0-9a-fk-or])", Pattern.CASE_INSENSITIVE);

    private static final Map<Character, String> LEGACY_TO_MINIMESSAGE = Map.ofEntries(
            Map.entry('0', "<black>"),
            Map.entry('1', "<dark_blue>"),
            Map.entry('2', "<dark_green>"),
            Map.entry('3', "<dark_aqua>"),
            Map.entry('4', "<dark_red>"),
            Map.entry('5', "<dark_purple>"),
            Map.entry('6', "<gold>"),
            Map.entry('7', "<gray>"),
            Map.entry('8', "<dark_gray>"),
            Map.entry('9', "<blue>"),
            Map.entry('a', "<green>"),
            Map.entry('b', "<aqua>"),
            Map.entry('c', "<red>"),
            Map.entry('d', "<light_purple>"),
            Map.entry('e', "<yellow>"),
            Map.entry('f', "<white>"),
            Map.entry('k', "<obfuscated>"),
            Map.entry('l', "<bold>"),
            Map.entry('m', "<strikethrough>"),
            Map.entry('n', "<underlined>"),
            Map.entry('o', "<italic>"),
            Map.entry('r', "<reset>")
    );

    // flags which values are parsed by minimessage
    private static final Set<Class<? extends PlotFlag<?, ?>>> MINI_MESSAGE_FLAGS = Set.of(
            GreetingFlag.class,
            FarewellFlag.class,
            DescriptionFlag.class,
            PlotTitleFlag.class
    );

    private static final ComponentTransform CLICK_STRIP_TRANSFORM = nested(
            stripClicks(
                    Settings.Chat.CLICK_EVENT_ACTIONS_TO_REMOVE.stream()
                            .map(ClickEvent.Action::valueOf)
                            .toArray(ClickEvent.Action[]::new)
            )
    );


    /**
     * Converts legacy Minecraft ampersand color codes (e.g., &amp;b, &amp;f) to MiniMessage format.
     * This allows users to use traditional Minecraft formatting codes in flags like greeting and farewell.
     *
     * @param message The message containing legacy ampersand color codes
     * @return The message with legacy codes converted to MiniMessage tags
     * @since 7.3.14
     */
    public static @NonNull String legacyToMiniMessage(final @NonNull String message) {
        java.util.regex.Matcher matcher = LEGACY_AMPERSAND.matcher(message);
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            char code = Character.toLowerCase(matcher.group(1).charAt(0));
            String replacement = LEGACY_TO_MINIMESSAGE.getOrDefault(code, matcher.group());
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    /**
     * Format a chat message but keep the formatting keys
     *
     * @param recipient Message recipient
     * @param message   Message
     * @return Formatted message
     */
    public static String formatRaw(PlotPlayer<?> recipient, String message) {
        final ChatFormatter.ChatContext chatContext =
                new ChatFormatter.ChatContext(recipient, message, true);
        for (final ChatFormatter chatFormatter : ChatFormatter.formatters) {
            chatFormatter.format(chatContext);
        }
        return chatContext.getMessage();
    }

    /**
     * Format a chat message
     *
     * @param recipient Message recipient
     * @param message   Message
     * @return Formatted message
     */
    public static String format(
            final @Nullable PlotPlayer<?> recipient,
            final @NonNull String message
    ) {
        final ChatFormatter.ChatContext chatContext =
                new ChatFormatter.ChatContext(recipient, message, false);
        for (final ChatFormatter chatFormatter : ChatFormatter.formatters) {
            chatFormatter.format(chatContext);
        }
        return chatContext.getMessage();
    }

    /**
     * Strips configured click events from a MiniMessage string.
     *
     * @param miniMessageString the message from which the specified click events should be removed from.
     * @return the string without the click events that are configured to be removed.
     * @see Settings.Chat#CLICK_EVENT_ACTIONS_TO_REMOVE
     * @since 6.0.10
     */
    public static String stripClickEvents(final @NonNull String miniMessageString) {
        // parse, transform and serialize again
        Component component;
        try {
            component = MiniMessage.miniMessage().deserialize(miniMessageString);
        } catch (ParsingException e) {
            // if the String cannot be parsed, we try stripping legacy colors
            String legacyStripped = LEGACY_FORMATTING.matcher(miniMessageString).replaceAll("");
            component = MiniMessage.miniMessage().deserialize(legacyStripped);
        }
        component = CLICK_STRIP_TRANSFORM.transform(component);
        return MiniMessage.miniMessage().serialize(component);
    }

    /**
     * Strips configured MiniMessage click events from a plot flag value.
     * This is used before letting the string be parsed by the plot flag.
     * This method works the same way as {@link #stripClickEvents(String)} but will only
     * strip click events from messages that target flags that are meant to contain MiniMessage strings.
     *
     * @param flag              the flag the message is targeted for.
     * @param miniMessageString the message from which the specified click events should be removed from.
     * @return the string without the click events that are configured to be removed.
     * @see Settings.Chat#CLICK_EVENT_ACTIONS_TO_REMOVE
     * @see #stripClickEvents(String)
     * @since 6.0.10
     */
    public static String stripClickEvents(
            final @NonNull PlotFlag<?, ?> flag,
            final @NonNull String miniMessageString
    ) {
        if (MINI_MESSAGE_FLAGS.contains(flag.getClass())) {
            return stripClickEvents(miniMessageString);
        }
        return miniMessageString;
    }

}
