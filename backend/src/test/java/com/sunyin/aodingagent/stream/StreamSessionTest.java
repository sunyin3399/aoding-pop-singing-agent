package com.sunyin.aodingagent.stream;

import org.junit.jupiter.api.Test;
import reactor.core.Disposable;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StreamSessionTest {

    @Test
    void replaysOnlyEventsAfterClientCursor() {
        StreamSession session = new StreamSession("request-1");
        session.emit("message", "A");
        session.emit("message", "B");
        session.complete();

        List<StreamEvent> resumed = session.eventsAfter(1).collectList().block();

        assertEquals(2, resumed.size());
        assertEquals("B", resumed.get(0).data());
        assertEquals("complete", resumed.get(1).event());
    }

    @Test
    void explicitCancellationDisposesModelSubscription() {
        StreamSession session = new StreamSession("request-2");
        AtomicBoolean disposed = new AtomicBoolean();
        session.attach((Disposable) () -> disposed.set(true));

        session.cancel();

        assertTrue(disposed.get());
        assertTrue(session.isCancelled());
    }
}
