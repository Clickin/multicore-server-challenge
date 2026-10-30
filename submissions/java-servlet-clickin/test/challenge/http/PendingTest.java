package challenge.http;

public final class PendingTest {
    public static void main(String[] args) {
        Loop.Pending queue = new Loop.Pending();
        Loop.Conn first = new Loop.Conn(1), second = new Loop.Conn(2), third = new Loop.Conn(3);
        queue.add(first); queue.add(second); queue.add(first);
        if (queue.poll() != first) throw new AssertionError("first connection");
        queue.add(first); queue.add(third);
        if (queue.poll() != second || queue.poll() != first || queue.poll() != third || queue.poll() != null || !queue.isEmpty())
            throw new AssertionError("FIFO, deduplication, or requeue");
        queue.add(second);
        if (queue.poll() != second || !queue.isEmpty()) throw new AssertionError("reuse after empty");
    }
}
