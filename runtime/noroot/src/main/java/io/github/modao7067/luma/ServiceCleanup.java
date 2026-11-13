package io.github.modao7067.luma;

import java.util.concurrent.Executor;

final class ServiceCleanup {
    static void start(Runnable cleanup, Runnable completion, Executor serviceThread) {
        new Thread(() -> {
            try { cleanup.run(); }
            finally { serviceThread.execute(completion); }
        }, "LumaServiceCleanup").start();
    }
}
