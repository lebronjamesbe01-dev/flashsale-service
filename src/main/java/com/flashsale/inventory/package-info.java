/**
 * Warehouse stock per product: on_hand (physically available) and reserved (held for flash sale
 * quotas). Kept in sync by consuming domain events idempotently.
 */
package com.flashsale.inventory;
