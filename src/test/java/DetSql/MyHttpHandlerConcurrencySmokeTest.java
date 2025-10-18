package DetSql;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class MyHttpHandlerConcurrencySmokeTest {

    @Test
    public void allocateIdAndInitMapShouldBeThreadSafe() throws InterruptedException {
        final ReentrantLock lock = new ReentrantLock();
        final int[] countIdRef = new int[]{1};
        final ConcurrentHashMap<String, java.util.List<PocLogEntry>> map = new ConcurrentHashMap<>();

        int threads = 64;
        int tasks = 2000; // enough to amplify races if any
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        ConcurrentSkipListSet<Integer> ids = new ConcurrentSkipListSet<>();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(tasks);

        for (int i = 0; i < tasks; i++) {
            final String hash = "h" + i;
            pool.submit(() -> {
                try {
                    start.await();
                    int id = MyHttpHandler.allocateIdAndInitMapForTest(lock, countIdRef, map, hash);
                    ids.add(id);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "Tasks did not finish in time");
        pool.shutdownNow();

        // Assert uniqueness and monotonicity
        assertEquals(tasks, ids.size(), "IDs should be unique for each allocation");
        assertEquals(1 + tasks - 1, ids.last(), "Max ID should be start + tasks - 1");

        // Assert map initialized for each hash
        assertEquals(tasks, map.size(), "Each hash should be initialized in map");
        map.forEach((k, v) -> assertNotNull(v, "Map value should not be null"));
    }
}
