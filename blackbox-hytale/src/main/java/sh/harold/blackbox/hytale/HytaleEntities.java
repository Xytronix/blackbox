package sh.harold.blackbox.hytale;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import sh.harold.blackbox.core.incident.DiagnosticSection;

/**
 * Builds an "Entities" diagnostics section at capture time: per world, NPC counts by role name
 * (top {@value #TYPE_LIMIT}) plus players and a residual, e.g. "Zombie x42, Boar x18, Player x11,
 * Other x230". Counts come from one chunk-level walk of the world's ECS store; read-only and
 * best-effort, so a world mutating mid-walk is skipped rather than breaking capture.
 */
final class HytaleEntities {
    private static final System.Logger LOGGER = System.getLogger(HytaleEntities.class.getName());
    private static final int TYPE_LIMIT = 10;

    private HytaleEntities() {
    }

    /** Returns {@code base} with an "Entities" section appended when any world is countable. */
    static List<DiagnosticSection> appendTo(List<DiagnosticSection> base) {
        Map<String, String> entries = entries();
        if (entries.isEmpty()) {
            return base;
        }
        List<DiagnosticSection> out = new ArrayList<>(base);
        out.add(new DiagnosticSection("Entities", entries));
        return out;
    }

    private static Map<String, String> entries() {
        Map<String, String> out = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        try {
            var npcType = NPCEntity.getComponentType();
            for (Map.Entry<String, World> entry : Universe.get().getWorlds().entrySet()) {
                String name = entry.getKey();
                World world = entry.getValue();
                if (name == null || name.isBlank() || world == null) {
                    continue;
                }
                try {
                    String value = describeWorld(world, npcType);
                    if (value != null) {
                        out.put(name, value);
                    }
                } catch (Throwable t) {
                    LOGGER.log(System.Logger.Level.DEBUG, "Entity count failed for " + name, t);
                }
            }
        } catch (Throwable t) {
            LOGGER.log(System.Logger.Level.DEBUG, "Entity enumeration failed.", t);
        }
        return out;
    }

    private static String describeWorld(World world, ComponentType<EntityStore, NPCEntity> npcType) {
        var store = world.getEntityStore().getStore();
        int total = store.getEntityCount();
        if (total <= 0) {
            return null;
        }
        Map<String, int[]> byRole = new HashMap<>();
        int[] npcTotal = {0};
        store.forEachChunk((chunk, buffer) -> {
            int size = chunk.size();
            for (int i = 0; i < size; i++) {
                NPCEntity npc = chunk.getComponent(i, npcType);
                if (npc == null) {
                    continue;
                }
                String role = npc.getRoleName();
                byRole.computeIfAbsent(role == null || role.isBlank() ? "NPC" : role, k -> new int[1])[0]++;
                npcTotal[0]++;
            }
        });

        List<Map.Entry<String, int[]>> ranked = new ArrayList<>(byRole.entrySet());
        ranked.sort((a, b) -> Integer.compare(b.getValue()[0], a.getValue()[0]));
        StringBuilder value = new StringBuilder(128);
        int shown = 0;
        int shownCount = 0;
        for (Map.Entry<String, int[]> role : ranked) {
            if (shown++ >= TYPE_LIMIT) {
                break;
            }
            if (value.length() > 0) {
                value.append(", ");
            }
            value.append(role.getKey()).append(" x").append(role.getValue()[0]);
            shownCount += role.getValue()[0];
        }
        int foldedNpcs = npcTotal[0] - shownCount;
        if (foldedNpcs > 0) {
            value.append(value.length() > 0 ? ", " : "").append("Other NPC x").append(foldedNpcs);
        }
        int players = playerCount(world);
        if (players > 0) {
            value.append(value.length() > 0 ? ", " : "").append("Player x").append(players);
        }
        int other = total - npcTotal[0] - Math.max(0, players);
        if (other > 0) {
            value.append(value.length() > 0 ? ", " : "").append("Other x").append(other);
        }
        return value.length() == 0 ? null : value.toString();
    }

    private static int playerCount(World world) {
        try {
            return world.getPlayerCount();
        } catch (Throwable t) {
            return 0;
        }
    }
}
