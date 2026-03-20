package com.zenith.plugin.stashmanager.index;

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
        String base = itemId;
        if (base.startsWith("minecraft:")) {
            base = base.substring("minecraft:".length());
        }
        String[] words = base.split("_");
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

    private static String escapeCsv(String value) {
        if (value == null) return "";
        if (value.contains(",") || value.contains("\"") || value.contains("\n")) {
            return "\"" + value.replace("\"", "\"\"") + "\"";
        }
        return value;
    }
}
