package com.flashsale.outbox.application;

import com.flashsale.outbox.domain.OutboxStore;
import com.flashsale.shared.time.BusinessClock;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * Records an event in the caller's transaction. MANDATORY: writing an event outside the business
 * transaction would bring back the "saved but not announced" problem the outbox exists to solve.
 */
@Service
public class OutboxWriter {

    private final OutboxStore store;
    private final JsonMapper json;
    private final BusinessClock clock;

    public OutboxWriter(OutboxStore store, JsonMapper json, BusinessClock clock) {
        this.store = store;
        this.json = json;
        this.clock = clock;
    }

    /** Returns the event id, which consumers use to recognise redeliveries. */
    @Transactional(propagation = Propagation.MANDATORY)
    public UUID write(String aggregateType, UUID aggregateId, String routingKey, Object payload) {
        UUID eventId = UUID.randomUUID();
        store.append(
                eventId,
                aggregateType,
                aggregateId,
                payload.getClass().getSimpleName(),
                routingKey,
                json.writeValueAsString(payload),
                clock.now());
        return eventId;
    }
}
