package com.zenith.plugin.stashmanager.kit;

/** Only restart from phases with no in-flight inventory or world transaction. */
public final class KitResumePolicy {
    private KitResumePolicy() { }

    private static boolean hasRemainingBox(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint.completedBoxes() + checkpoint.incompleteBoxes()
                < checkpoint.requestedBoxes();
    }

    /** A packed apple batch was verified, but displaced custom stacks exceeded the keep quota. */
    public static boolean canAuditAppleOverflow(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && "kit_inventory_unexpected_items".equals(checkpoint.blocker())
                && checkpoint.resumeState() == null && hasRemainingBox(checkpoint)
                && "enchanted_golden_apple".equals(checkpoint.currentItem())
                && checkpoint.currentQuantity() > 0
                && checkpoint.packedContents().getOrDefault("enchanted_golden_apple", 0).intValue()
                    == checkpoint.recipe().getOrDefault("enchanted_golden_apple", -1)
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && checkpoint.kitWorksite() == null && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.sourceBoxesToReturn().isEmpty()
                && checkpoint.auditedCargoSlot() < 0 && checkpoint.auditedCargoSlots().isEmpty()
                && checkpoint.displacedAppleSlots().isEmpty();
    }

    public static String blocker(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return blocker(checkpoint, checkpoint == null ? null : checkpoint.resumeState());
    }

    /** A witnessed export can resume only at the next empty-shell boundary. */
    public static boolean canResumeAuditedExport(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && "export_delivery_audited".equals(checkpoint.blocker())
                && checkpoint.resumeState() == null
                && checkpoint.completedBoxes() > 0
                && hasRemainingBox(checkpoint)
                && checkpoint.packedContents().isEmpty()
                && checkpoint.ownedShellSlot() < 0
                && checkpoint.currentItem() == null && checkpoint.currentQuantity() == 0
                && checkpoint.kitWorksite() == null
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.sourceBoxesToReturn().isEmpty()
                && checkpoint.writtenOffSources().isEmpty()
                && checkpoint.auditedCargoSlot() < 0
                && checkpoint.auditedCargoSlots().isEmpty();
    }

    public static boolean canRetryUnverifiedCargo(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && checkpoint.blocker() != null
                && checkpoint.blocker().startsWith("kit_retrieved_cargo_unverified:")
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.ownedShellSlot() >= 9
                && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null
                && checkpoint.currentQuantity() > 0
                && checkpoint.currentQuantity()
                    <= remainingForCurrentItem(checkpoint)
                && hasRemainingBox(checkpoint);
    }

    public static boolean canRetryAfterHotbarClear(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_hotbar_slots_unavailable".equals(checkpoint.blocker())
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.ownedShellSlot() >= 9
                && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null
                && checkpoint.packedContents().getOrDefault(checkpoint.currentItem(), 0)
                    <= checkpoint.recipe().getOrDefault(checkpoint.currentItem(), -1)
                && checkpoint.packedContents().getOrDefault(checkpoint.currentItem(), 0)
                    >= checkpoint.currentQuantity()
                && hasRemainingBox(checkpoint);
    }

    /** The batch preflight stopped before borrowing a box or moving any cargo. */
    public static boolean canRetryAfterMainHeadroom(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_main_inventory_batch_headroom_insufficient".equals(checkpoint.blocker())
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn().isEmpty()
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null && checkpoint.currentQuantity() == 0
                && checkpoint.packedContents().getOrDefault(checkpoint.currentItem(), 0)
                    < checkpoint.recipe().getOrDefault(checkpoint.currentItem(), 0)
                && hasRemainingBox(checkpoint);
    }

    /** A failed borrow may leave the still-full source shulker in player inventory. */
    public static boolean canRetryBorrowedInventoryCargo(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_cargo_retrieval_incomplete".equals(checkpoint.blocker())
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.ownedShellSlot() >= 9
                && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null
                && checkpoint.currentQuantity() > 0
                && checkpoint.currentQuantity()
                    <= remainingForCurrentItem(checkpoint)
                && hasRemainingBox(checkpoint);
    }

    /** No transfer was observed: retry retrieval only after live inventory confirms no loose cargo. */
    public static boolean canRetryNoCargoRetrieval(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return canRetryBorrowedInventoryCargo(checkpoint)
                && checkpoint.sourceBoxesToReturn().isEmpty()
                && checkpoint.auditedCargoSlot() < 0;
    }

    /** A failed retrieval may coexist with older same-item kept stock. Require an
     * exact live-slot declaration before treating that stock as non-cargo. */
    public static boolean canAuditKeptNoCargoRetrieval(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return canRetryNoCargoRetrieval(checkpoint)
                && "kit_cargo_retrieval_incomplete".equals(checkpoint.blocker())
                && checkpoint.writtenOffSources().isEmpty()
                && checkpoint.auditedCargoSlots().isEmpty();
    }

    /** Recover a verified loose batch after the borrowed world box was picked up. */
    public static boolean canResumeAuditedCargo(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && ("recovered_box_requires_cargo_audit".equals(checkpoint.blocker())
                    || (checkpoint.blocker() != null
                        && checkpoint.blocker().startsWith("kit_retrieved_cargo_unverified:")
                        && checkpoint.sourceBoxesToReturn().isEmpty()))
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.ownedShellSlot() >= 9
                && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null
                && checkpoint.currentQuantity() > 0
                && checkpoint.currentQuantity()
                    <= remainingForCurrentItem(checkpoint)
                && hasRemainingBox(checkpoint);
    }

    /** A recovered borrowed box may still be pending return beside loose task cargo.
     * Permit only the original, unaudited boundary; live inventory verification is
     * performed before the batch audit is journaled. */
    public static boolean canAuditRecoveredBorrowedCargo(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return canResumeAuditedCargo(checkpoint)
                && "recovered_box_requires_cargo_audit".equals(checkpoint.blocker())
                && checkpoint.sourceBoxesToReturn() != null
                && checkpoint.sourceBoxesToReturn().size() == 1
                && checkpoint.auditedCargoSlot() < 0
                && checkpoint.auditedCargoSlots().isEmpty();
    }

    /** Retry only the unchanged, already-audited multi-source preflight boundary. */
    public static boolean canRetryAuditedMultiSourcePreflight(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        if (checkpoint == null || !"BLOCKED".equals(checkpoint.state())
                || !"borrowed_source_return_ambiguous".equals(checkpoint.blocker())
                || !"WAIT_CARGO".equals(checkpoint.resumeState())
                || checkpoint.resumeNotBeforeEpochMilli() != 0
                || checkpoint.kitWorksite() != null
                || checkpoint.borrowedSourceWorksite() != null
                || checkpoint.sourceBoxesToReturn() == null
                || checkpoint.sourceBoxesToReturn().size() < 2
                || checkpoint.ownedShellSlot() < 9 || checkpoint.ownedShellSlot() > 44
                || checkpoint.currentItem() == null || checkpoint.currentQuantity() <= 0
                || checkpoint.currentQuantity() > remainingForCurrentItem(checkpoint)
                || checkpoint.auditedCargoSlot() >= 0
                || checkpoint.auditedCargoSlots() == null
                || checkpoint.auditedCargoSlots().isEmpty()
                || !hasRemainingBox(checkpoint)) return false;
        long auditedTotal = 0;
        for (var entry : checkpoint.auditedCargoSlots().entrySet()) {
            if (entry.getKey() == null || entry.getKey() < 9 || entry.getKey() > 44
                    || entry.getKey() == checkpoint.ownedShellSlot()
                    || checkpoint.displacedAppleSlots().containsKey(entry.getKey())
                    || entry.getValue() == null || entry.getValue() <= 0
                    || entry.getValue() > 64) return false;
            auditedTotal += entry.getValue();
        }
        return auditedTotal == checkpoint.currentQuantity();
    }

    /** An already recovered borrowed box and loose batch need an explicit, exact
     * multi-slot audit before packing. A plain resume must not replay retrieval. */
    public static boolean canAuditUnverifiedBorrowedCargo(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        if (checkpoint == null || checkpoint.blocker() == null) return false;
        boolean originalMismatch = checkpoint.resumeState() == null
                && checkpoint.blocker().startsWith("kit_retrieved_cargo_unverified:");
        // An admission preflight may reject the journaled borrowed box before
        // any movement. Permit re-auditing, but only from the saved WAIT_CARGO
        // boundary with its exact previously pinned batch.
        boolean rejectedAdmission = "WAIT_CARGO".equals(checkpoint.resumeState())
                && checkpoint.resumeNotBeforeEpochMilli() == 0
                && checkpoint.blocker().startsWith("unexpected_inventory_slots:")
                && !checkpoint.auditedCargoSlots().isEmpty();
        return "BLOCKED".equals(checkpoint.state())
                && (originalMismatch || rejectedAdmission)
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn() != null
                && checkpoint.sourceBoxesToReturn().size() == 1
                && checkpoint.ownedShellSlot() >= 9
                && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null
                && checkpoint.currentQuantity() > 1
                && checkpoint.currentQuantity() <= remainingForCurrentItem(checkpoint)
                && hasRemainingBox(checkpoint);
    }

    /** A completed retrieval stopped at the pre-existing inventory guard, before packing. */
    public static boolean canAuditChangedBaselineCargo(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && "kit_preexisting_inventory_changed".equals(checkpoint.blocker())
                && checkpoint.resumeState() == null
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn() != null
                && !checkpoint.sourceBoxesToReturn().isEmpty()
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null && checkpoint.currentQuantity() > 1
                && checkpoint.currentQuantity() <= remainingForCurrentItem(checkpoint)
                && checkpoint.auditedCargoSlot() < 0
                && checkpoint.auditedCargoSlots().isEmpty()
                && hasRemainingBox(checkpoint);
    }

    /** A failed apple search may leave an exact partial transfer beside protected kept stock. */
    public static boolean canAuditKeptAppleCargo(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_cargo_retrieval_incomplete".equals(checkpoint.blocker())
                && "enchanted_golden_apple".equals(checkpoint.currentItem())
                && checkpoint.currentQuantity() > 1
                && checkpoint.currentQuantity() <= remainingForCurrentItem(checkpoint)
                && checkpoint.packedContents().getOrDefault("enchanted_golden_apple", 0) == 0
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn().isEmpty()
                && checkpoint.auditedCargoSlot() < 0
                && checkpoint.auditedCargoSlots().isEmpty()
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && hasRemainingBox(checkpoint);
    }

    /** A borrowed source may be back in inventory while a smaller, exact apple
     * batch is already loose. Audit that batch before touching the source again. */
    public static boolean canAuditPartialBorrowedAppleCargo(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_cargo_retrieval_incomplete".equals(checkpoint.blocker())
                && "enchanted_golden_apple".equals(checkpoint.currentItem())
                && checkpoint.currentQuantity() > 1
                && checkpoint.currentQuantity() <= remainingForCurrentItem(checkpoint)
                && checkpoint.packedContents().getOrDefault("enchanted_golden_apple", 0) == 0
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn() != null
                && checkpoint.sourceBoxesToReturn().size() == 1
                && checkpoint.auditedCargoSlot() < 0
                && checkpoint.auditedCargoSlots().isEmpty()
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && hasRemainingBox(checkpoint);
    }

    /** A rejected packer preflight has not placed a box or moved cargo. Re-audit the
     * same pinned batch rather than fetching it again or replaying a world action. */
    public static boolean canRetryPackerPreflight(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_packer_start_failed".equals(checkpoint.blocker())
                && "enchanted_golden_apple".equals(checkpoint.currentItem())
                && checkpoint.currentQuantity() > 1
                && checkpoint.currentQuantity() <= remainingForCurrentItem(checkpoint)
                && checkpoint.packedContents().getOrDefault("enchanted_golden_apple", 0) == 0
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn() != null
                && checkpoint.sourceBoxesToReturn().size() == 1
                && !checkpoint.auditedCargoSlots().isEmpty()
                && checkpoint.auditedCargoSlots().values().stream()
                    .mapToInt(Integer::intValue).sum() == checkpoint.currentQuantity()
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && hasRemainingBox(checkpoint);
    }

    /** Recovered shell itself proves a completed batch; no loose cargo may be replayed. */
    public static boolean canResumeRecoveredPackedBatch(KitFulfillmentJournalStore.Snapshot checkpoint) {
        if (checkpoint == null || !"BLOCKED".equals(checkpoint.state())
                || checkpoint.resumeState() != null
                || !"recovered_packed_batch_verified".equals(checkpoint.blocker())
                || checkpoint.borrowedSourceWorksite() != null
                || checkpoint.kitWorksite() != null
                || !checkpoint.sourceBoxesToReturn().isEmpty()
                || checkpoint.currentItem() != null || checkpoint.currentQuantity() != 0
                || checkpoint.ownedShellSlot() < 9 || checkpoint.ownedShellSlot() > 44
                || !hasRemainingBox(checkpoint)) return false;
        for (var entry : checkpoint.packedContents().entrySet()) {
            if (entry.getValue() < 0
                    || entry.getValue() > checkpoint.recipe().getOrDefault(entry.getKey(), 0)) {
                return false;
            }
        }
        return !checkpoint.packedContents().isEmpty();
    }

    public static boolean canResumeReconciledLoss(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "lost_borrowed_source_operator_attested".equals(checkpoint.blocker())
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn().isEmpty()
                && !checkpoint.writtenOffSources().isEmpty()
                && checkpoint.auditedCargoSlot() >= 9
                && checkpoint.currentItem() != null && checkpoint.currentQuantity() > 0
                && checkpoint.currentQuantity() <= remainingForCurrentItem(checkpoint)
                && hasRemainingBox(checkpoint);
    }

    /** A written-off borrowed source may be retried only after an explicit no-cargo audit.
     * The live audit must separately prove that any same-item inventory is protected keep
     * supply; a historical source count is not evidence of loose job cargo. */
    public static boolean canAuditLostBorrowedNoCargo(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "lost_borrowed_source_operator_attested_needs_cargo_audit"
                    .equals(checkpoint.blocker())
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn().isEmpty()
                && !checkpoint.writtenOffSources().isEmpty()
                && checkpoint.auditedCargoSlot() < 0
                && checkpoint.auditedCargoSlots().isEmpty()
                && checkpoint.ownedShellSlot() >= 9
                && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null
                && checkpoint.currentQuantity() > 0
                && checkpoint.currentQuantity() <= remainingForCurrentItem(checkpoint)
                && hasRemainingBox(checkpoint);
    }

    /** The same attested-loss boundary may instead have one uniquely identified loose
     * task stack. Audit that exact stack; never relabel it as kept no-cargo supply. */
    public static boolean canAuditLostBorrowedCargo(
            KitFulfillmentJournalStore.Snapshot checkpoint, int rawSlot, int quantity) {
        return canAuditLostBorrowedNoCargo(checkpoint)
                && rawSlot >= 9 && rawSlot <= 44
                && rawSlot != checkpoint.ownedShellSlot()
                && quantity == checkpoint.currentQuantity()
                && quantity <= 64;
    }

    /** The exporter rejected the target window before submitting a return click. */
    public static boolean canRerouteSourceReturn(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && ("kit_source_return_failed:export_chest_full".equals(checkpoint.blocker())
                    || "kit_source_return_no_empty_import".equals(checkpoint.blocker())
                    || "kit_source_return_no_capacity_import".equals(checkpoint.blocker())
                    || "kit_source_return_failed:export_target_footprint_changed"
                        .equals(checkpoint.blocker())
                    || "kit_source_return_failed:export_target_block_changed"
                        .equals(checkpoint.blocker())
                    || "kit_source_return_failed:export_target_changed_before_transfer"
                        .equals(checkpoint.blocker())
                    || "kit_source_return_failed:wrong_export_container_opened"
                        .equals(checkpoint.blocker())
                    || "kit_source_return_failed:export_window_lost_before_transfer"
                        .equals(checkpoint.blocker())
                    // Older exporter versions used this reason even before any click.
                    // The caller must verify the unique borrowed box is still in inventory.
                    || "kit_source_return_failed:export_window_lost"
                        .equals(checkpoint.blocker()))
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.ownedShellSlot() >= 9
                && checkpoint.ownedShellSlot() <= 44
                && hasRemainingBox(checkpoint)
                && checkpoint.sourceBoxesToReturn() != null
                && !checkpoint.sourceBoxesToReturn().isEmpty();
    }

    /** The transfer was observed, but the reopened return window was judged too early.
     * This boundary may only be resumed by a read-only live-chest audit, never by
     * replaying the source-box deposit. */
    public static boolean canAuditCompletedSourceReturn(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_source_return_failed:export_postclose_verification_mismatch"
                        .equals(checkpoint.blocker())
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && checkpoint.sourceBoxesToReturn() != null
                && checkpoint.sourceBoxesToReturn().size() == 1
                && checkpoint.sourceBoxesToReturn().getFirst().returnPosition() != null
                && checkpoint.currentItem() != null
                && checkpoint.currentQuantity() > 0
                && checkpoint.packedContents().getOrDefault(checkpoint.currentItem(), 0)
                    .intValue() == checkpoint.recipe().getOrDefault(checkpoint.currentItem(), -1)
                    .intValue()
                && hasRemainingBox(checkpoint);
    }

    /** A transfer click was submitted but never confirmed. Recheck the original
     * chest without a click before routing the still-carried box to an import. */
    public static boolean canAuditFailedSourceReturn(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_source_return_failed:export_deposit_unconfirmed"
                        .equals(checkpoint.blocker())
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && checkpoint.sourceBoxesToReturn() != null
                && checkpoint.sourceBoxesToReturn().size() == 1
                && checkpoint.sourceBoxesToReturn().getFirst().sourcePosition() != null
                && checkpoint.sourceBoxesToReturn().getFirst().returnPosition() == null
                && checkpoint.currentItem() != null && checkpoint.currentQuantity() > 0
                && checkpoint.packedContents().getOrDefault(checkpoint.currentItem(), 0)
                    .intValue() == checkpoint.recipe().getOrDefault(checkpoint.currentItem(), -1)
                    .intValue()
                && hasRemainingBox(checkpoint);
    }

    /** A separately selected registered import must be checked live for capacity
     * before any inventory click. Accept the older empty-import blocker too. */
    public static boolean canScheduleLiveCapacityImportReturn(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && ("kit_source_return_no_empty_import".equals(checkpoint.blocker())
                    || "kit_source_return_no_capacity_import".equals(checkpoint.blocker()))
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && hasRemainingBox(checkpoint)
                && checkpoint.sourceBoxesToReturn() != null
                && checkpoint.sourceBoxesToReturn().size() == 1;
    }

    /** Legacy checkpoint: the exact kit batch was packed, but an inventory-borrowed
     * source box was recovered before its return obligation could be journaled. */
    public static boolean canAuditUnjournaledBorrowedSource(
            KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_inventory_unexpected_items".equals(checkpoint.blocker())
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.kitWorksite() == null
                && checkpoint.sourceBoxesToReturn() != null
                && checkpoint.sourceBoxesToReturn().isEmpty()
                && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                && checkpoint.currentItem() != null && checkpoint.currentQuantity() > 0
                && checkpoint.packedContents().getOrDefault(checkpoint.currentItem(), 0)
                    .intValue() == checkpoint.recipe().getOrDefault(checkpoint.currentItem(), -1)
                    .intValue()
                && hasRemainingBox(checkpoint);
    }

    /** This failure occurs before the packer submits any world placement request. */
    public static boolean canRecoverUnplacedKitShell(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint != null && "BLOCKED".equals(checkpoint.state())
                && checkpoint.resumeState() == null
                && "kit_pack_failed:worksite_changed_before_place".equals(checkpoint.blocker())
                && checkpoint.kitWorksite() != null
                && checkpoint.borrowedSourceWorksite() == null
                && checkpoint.currentItem() != null
                && checkpoint.currentQuantity() > 0
                && checkpoint.currentQuantity() <= remainingForCurrentItem(checkpoint)
                && hasRemainingBox(checkpoint);
    }

    private static int remainingForCurrentItem(KitFulfillmentJournalStore.Snapshot checkpoint) {
        return checkpoint.recipe().getOrDefault(checkpoint.currentItem(), 0)
                - checkpoint.packedContents().getOrDefault(checkpoint.currentItem(), 0);
    }

    public static String blocker(KitFulfillmentJournalStore.Snapshot checkpoint,
                                 String resumeState) {
        if (checkpoint == null || resumeState == null) return "not_scheduled";
        if (checkpoint.borrowedSourceWorksite() != null || checkpoint.kitWorksite() != null) {
            return "world_box_recovery_pending";
        }
        return switch (resumeState) {
            case "NEED_SHELL" -> checkpoint.packedContents().isEmpty()
                    && checkpoint.ownedShellSlot() < 0 ? null : "shell_boundary_inconsistent";
            case "NEED_CARGO" -> checkpoint.ownedShellSlot() >= 9
                    && checkpoint.ownedShellSlot() <= 44 ? null : "owned_shell_slot_unknown";
            case "WAIT_CARGO" -> ("resume_audited_inventory_cargo".equals(checkpoint.blocker())
                    || "resume_audited_kept_apple_cargo".equals(checkpoint.blocker())
                    || KitEmptyShellOwnershipAudit.isScheduled(checkpoint.blocker()))
                    && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                    && checkpoint.currentItem() != null && checkpoint.currentQuantity() > 0
                    ? null : "uncertain_wait_cargo";
            case "RETURNING_SOURCE" -> ("resume_source_return".equals(checkpoint.blocker())
                    || "resume_source_return_exact_import".equals(checkpoint.blocker()))
                    && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                    && checkpoint.sourceBoxesToReturn() != null
                    && !checkpoint.sourceBoxesToReturn().isEmpty()
                    ? null : "uncertain_source_return";
            case "VERIFY_RETURN" -> "resume_audit_completed_source_return".equals(
                    checkpoint.blocker())
                    && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                    && checkpoint.sourceBoxesToReturn() != null
                    && checkpoint.sourceBoxesToReturn().size() == 1
                    && checkpoint.sourceBoxesToReturn().getFirst().returnPosition() != null
                    ? null : "uncertain_completed_source_return";
            case "VERIFY_FAILED_RETURN" -> "resume_audit_failed_source_return".equals(
                    checkpoint.blocker())
                    && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                    && checkpoint.sourceBoxesToReturn() != null
                    && checkpoint.sourceBoxesToReturn().size() == 1
                    && checkpoint.sourceBoxesToReturn().getFirst().sourcePosition() != null
                    && checkpoint.sourceBoxesToReturn().getFirst().returnPosition() != null
                    ? null : "uncertain_failed_source_return";
            case "VARIANT_NEED_SOURCE" -> "resume_variant_exchange".equals(checkpoint.blocker())
                    && checkpoint.variantRepair() != null
                    && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                    && checkpoint.currentItem() != null && checkpoint.currentQuantity() == 1
                    ? null : "uncertain_variant_exchange";
            case "VARIANT_WAIT_SOURCE" -> "resume_variant_source_cargo".equals(checkpoint.blocker())
                    && checkpoint.variantRepair() != null
                    && checkpoint.variantRepair().auditedIncomingRawSlot() >= 9
                    && checkpoint.variantRepair().auditedIncomingRawSlot() <= 35
                    && checkpoint.ownedShellSlot() >= 9 && checkpoint.ownedShellSlot() <= 44
                    && checkpoint.currentItem() != null && checkpoint.currentQuantity() == 1
                    ? null : "uncertain_variant_source_cargo";
            default -> "uncertain_" + resumeState.toLowerCase();
        };
    }
}
