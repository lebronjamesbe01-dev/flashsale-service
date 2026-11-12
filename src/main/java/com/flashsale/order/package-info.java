/**
 * Flash sale purchase use case: reserve a unit, debit the wallet and create the order in one
 * database transaction, then emit an order event through the outbox.
 */
package com.flashsale.order;
