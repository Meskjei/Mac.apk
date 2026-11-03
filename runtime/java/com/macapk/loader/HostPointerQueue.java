package com.macapk.loader;

import java.util.function.Consumer;

/** Keep the latest two adjacent moves for release velocity; retain every gesture boundary. */
final class HostPointerQueue {
    private static final int MOVE = 2;
    private static final class Moves {
        Runnable previous, latest;
        Moves(Runnable latest) { this.latest = latest; }
    }
    private final Consumer<Runnable> post;
    private Moves pending;

    HostPointerQueue(Consumer<Runnable> post) { this.post = post; }

    synchronized void barrier() { pending = null; }

    synchronized void enqueue(int action, Runnable delivery) {
        if (action != MOVE) {
            pending = null;
            post.accept(delivery);
        } else if (pending != null) {
            pending.previous = pending.latest;
            pending.latest = delivery;
        } else {
            Moves moves = new Moves(delivery);
            pending = moves;
            post.accept(() -> drain(moves));
        }
    }

    private void drain(Moves moves) {
        Runnable previous, latest;
        synchronized (this) {
            if (pending == moves) pending = null;
            previous = moves.previous;
            latest = moves.latest;
        }
        // One queued task, at most two samples. Keep the Handler's ordering with keys, cancellation and lifecycle tasks.
        if (previous != null) previous.run();
        latest.run();
    }
}
