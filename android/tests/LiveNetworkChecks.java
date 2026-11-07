package com.pilipala.marcomovie;

import java.util.concurrent.*;

/** Real HTTP checks for source switching and credential handling on redirects. */
public final class LiveNetworkChecks {
    public static void main(String[] args) throws Exception {
        LiveCatalog.Source source = new LiveCatalog.Source();
        source.url = args[0] + "cross";
        source.headers.put("authorization", "test-auth");
        source.headers.put("cOoKiE", "test-cookie");
        if (LiveCatalog.load(source).channelCount() != 1) throw new AssertionError("redirect");
        System.out.println("PASS cross-origin redirect removes mixed-case credentials");
        source.url = args[0] + "list.php";
        LiveCatalog.Catalog utf16 = LiveCatalog.load(source);
        LiveCatalog.Channel channel = utf16.groups.get(0).channels.get(0);
        if (!channel.name.equals("中文频道")
                || !channel.streams.get(0).url.equals(args[0] + "media/video.m3u8"))
            throw new AssertionError("PHP UTF16 playlist or relative stream decode failed");
        System.out.println("PASS PHP endpoint with UTF16 BOM playlist and relative media");
        source.url = args[0] + "slow";
        ExecutorService executor = Executors.newSingleThreadExecutor();
        RequestScope scope = new RequestScope();
        CountDownLatch done = new CountDownLatch(1);
        try {
            executor.submit(
                    () -> {
                        try {
                            scope.call(() -> LiveCatalog.load(source));
                        } catch (Exception expected) {
                        } finally {
                            done.countDown();
                        }
                    });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (scope.connections.isEmpty() && System.nanoTime() < deadline) Thread.sleep(10);
            if (scope.connections.isEmpty()) throw new AssertionError("socket not registered");
            scope.cancel();
            if (!done.await(3, TimeUnit.SECONDS))
                throw new AssertionError("cancel did not close request");
            System.out.println("PASS source switch closes in-flight live connection");
        } finally {
            executor.shutdownNow();
        }
    }
}
