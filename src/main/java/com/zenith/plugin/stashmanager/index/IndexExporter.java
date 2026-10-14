package com.zenith.plugin.stashmanager.index;

import com.zenith.plugin.stashmanager.util.ItemIdentifier;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Map;

// Exports the ContainerIndex to CSV format, one row per item per container.
public class IndexExporter {

    private static final String HEADER =
        "Container X,Container Y,Container Z,Block Type,Double Chest,Item ID,Item Name,Quantity,In Shulker,Shulker Color";

    // Export the entire index to CSV as a byte array for file attachment.
    public static byte[] exportCsv(Collection<ContainerEntry> entries) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream(4096);
        try (PrintWriter pw = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            pw.println(HEADER);

            for (ContainerEntry entry : entries) {
                String posX = String.valueOf(entry.x());
                String posY = String.valueOf(entry.y());
                String posZ = String.valueOf(entry.z());
                String blockType = entry.blockType();
                String isDouble = String.valueOf(entry.isDouble());

                // Direct container items (excluding those inside shulkers)
                for (Map.Entry<String, Integer> item : entry.items().entrySet()) {
                    pw.printf("%s,%s,%s,%s,%s,%s,%s,%d,%s,%s%n",
                        posX, posY, posZ,
                        escapeCsv(blockType), isDouble,
                        escapeCsv(item.getKey()),
                        escapeCsv(toReadableName(item.getKey())),
                        item.getValue(),
                        "false", "");
                }

                // Shulker detail rows
                for (ContainerEntry.ShulkerDetail shulker : entry.shulkerDetails()) {
                    for (Map.Entry<String, Integer> item : shulker.items().entrySet()) {
                        pw.printf("%s,%s,%s,%s,%s,%s,%s,%d,%s,%s%n",
                            posX, posY, posZ,
                            escapeCsv(blockType), isDouble,
                            escapeCsv(item.getKey()),
                            escapeCsv(toReadableName(item.getKey())),
                            item.getValue(),
                            "true",
                            escapeCsv(shulker.color()));
                    }
                }
            }
        }
        return baos.toByteArray();
    }

    // Convert a Minecraft item ID to a human-readable name (title case, no prefix).
    public static String toReadableName(String itemId) {
        String normalized = ItemIdentifier.normalizeItemId(itemId);
        if (normalized == null || normalized.isBlank()) return "Unknown Item";
        if (normalized.startsWith("profile:")) {
            return "Profile · " + titleCase(normalized.substring("profile:".length()));
        }
        String base = ItemIdentifier.baseItemId(normalized);
        String readable = titleCase(base);
        int bracket = normalized.indexOf('[');
        if (bracket < 0 || !normalized.endsWith("]")) return readable;

        String variants = normalized.substring(bracket + 1, normalized.length() - 1);
        String details = java.util.Arrays.stream(variants.split(","))
                .map(IndexExporter::readableVariant)
                .collect(java.util.stream.Collectors.joining(", "));
        return details.isBlank() ? readable : readable + " · " + details;
    }

    private static String readableVariant(String variant) {
        String[] parts = variant.split("=", 2);
        String name = titleCase(parts[0]);
        if (parts.length < 2) return name;
        try {
            return name + " " + romanNumeral(Integer.parseInt(parts[1]));
        } catch (NumberFormatException ignored) {
            return name + " " + parts[1];
        }
    }

    private static String titleCase(String id) {
        String[] words = id.split("_");
        StringBuilder sb = new StringBuilder();
        for (String word : words) {
            if (!word.isEmpty()) {
                if (sb.length() > 0) sb.append(' ');
                sb.append(Character.toUpperCase(word.charAt(0)));
                if (word.length() > 1) sb.append(word.substring(1));
            }
        }
        return sb.toString();
    }

    private static String romanNumeral(int level) {
        return switch (level) {
            case 1 -> "I";
            case 2 -> "II";
            case 3 -> "III";
            case 4 -> "IV";
            case 5 -> "V";
            case 6 -> "VI";
            case 7 -> "VII";
            case 8 -> "VIII";
            case 9 -> "IX";
            case 10 -> "X";
            default -> Integer.toString(level);
        };
    }

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
