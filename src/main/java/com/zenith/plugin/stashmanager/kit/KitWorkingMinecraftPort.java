package com.zenith.plugin.stashmanager.kit;

import com.zenith.Proxy;
import com.zenith.cache.data.inventory.Container;
import com.zenith.feature.inventory.InventoryActionRequest;
import com.zenith.feature.inventory.actions.ClickItem;
import com.zenith.feature.inventory.actions.CloseContainer;
import com.zenith.plugin.stashmanager.organizer.PlaceCursorStackInEmptySlot;
import com.zenith.feature.inventory.actions.ShiftClick;
import com.zenith.feature.pathfinder.goals.GoalGetToBlock;
import com.zenith.feature.player.World;
import com.zenith.mc.block.BlockPos;
import com.zenith.plugin.stashmanager.orchestration.ContainerApproach;
import com.zenith.plugin.stashmanager.orchestration.ContainerAccessPlanner;
import com.zenith.plugin.stashmanager.util.ItemIdentifier;
import com.zenith.util.RequestFuture;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.game.item.component.DataComponentTypes;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import static com.zenith.Globals.BARITONE;
import static com.zenith.Globals.CACHE;
import static com.zenith.Globals.CONFIG;
import static com.zenith.Globals.INVENTORY;

/** Live Zenith bridge for one intact, single-item shulker transfer. No click is retried. */
public final class KitWorkingMinecraftPort implements KitWorkingTransferExecutor.Port,
        KitWorkingFillPreparation.Probe, KitWorkingReservationPreparation.Probe,
        KitWorkingRecoveryPreparation.Port {
    private static final int MAX_WINDOW_TICKS = 6000;
    private int[] target;
    private volatile int contentContainerId = -1;
    private boolean openIssued;
    private int targetTicks;
    private int quietTicks;
    private int openContainerId = -1;
    private int sourceContainerId = -1;
    private int destinationContainerId = -1;
    private int cargoRawSlot = -1;
    private RequestFuture clickFuture;
    private List<String> sourceBefore;
    private List<String> destinationBefore;
    private boolean reopenRequired;
    private int closeIssuedForId = -1;
    private int closeWaitTicks;
    private boolean navigationGuard;
    private boolean savedAllowBreak;
    private boolean savedAllowPlace;
    private final BooleanSupplier exclusiveAutomationOwner;
    private List<String> probeSnapshot;
    private int probeStableTicks;

    public KitWorkingMinecraftPort(BooleanSupplier exclusiveAutomationOwner) {
        this.exclusiveAutomationOwner = Objects.requireNonNull(exclusiveAutomationOwner);
    }

    @Override public void onContainerData(int containerId) {
        if (openIssued && containerId > 0) contentContainerId = containerId;
    }

    /** Observe a source in a server-backed window. This path sends no inventory click. */
    public ProbeResult probeSource(KitWorkingSourceAssignmentPlan.Assignment assignment) {
        var ready = readyWindow(assignment.source());
        if (ready != KitWorkingTransferExecutor.Check.MATCH)
            return new ProbeResult(ready, null, "source_window_unavailable");
        Container open = openWindow();
        int slots = chestSlots(open);
        if (assignment.sourceSlot() >= slots || !clearCursor() || firstFreePlayerRawSlot() < 0)
            return new ProbeResult(KitWorkingTransferExecutor.Check.MISMATCH, null,
                    "source_slot_or_inventory_unavailable");
        var destination = assignment.destination();
        ItemStack box = open.getItemStack(assignment.sourceSlot());
        if (!boxShapeMatches(box, destination.itemId(), destination.plannedUnits()))
            return new ProbeResult(KitWorkingTransferExecutor.Check.MISMATCH, null,
                    "source_box_contents_mismatch");
        List<String> now = snapshot(open, slots);
        if (now == null) return new ProbeResult(KitWorkingTransferExecutor.Check.MISMATCH,
                null, "source_snapshot_unavailable");
        if (!now.equals(probeSnapshot)) {
            probeSnapshot = now;
            probeStableTicks = 1;
            return new ProbeResult(KitWorkingTransferExecutor.Check.WAIT, null,
                    "source_window_settling");
        }
        if (++probeStableTicks < 3)
            return new ProbeResult(KitWorkingTransferExecutor.Check.WAIT, null,
                    "source_window_settling");
        return new ProbeResult(KitWorkingTransferExecutor.Check.MATCH,
                KitVariantFingerprint.canonicalVariant(box), null);
    }

    /** Server-window proof that every slot of a selected double chest is empty. */
    public ProbeResult probeEmptyDoubleChest(int[] position) {
        var ready = readyWindow(position);
        if (ready != KitWorkingTransferExecutor.Check.MATCH)
            return new ProbeResult(ready, null, "destination_window_unavailable");
        Container open = openWindow();
        if (chestSlots(open) != 54 || !clearCursor())
            return new ProbeResult(KitWorkingTransferExecutor.Check.MISMATCH, null,
                    "destination_is_not_an_empty_double_chest");
        for (int slot = 0; slot < 54; slot++) {
            if (!isEmpty(open.getItemStack(slot)))
                return new ProbeResult(KitWorkingTransferExecutor.Check.MISMATCH, null,
                        "destination_is_not_an_empty_double_chest");
        }
        List<String> now = snapshot(open, 54);
        if (now == null) return new ProbeResult(KitWorkingTransferExecutor.Check.MISMATCH,
                null, "destination_snapshot_unavailable");
        if (!now.equals(probeSnapshot)) {
            probeSnapshot = now;
            probeStableTicks = 1;
            return new ProbeResult(KitWorkingTransferExecutor.Check.WAIT, null,
                    "destination_window_settling");
        }
        if (++probeStableTicks < 3)
            return new ProbeResult(KitWorkingTransferExecutor.Check.WAIT, null,
                    "destination_window_settling");
        return new ProbeResult(KitWorkingTransferExecutor.Check.MATCH, null, null);
    }

    public KitWorkingTransferExecutor.Check settleClosed() {
        return closeWindow() ? KitWorkingTransferExecutor.Check.MATCH
                : KitWorkingTransferExecutor.Check.WAIT;
    }

    public record ProbeResult(KitWorkingTransferExecutor.Check check, String fingerprint,
                              String blocker) { }

    public record TransferObservation(KitWorkingTransferExecutor.Check check,
                                      boolean exactAtSlot, int playerMatches, String blocker) { }

    /** Read-only recovery observation; surrounding slots are stable before it returns. */
    public TransferObservation probeTransferSlot(KitWorkingTransferJournal.Ticket ticket,
                                                 boolean destination) {
        int[] position = destination ? ticket.destination() : ticket.source();
        int slot = destination ? ticket.destinationSlot() : ticket.sourceSlot();
        var ready = readyWindow(position);
        if (ready != KitWorkingTransferExecutor.Check.MATCH)
            return new TransferObservation(ready, false, -1, "recovery_window_unavailable");
        Container open = openWindow();
        int slots = chestSlots(open);
        if (slot >= slots || !clearCursor())
            return new TransferObservation(KitWorkingTransferExecutor.Check.MISMATCH,
                    false, -1, "recovery_window_or_cursor_mismatch");
        ItemStack observed = open.getItemStack(slot);
        boolean exact = boxMatches(observed, ticket);
        if (!exact && !isEmpty(observed))
            return new TransferObservation(KitWorkingTransferExecutor.Check.MISMATCH,
                    false, -1, "recovery_target_slot_changed");
        int carried = countPlayerBoxes(ticket);
        if (carried < 0 || carried > 1)
            return new TransferObservation(KitWorkingTransferExecutor.Check.MISMATCH,
                    exact, carried, "recovery_cargo_ambiguous");
        List<String> now = snapshot(open, slots);
        if (now == null)
            return new TransferObservation(KitWorkingTransferExecutor.Check.MISMATCH,
                    exact, carried, "recovery_snapshot_unavailable");
        if (!now.equals(probeSnapshot)) {
            probeSnapshot = now;
            probeStableTicks = 1;
            return new TransferObservation(KitWorkingTransferExecutor.Check.WAIT,
                    exact, carried, "recovery_window_settling");
        }
        if (++probeStableTicks < 3)
            return new TransferObservation(KitWorkingTransferExecutor.Check.WAIT,
                    exact, carried, "recovery_window_settling");
        return new TransferObservation(KitWorkingTransferExecutor.Check.MATCH,
                exact, carried, null);
    }

    public KitWorkingTransferExecutor.Check closeForRecoveryReopen() {
        if (!closeWindow()) return KitWorkingTransferExecutor.Check.WAIT;
        target = null;
        openIssued = false;
        contentContainerId = -1;
        openContainerId = -1;
        probeSnapshot = null;
        probeStableTicks = 0;
        quietTicks = 0;
        return KitWorkingTransferExecutor.Check.MATCH;
    }

    public boolean primeRecoveredCargo(KitWorkingTransferJournal.Ticket ticket) {
        cargoRawSlot = uniquePlayerBoxSlot(ticket);
        return cargoRawSlot >= 9;
    }

    @Override public TransferObservation observe(KitWorkingTransferJournal.Ticket ticket,
                                                 boolean destination) {
        return probeTransferSlot(ticket, destination);
    }

    @Override public KitWorkingTransferExecutor.Check closeAndReset() {
        return closeForRecoveryReopen();
    }

    @Override public boolean primeCargo(KitWorkingTransferJournal.Ticket ticket) {
        return primeRecoveredCargo(ticket);
    }

    @Override public ProbeResult inspect(KitWorkingSourceAssignmentPlan.Assignment assignment) {
        return probeSource(assignment);
    }

    @Override public ProbeResult inspect(int[] position) {
        return probeEmptyDoubleChest(position);
    }

    @Override public KitWorkingTransferExecutor.Check close() { return settleClosed(); }

    @Override public KitWorkingTransferExecutor.Check verifySource(
            KitWorkingTransferJournal.Ticket ticket) {
        var ready = readyWindow(ticket.source());
        if (ready != KitWorkingTransferExecutor.Check.MATCH) return ready;
        Container open = openWindow();
        int slots = chestSlots(open);
        if (ticket.sourceSlot() >= slots || !clearCursor()
                || !boxMatches(open.getItemStack(ticket.sourceSlot()), ticket)
                || countPlayerBoxes(ticket) != 0 || firstFreePlayerRawSlot() < 0) {
            return KitWorkingTransferExecutor.Check.MISMATCH;
        }
        List<String> observed = snapshot(open, slots);
        if (observed == null) return KitWorkingTransferExecutor.Check.MISMATCH;
        if (sourceBefore != null && !sourceBefore.equals(observed)) {
            return KitWorkingTransferExecutor.Check.MISMATCH;
        }
        sourceBefore = observed;
        sourceContainerId = openContainerId;
        return KitWorkingTransferExecutor.Check.MATCH;
    }

    @Override public void submitPickup(KitWorkingTransferJournal.Ticket ticket) {
        if (verifySource(ticket) != KitWorkingTransferExecutor.Check.MATCH
                || openContainerId != sourceContainerId || INVENTORY.hasActiveRequest()) {
            throw new IllegalStateException("source changed after pickup intent was journaled");
        }
        clickFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                .priority(6000).actions(new ShiftClick(openContainerId,
                        ticket.sourceSlot(), ShiftClickItemAction.LEFT_CLICK)).build());
    }

    @Override public KitWorkingTransferExecutor.Check verifyCargo(
            KitWorkingTransferJournal.Ticket ticket) {
        if (clickFuture == null || !clickFuture.isDone()) return KitWorkingTransferExecutor.Check.WAIT;
        if (!clickFuture.getNow() || !safe() || !clearCursor()
                || openContainerId != sourceContainerId) return KitWorkingTransferExecutor.Check.MISMATCH;
        Container open = openWindow();
        if (open == null || chestSlots(open) <= ticket.sourceSlot())
            return KitWorkingTransferExecutor.Check.MISMATCH;
        List<String> after = snapshot(open, chestSlots(open));
        if (after == null || sourceBefore == null || after.size() != sourceBefore.size()
                || !isEmpty(open.getItemStack(ticket.sourceSlot())))
            return KitWorkingTransferExecutor.Check.MISMATCH;
        for (int i = 0; i < after.size(); i++) {
            if (i != ticket.sourceSlot() && !after.get(i).equals(sourceBefore.get(i)))
                return KitWorkingTransferExecutor.Check.MISMATCH;
        }
        cargoRawSlot = uniquePlayerBoxSlot(ticket);
        return cargoRawSlot >= 9 ? KitWorkingTransferExecutor.Check.MATCH
                : KitWorkingTransferExecutor.Check.MISMATCH;
    }

    @Override public KitWorkingTransferExecutor.Check verifyDestination(
            KitWorkingTransferJournal.Ticket ticket) {
        var ready = readyWindow(ticket.destination());
        if (ready != KitWorkingTransferExecutor.Check.MATCH) return ready;
        Container open = openWindow();
        int slots = chestSlots(open);
        if (ticket.destinationSlot() >= slots || !isEmpty(open.getItemStack(ticket.destinationSlot()))
                || !clearCursor() || uniquePlayerBoxSlot(ticket) != cargoRawSlot)
            return KitWorkingTransferExecutor.Check.MISMATCH;
        List<String> observed = snapshot(open, slots);
        if (observed == null) return KitWorkingTransferExecutor.Check.MISMATCH;
        if (destinationBefore != null && !destinationBefore.equals(observed))
            return KitWorkingTransferExecutor.Check.MISMATCH;
        destinationBefore = observed;
        destinationContainerId = openContainerId;
        return KitWorkingTransferExecutor.Check.MATCH;
    }

    @Override public void submitDeposit(KitWorkingTransferJournal.Ticket ticket) {
        if (verifyDestination(ticket) != KitWorkingTransferExecutor.Check.MATCH
                || openContainerId != destinationContainerId || INVENTORY.hasActiveRequest())
            throw new IllegalStateException("destination changed after deposit intent was journaled");
        int sourceWindowSlot = rawToWindow(cargoRawSlot, chestSlots(openWindow()));
        if (!boxMatches(openWindow().getItemStack(sourceWindowSlot), ticket))
            throw new IllegalStateException("carried box changed before deposit click");
        clickFuture = INVENTORY.submit(InventoryActionRequest.builder().owner(this)
                .priority(6000).actions(
                        new ClickItem(openContainerId, sourceWindowSlot, ClickItemAction.LEFT_CLICK),
                        new PlaceCursorStackInEmptySlot(openContainerId, ticket.destinationSlot()))
                .build());
    }

    @Override public KitWorkingTransferExecutor.Check verifyDeposit(
            KitWorkingTransferJournal.Ticket ticket) {
        if (clickFuture == null || !clickFuture.isDone()) return KitWorkingTransferExecutor.Check.WAIT;
        if (!clickFuture.getNow() || !safe() || !clearCursor()
                || openContainerId != destinationContainerId || countPlayerBoxes(ticket) != 0)
            return KitWorkingTransferExecutor.Check.MISMATCH;
        Container open = openWindow();
        if (open == null || chestSlots(open) <= ticket.destinationSlot()
                || !boxMatches(open.getItemStack(ticket.destinationSlot()), ticket)
                || !unchangedExceptDestination(snapshot(open, chestSlots(open)), ticket.destinationSlot()))
            return KitWorkingTransferExecutor.Check.MISMATCH;
        reopenRequired = true;
        return KitWorkingTransferExecutor.Check.MATCH;
    }

    @Override public KitWorkingTransferExecutor.Check verifyPersistedDeposit(
            KitWorkingTransferJournal.Ticket ticket) {
        if (reopenRequired) {
            if (!closeWindow()) return KitWorkingTransferExecutor.Check.WAIT;
            reopenRequired = false;
            target = null;
            contentContainerId = -1;
            openContainerId = -1;
            openIssued = false;
            quietTicks = 0;
            return KitWorkingTransferExecutor.Check.WAIT;
        }
        var ready = readyWindow(ticket.destination());
        if (ready != KitWorkingTransferExecutor.Check.MATCH) return ready;
        Container open = openWindow();
        int slots = chestSlots(open);
        return slots > ticket.destinationSlot() && clearCursor()
                && countPlayerBoxes(ticket) == 0
                && boxMatches(open.getItemStack(ticket.destinationSlot()), ticket)
                && unchangedExceptDestination(snapshot(open, slots), ticket.destinationSlot())
                ? KitWorkingTransferExecutor.Check.MATCH
                : KitWorkingTransferExecutor.Check.MISMATCH;
    }

    @Override public void shutdown() {
        BARITONE.stop();
        if (navigationGuard) {
            CONFIG.client.extra.pathfinder.allowBreak = savedAllowBreak;
            CONFIG.client.extra.pathfinder.allowPlace = savedAllowPlace;
            navigationGuard = false;
        }
    }

    private KitWorkingTransferExecutor.Check readyWindow(int[] position) {
        if (!safe() || !clearCursor()) return KitWorkingTransferExecutor.Check.MISMATCH;
        if (target != null && !Arrays.equals(target, position)) {
            if (!closeWindow()) return KitWorkingTransferExecutor.Check.WAIT;
            target = null;
            quietTicks = 0;
        }
        if (target == null) {
            if (++quietTicks < 3) return KitWorkingTransferExecutor.Check.WAIT;
            if (!navigationGuard) {
                savedAllowBreak = CONFIG.client.extra.pathfinder.allowBreak;
                savedAllowPlace = CONFIG.client.extra.pathfinder.allowPlace;
                CONFIG.client.extra.pathfinder.allowBreak = false;
                CONFIG.client.extra.pathfinder.allowPlace = false;
                navigationGuard = true;
            }
            target = position.clone();
            probeSnapshot = null;
            probeStableTicks = 0;
            targetTicks = 0;
            openIssued = false;
            contentContainerId = -1;
            openContainerId = -1;
            BARITONE.stop();
        }
        if (++targetTicks > MAX_WINDOW_TICKS) return KitWorkingTransferExecutor.Check.MISMATCH;
        Container open = openWindow();
        if (open != null) {
            if (!openIssued) return KitWorkingTransferExecutor.Check.MISMATCH;
            int id = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
            int slots = chestSlots(open);
            if (openIssued && id == contentContainerId && (slots == 27 || slots == 54)
                    && atAccessPosition(position)) {
                openContainerId = id;
                BARITONE.stop();
                return KitWorkingTransferExecutor.Check.MATCH;
            }
            return KitWorkingTransferExecutor.Check.WAIT;
        }
        if (CACHE.getPlayerCache().getInventoryCache().getOpenContainerId() > 0)
            return KitWorkingTransferExecutor.Check.MISMATCH;
        if (!atAccessPosition(position)) {
            if (targetTicks == 1 || targetTicks % 40 == 0)
                BARITONE.pathTo(new GoalGetToBlock(new BlockPos(position[0], position[1], position[2])));
            return KitWorkingTransferExecutor.Check.WAIT;
        }
        String block = World.getBlock(position[0], position[1], position[2]).name();
        if (!block.equals("chest") && !block.equals("trapped_chest")
                && !block.equals("barrel") && !block.equals("minecraft:chest")
                && !block.equals("minecraft:trapped_chest") && !block.equals("minecraft:barrel"))
            return KitWorkingTransferExecutor.Check.MISMATCH;
        if (!openIssued) {
            var player = CACHE.getPlayerCache();
            BlockPos half = ContainerAccessPlanner.visibleInteractionHalf(player.getX(),
                    player.getY(), player.getZ(), position[0], position[1], position[2]);
            if (half == null) return KitWorkingTransferExecutor.Check.WAIT;
            BARITONE.stop();
            BARITONE.rightClickBlock(half.x(), half.y(), half.z());
            openIssued = true;
        }
        return KitWorkingTransferExecutor.Check.WAIT;
    }

    private boolean closeWindow() {
        int id = CACHE.getPlayerCache().getInventoryCache().getOpenContainerId();
        if (id <= 0) {
            closeIssuedForId = -1;
            closeWaitTicks = 0;
            return true;
        }
        if (closeIssuedForId == id) {
            if (++closeWaitTicks > 120) throw new IllegalStateException("container close unconfirmed");
            return false;
        }
        if (INVENTORY.hasActiveRequest()) return false;
        INVENTORY.submit(InventoryActionRequest.builder().owner(this).priority(6000)
                .actions(new CloseContainer(id)).build());
        closeIssuedForId = id;
        closeWaitTicks = 0;
        return false;
    }

    private boolean unchangedExceptDestination(List<String> after, int slot) {
        if (after == null || destinationBefore == null
                || after.size() != destinationBefore.size()) return false;
        for (int i = 0; i < after.size(); i++) {
            if (i != slot && !after.get(i).equals(destinationBefore.get(i))) return false;
        }
        return true;
    }

    private static List<String> snapshot(Container open, int slots) {
        try {
            List<String> result = new ArrayList<>(slots);
            for (int i = 0; i < slots; i++) {
                ItemStack stack = open.getItemStack(i);
                result.add(isEmpty(stack) ? "" : stack.getAmount() + ":"
                        + KitVariantFingerprint.canonicalVariant(stack));
            }
            return List.copyOf(result);
        } catch (RuntimeException e) { return null; }
    }

    static boolean boxMatches(ItemStack box, KitWorkingTransferJournal.Ticket ticket) {
        try {
            return boxShapeMatches(box, ticket.itemId(), ticket.units())
                    && ticket.boxFingerprint().equals(KitVariantFingerprint.canonicalVariant(box));
        } catch (RuntimeException e) { return false; }
    }

    static boolean boxShapeMatches(ItemStack box, String itemId, int units) {
        if (box == null || box.getAmount() != 1) return false;
        String id = ItemIdentifier.baseItemId(ItemIdentifier.getItemId(box));
        if (!id.endsWith("shulker_box")) return false;
        try {
            var physical = ItemIdentifier.readShulkerPhysicalSlotStacks(box);
            String innerVariant = null;
            int physicalUnits = 0;
            for (ItemStack inner : physical.values()) {
                if (!itemId.equals(ItemIdentifier.getItemId(inner))) return false;
                var components = inner.getDataComponents();
                if (components != null && (components.get(DataComponentTypes.CUSTOM_NAME) != null
                        || components.get(DataComponentTypes.ITEM_NAME) != null)) return false;
                String variant = KitVariantFingerprint.canonicalVariant(inner);
                if (innerVariant != null && !innerVariant.equals(variant)) return false;
                innerVariant = variant;
                physicalUnits += inner.getAmount();
            }
            return ItemIdentifier.readShulkerContents(box).equals(Map.of(itemId, units))
                    && physicalUnits == units;
        } catch (RuntimeException e) { return false; }
    }

    private static int countPlayerBoxes(KitWorkingTransferJournal.Ticket ticket) {
        int count = 0;
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return -1;
        for (int raw = 9; raw <= 44; raw++) if (boxMatches(player.getItemStack(raw), ticket)) count++;
        return count;
    }

    private static int uniquePlayerBoxSlot(KitWorkingTransferJournal.Ticket ticket) {
        int slot = -1;
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return -1;
        for (int raw = 9; raw <= 44; raw++) if (boxMatches(player.getItemStack(raw), ticket)) {
            if (slot >= 0) return -1;
            slot = raw;
        }
        return slot;
    }

    private static int firstFreePlayerRawSlot() {
        Container player = CACHE.getPlayerCache().getInventoryCache().getPlayerInventory();
        if (player == null) return -1;
        for (int raw = 9; raw <= 44; raw++) if (isEmpty(player.getItemStack(raw))) return raw;
        return -1;
    }

    private boolean safe() { return exclusiveAutomationOwner.getAsBoolean()
            && Proxy.getInstance().isConnected()
            && !Proxy.getInstance().hasActivePlayer()
            && CACHE.getPlayerCache().getThePlayer() != null
            && CACHE.getPlayerCache().getThePlayer().isAlive(); }
    private static boolean clearCursor() {
        return isEmpty(CACHE.getPlayerCache().getInventoryCache().getMouseStack());
    }
    private static boolean isEmpty(ItemStack stack) { return stack == null || stack.getAmount() <= 0; }
    private static int chestSlots(Container open) { return open == null ? 0 : open.getSize() - 36; }
    private static int rawToWindow(int raw, int slots) {
        return raw < 36 ? slots + raw - 9 : slots + 27 + raw - 36;
    }
    private static Container openWindow() {
        var cache = CACHE.getPlayerCache().getInventoryCache();
        Container open = cache.getOpenContainer();
        return open != null && open.getContainerId() == cache.getOpenContainerId() ? open : null;
    }
    private static boolean atAccessPosition(int[] position) {
        var player = CACHE.getPlayerCache();
        return ContainerApproach.isAtAccessPosition(player.getX(), player.getY(), player.getZ(),
                position[0], position[1], position[2]);
    }
}
