package com.confia.shared.web.harness;

import com.confia.shared.web.authorization.PermissionDenialRecorder;
import com.confia.shared.web.authorization.PermissionDeniedException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** The double of the denial recorder for the database-free harness: it keeps what it was asked to record. */
public final class RecordingPermissionDenialRecorder implements PermissionDenialRecorder {

    /** One call: the denial and the request id the handler gave. */
    public record Call(PermissionDeniedException denied, String requestId) {
    }

    private final List<Call> calls = new CopyOnWriteArrayList<>();

    @Override
    public void record(PermissionDeniedException denied, String requestId) {
        calls.add(new Call(denied, requestId));
    }

    /** The calls so far, in order. */
    public List<Call> calls() {
        return List.copyOf(calls);
    }

    /** Forgets the calls, between tests. */
    public void clear() {
        calls.clear();
    }
}
