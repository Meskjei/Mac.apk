package com.macapk.loader;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;

public final class HostPointerQueueTest {
    public static void main(String[] args) {
        ArrayDeque<Runnable> loop = new ArrayDeque<>();
        ArrayList<Integer> delivered = new ArrayList<>();
        HostPointerQueue queue = new HostPointerQueue(loop::addLast);
        queue.enqueue(0, () -> delivered.add(0));
        for (int i = 1; i <= 100; i++) {
            final int positionAndArrivalTime = i;
            queue.enqueue(2, () -> delivered.add(positionAndArrivalTime));
        }
        queue.enqueue(1, () -> delivered.add(101));
        queue.enqueue(0, () -> delivered.add(102));
        queue.enqueue(2, () -> delivered.add(103));
        queue.enqueue(3, () -> delivered.add(104));
        if (loop.size() != 6) throw new AssertionError("one task per move burst, not a runnable per stale move");
        loop.addLast(() -> delivered.add(-1));
        while (!loop.isEmpty()) loop.removeFirst().run();
        if (!delivered.equals(Arrays.asList(0, 99, 100, 101, 102, 103, 104, -1))) throw new AssertionError(delivered);
        queue.enqueue(0, () -> { throw new IllegalStateException("application handler"); });
        queue.enqueue(3, () -> delivered.add(105));
        try { loop.removeFirst().run(); throw new AssertionError("expected handler failure"); }
        catch (IllegalStateException expected) { }
        while (!loop.isEmpty()) loop.removeFirst().run();
        if (delivered.get(delivered.size() - 1) != 105) throw new AssertionError("cancel lost after handler failure");
        queue.enqueue(0, () -> delivered.add(106));
        while (!loop.isEmpty()) loop.removeFirst().run();
        if (delivered.get(delivered.size() - 1) != 106) throw new AssertionError("queue stuck after drain");
        delivered.clear();
        queue.enqueue(2, () -> delivered.add(1));
        queue.barrier();
        loop.addLast(() -> delivered.add(2)); // A key must separate the two move batches.
        queue.enqueue(2, () -> delivered.add(3));
        while (!loop.isEmpty()) loop.removeFirst().run();
        if (!delivered.equals(Arrays.asList(1, 2, 3))) throw new AssertionError("key ordering changed: " + delivered);
        System.out.println("Host pointer coalescing, ordering, arrival time and render fairness passed");
    }
}
