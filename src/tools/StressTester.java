package src.tools;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class StressTester {
    public static void main(String[] args) {
        String urlString = "http://127.0.0.1:8080/";
        int concurrency = 50;
        int durationSeconds = 10;

        if (args.length > 0) urlString = args[0];
        if (args.length > 1) concurrency = Integer.parseInt(args[1]);
        if (args.length > 2) durationSeconds = Integer.parseInt(args[2]);

        System.out.println("Starting stress test against: " + urlString);
        System.out.println("Concurrency: " + concurrency + " threads");
        System.out.println("Duration: " + durationSeconds + " seconds");

        ExecutorService executor = Executors.newFixedThreadPool(concurrency);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        AtomicLong totalLatency = new AtomicLong(0);

        long endTime = System.currentTimeMillis() + (durationSeconds * 1000L);
        final String targetUrl = urlString;

        for (int i = 0; i < concurrency; i++) {
            executor.submit(() -> {
                while (System.currentTimeMillis() < endTime) {
                    long start = System.currentTimeMillis();
                    try {
                        URL url = new URL(targetUrl);
                        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                        conn.setRequestMethod("GET");
                        conn.setConnectTimeout(2000);
                        conn.setReadTimeout(2000);

                        int code = conn.getResponseCode();
                        try (InputStream is = conn.getInputStream()) {
                            byte[] buffer = new byte[1024];
                            while (is.read(buffer) != -1) {}
                        }

                        long latency = System.currentTimeMillis() - start;
                        if (code >= 200 && code < 400) {
                            successCount.incrementAndGet();
                            totalLatency.addAndGet(latency);
                        } else {
                            failureCount.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failureCount.incrementAndGet();
                    }
                }
            });
        }

        executor.shutdown();
        try {
            executor.awaitTermination(durationSeconds + 5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        int success = successCount.get();
        int failure = failureCount.get();
        int total = success + failure;
        double successRate = total > 0 ? (success * 100.0 / total) : 0.0;
        double avgLatency = success > 0 ? (totalLatency.get() * 1.0 / success) : 0.0;

        System.out.println("\n--- Stress Test Results ---");
        System.out.println("Total Requests: " + total);
        System.out.println("Successful: " + success);
        System.out.println("Failed: " + failure);
        System.out.println("Success Rate: " + String.format("%.2f%%", successRate));
        System.out.println("Average Latency: " + String.format("%.2f ms", avgLatency));
    }
}
