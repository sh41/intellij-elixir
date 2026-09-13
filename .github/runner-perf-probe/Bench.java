import java.util.*;
import java.util.concurrent.*;

// Allocation-, hash- and sort-heavy, closer to a compiler than a crypto loop, which would mostly measure
// SHA or AES extensions. Run with the same JBR on every runner so the result compares CPUs, not toolchains.
public class Bench {
    static long work(long seed) {
        Random r = new Random(seed);
        Map<String, Integer> map = new HashMap<>();
        List<String> keys = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 600_000; i++) {
            sb.setLength(0);
            for (int j = 0, n = 8 + r.nextInt(16); j < n; j++) sb.append((char) ('a' + r.nextInt(26)));
            String s = sb.toString();
            map.merge(s, 1, Integer::sum);
            keys.add(s);
        }
        Collections.sort(keys);
        long h = 0;
        for (String k : keys) h = h * 31 + k.hashCode() + map.get(k);
        return h;
    }

    static double timeMs(int threads, ExecutorService pool) throws Exception {
        long t0 = System.nanoTime();
        List<Future<Long>> fs = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            long seed = i;
            fs.add(pool.submit(() -> work(seed)));
        }
        for (Future<Long> f : fs) f.get();
        return (System.nanoTime() - t0) / 1e6;
    }

    public static void main(String[] args) throws Exception {
        int cpus = Runtime.getRuntime().availableProcessors();
        ExecutorService pool = Executors.newFixedThreadPool(cpus);
        System.out.printf("java %s, availableProcessors %d%n", System.getProperty("java.vm.version"), cpus);
        for (int threads : new int[] {1, cpus}) {
            for (int i = 0; i < 2; i++) timeMs(threads, pool);
            double[] t = new double[5];
            for (int i = 0; i < t.length; i++) t[i] = timeMs(threads, pool);
            Arrays.sort(t);
            System.out.printf("threads=%d median=%.0f ms min=%.0f max=%.0f ms%n", threads, t[2], t[0], t[4]);
        }
        pool.shutdown();
    }
}
