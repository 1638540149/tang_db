package com.wangyu.tangdb.query;

import com.wangyu.tangdb.storage.StoreException;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.Future;

/**
 * 算子内并行的小工具(SPEC-P6.1 FR-712 / FR-713 / FR-714)。
 *
 * <p>只用 JDK 的 {@code java.util.concurrent}(依赖红线),按任务下标把一个算子切成 N 份,
 * 结果按任务序返回 —— 调用方据此做确定性归并,保证"并行度 1 与 &gt;1 逐行同序"。</p>
 */
final class Parallel {

    /** 少于该行数的算子并行不划算(线程调度开销大于收益)。 */
    static final int MIN_ROWS = 2048;

    private Parallel() {
    }

    /** 实际走了并行的任务数(单测用来证明"并行路径确实被执行",不影响执行语义)。 */
    private static final java.util.concurrent.atomic.AtomicLong PARALLEL_TASKS =
            new java.util.concurrent.atomic.AtomicLong();

    static void resetParallelTaskCount() {
        PARALLEL_TASKS.set(0);
    }

    static long parallelTaskCount() {
        return PARALLEL_TASKS.get();
    }

    /** 并行度按任务数收敛:并发度不超过任务数,也不超过配置值。 */
    static int tasks(int parallelism, int desired) {
        int n = Math.min(Math.max(1, parallelism), Math.max(1, desired));
        return n;
    }

    /**
     * 把 {@code [0, tasks)} 交给线程池并行求值,按**任务下标顺序**返回结果。
     *
     * <p>{@code parallelism <= 1} 或只有 1 个任务时走原顺序路径(FR-718)。</p>
     */
    static <T> List<T> run(int parallelism, int tasks, java.util.function.IntFunction<T> body) {
        if (tasks <= 0) {
            return new ArrayList<>();
        }
        int concurrency = Math.min(Math.max(1, parallelism), tasks);
        if (concurrency <= 1 || tasks == 1) {
            List<T> out = new ArrayList<>(tasks);
            for (int i = 0; i < tasks; i++) {
                out.add(body.apply(i));
            }
            return out;
        }
        PARALLEL_TASKS.addAndGet(tasks);
        List<Callable<T>> callables = new ArrayList<>(tasks);
        for (int i = 0; i < tasks; i++) {
            final int index = i;
            callables.add(() -> body.apply(index));
        }
        List<Future<T>> futures = ForkJoinPool.commonPool().invokeAll(callables);
        List<T> out = new ArrayList<>(tasks);
        for (Future<T> future : futures) {
            try {
                out.add(future.get());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new StoreException("并行算子被中断", e);
            } catch (ExecutionException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException runtime) {
                    throw runtime;
                }
                if (cause instanceof Error error) {
                    throw error;
                }
                throw new StoreException("并行算子失败: " + cause.getMessage(), cause);
            }
        }
        return out;
    }

    /** 把一个长度切成 {@code tasks} 段,返回每段的 [from, to)。 */
    static int[][] ranges(int size, int tasks) {
        int n = Math.max(1, tasks);
        int[][] out = new int[n][2];
        int base = size / n;
        int extra = size % n;
        int pos = 0;
        for (int i = 0; i < n; i++) {
            int len = base + (i < extra ? 1 : 0);
            out[i][0] = pos;
            out[i][1] = pos + len;
            pos += len;
        }
        return out;
    }
}
