package com.flashsale.flashsale.domain;

public enum SlotStatus {
    /** Being prepared by an admin; invisible to users; may overlap other drafts. */
    DRAFT,
    /** Visible and purchasable between start and end. Never overlaps another live slot. */
    PUBLISHED,
    /** Ended or stopped early; unsold quota has been returned to the warehouse. */
    CLOSED
}
