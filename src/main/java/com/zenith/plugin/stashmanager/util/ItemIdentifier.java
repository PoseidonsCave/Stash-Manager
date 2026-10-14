package com.zenith.plugin.stashmanager.util;

import com.zenith.mc.enchantment.EnchantmentRegistry;
import com.zenith.mc.item.ItemData;
import com.zenith.mc.item.ItemRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponents;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.Filterable;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.ItemEnchantments;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.WrittenBookContent;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

// Shared item-ID logic so scan, kit, organize, and retrieve all agree on tool variants.
public final class ItemIdentifier {

    private ItemIdentifier() {}

    public static String getItemId(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return "";

        ItemData data = ItemRegistry.REGISTRY.get(stack.getId());
        String baseId = normalizeItemId(data != null ? data.name() : "unknown_" + stack.getId());

        if ("written_book".equals(baseId)) {
            return baseId + "[book=" + signedBookFingerprint(stack) + "]";
        }
        String enchantments = getNormalizedEnchantments(stack);
        return enchantments == null ? baseId : baseId + "[" + enchantments + "]";
    }

    /** Hash book content so distinct signed books stay distinct without indexing their prose. */
    private static String signedBookFingerprint(ItemStack stack) {
        DataComponents components = stack.getDataComponents();
        WrittenBookContent book = components == null
                ? null : components.get(DataComponentTypes.WRITTEN_BOOK_CONTENT);
        if (book == null) return "unreadable";

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            Filterable<String> title = book.getTitle();
            digestPart(digest, title == null ? null : title.getRaw());
            digestPart(digest, book.getAuthor());
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(book.getGeneration()).array());
            List<Filterable<Component>> pages = book.getPages();
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(pages == null ? -1 : pages.size()).array());
            if (pages != null) {
                for (Filterable<Component> page : pages) {
                    Component raw = page == null ? null : page.getRaw();
                    digestPart(digest, raw == null ? null : GsonComponentSerializer.gson().serialize(raw));
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void digestPart(MessageDigest digest, String value) {
        if (value == null) {
            digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(-1).array());
            return;
        }
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    public static Map<String, Integer> readShulkerContents(ItemStack shulkerStack) {
        Map<String, Integer> contents = new LinkedHashMap<>();
        if (shulkerStack == null || shulkerStack.getAmount() <= 0) return contents;

        try {
            DataComponents components = shulkerStack.getDataComponents();
            if (components == null) return contents;
            Object containerValue = components.get(DataComponentTypes.CONTAINER);
            if (!(containerValue instanceof List<?> containerItems)) return contents;

            for (Object entry : containerItems) {
                Object value = entry instanceof Optional<?> optional ? optional.orElse(null) : entry;
                if (!(value instanceof ItemStack innerStack)) continue;
                if (innerStack == null || innerStack.getId() == 0 || innerStack.getAmount() <= 0) continue;
                contents.merge(getItemId(innerStack), innerStack.getAmount(), Integer::sum);
            }
        } catch (Exception ignored) {
        }

        return contents;
    }

    /** Count physical occupied slots by item type without persisting names or components. */
    public static Map<String, Integer> readShulkerStackSlots(ItemStack shulkerStack) {
        Map<String, Integer> slots = new LinkedHashMap<>();
        if (shulkerStack == null || shulkerStack.getAmount() <= 0) return slots;
        try {
            DataComponents components = shulkerStack.getDataComponents();
            if (components == null) return slots;
            Object value = components.get(DataComponentTypes.CONTAINER);
            if (!(value instanceof List<?> entries)) return slots;
            for (Object entry : entries) {
                Object unwrapped = entry instanceof Optional<?> optional ? optional.orElse(null) : entry;
                if (unwrapped instanceof ItemStack stack && stack.getId() != 0 && stack.getAmount() > 0) {
                    slots.merge(getItemId(stack), 1, Integer::sum);
                }
            }
        } catch (Exception ignored) {
            // A missing count remains unknown; callers must use a conservative fallback.
        }
        return slots;
    }

    // Physical stack count matters during mixed-box admission: 27 non-stackable tools need
    // more unpack room than 27 blocks even though both aggregate to a quantity of 27.
    public static int readShulkerOccupiedSlots(ItemStack shulkerStack) {
        if (shulkerStack == null || shulkerStack.getAmount() <= 0) return 0;

        try {
            DataComponents components = shulkerStack.getDataComponents();
            if (components == null) return 0;
            Object containerValue = components.get(DataComponentTypes.CONTAINER);
            if (!(containerValue instanceof List<?> containerItems)) return 0;

            int occupied = 0;
            for (Object entry : containerItems) {
                Object value = entry instanceof Optional<?> optional ? optional.orElse(null) : entry;
                if (value instanceof ItemStack innerStack
                        && innerStack.getId() != 0
                        && innerStack.getAmount() > 0) {
                    occupied++;
                }
            }
            return Math.min(27, occupied);
        } catch (Exception ignored) {
            return 0;
        }
    }

    // Strips an enchant suffix for keep rules and default storage grouping.
    public static String baseItemId(String itemId) {
        if (itemId == null) return null;
        String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        int bracket = normalized.indexOf('[');
        String base = bracket >= 0 ? normalized.substring(0, bracket) : normalized;
        return base.replace(' ', '_');
    }

    /** Canonical internal key: unprefixed base ID plus an optional sorted semantic variant. */
    public static String normalizeItemId(String itemId) {
        if (itemId == null) return null;
        String normalized = itemId.trim().toLowerCase(Locale.ROOT);
        int bracket = normalized.indexOf('[');
        String base = baseItemId(normalized);
        if (bracket < 0 || !normalized.endsWith("]")) return base;

        String body = normalized.substring(bracket + 1, normalized.length() - 1);
        String variants = java.util.Arrays.stream(body.split(","))
                .map(String::trim)
                .filter(term -> !term.isEmpty())
                .map(ItemIdentifier::normalizeVariantTerm)
                .sorted()
                .distinct()
                .collect(Collectors.joining(","));
        return variants.isEmpty() ? base : base + "[" + variants + "]";
    }

    /** Extract enchantment levels from an observed item key; bare legacy suffixes are unknown. */
    public static Map<String, Integer> enchantmentsInItemId(String itemId) {
        String normalized = normalizeItemId(itemId);
        if (normalized == null) return Map.of();
        int start = normalized.indexOf('[');
        if (start < 0 || !normalized.endsWith("]")) return Map.of();
        Map<String, Integer> enchantments = new TreeMap<>();
        String body = normalized.substring(start + 1, normalized.length() - 1);
        for (String part : body.split(",")) {
            int equals = part.indexOf('=');
            if (equals < 1) continue;
            try {
                int level = Integer.parseInt(part.substring(equals + 1));
                if (level > 0) enchantments.put(part.substring(0, equals), level);
            } catch (NumberFormatException ignored) {
            }
        }
        return Map.copyOf(enchantments);
    }

    public static boolean needsComponentRescan(String itemId) {
        String normalized = normalizeItemId(itemId);
        return normalized != null && normalized.contains("[")
                && normalized.endsWith("]") && !normalized.contains("=");
    }

    /** Names and lore never create a different inventory keep-list item type. */
    public static boolean hasCustomPresentation(ItemStack stack) {
        if (stack == null || stack.getAmount() <= 0) return false;
        DataComponents components = stack.getDataComponents();
        if (components == null) return false;
        return components.get(DataComponentTypes.CUSTOM_NAME) != null
                || components.get(DataComponentTypes.ITEM_NAME) != null
                || components.get(DataComponentTypes.LORE) != null;
    }

    // Physical item identity is exact; storage-class matching lives in StorageClassPolicy.
    public static boolean contentItemIdsMatch(String expected, String actual) {
        if (expected == null || actual == null) return false;
        return normalizeItemId(expected).equals(normalizeItemId(actual));
    }

    private static String getNormalizedEnchantments(ItemStack stack) {
        try {
            DataComponents components = stack.getDataComponents();
            if (components == null) return null;
            Map<String, Integer> normalized = new TreeMap<>();
            collectEnchantments(components.get(DataComponentTypes.ENCHANTMENTS), normalized);
            collectEnchantments(components.get(DataComponentTypes.STORED_ENCHANTMENTS), normalized);
            if (normalized.isEmpty()) return null;
            return normalized.entrySet().stream()
                    .map(entry -> entry.getKey() + "=" + entry.getValue())
                    .collect(Collectors.joining(","));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void collectEnchantments(
            ItemEnchantments enchantments,
            Map<String, Integer> output) {
        if (enchantments == null || enchantments.getEnchantments() == null) return;
        for (var entry : enchantments.getEnchantments().int2IntEntrySet()) {
            if (entry.getIntValue() <= 0) continue;
            var enchantment = EnchantmentRegistry.REGISTRY.get(entry.getIntKey());
            String name = enchantment == null
                    ? "unknown_enchantment_" + entry.getIntKey()
                    : normalizeVariantTerm(enchantment.name());
            output.merge(name, entry.getIntValue(), Math::max);
        }
    }

    private static String normalizeVariantTerm(String term) {
        String normalized = term == null ? "" : term.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("minecraft:")) {
            normalized = normalized.substring("minecraft:".length());
        }
        int equals = normalized.indexOf('=');
        if (equals < 0) return normalized.replace(' ', '_');
        String name = normalized.substring(0, equals).trim().replace(' ', '_');
        String level = normalized.substring(equals + 1).trim();
        return name + "=" + level;
    }
}
