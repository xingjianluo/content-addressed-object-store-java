package com.github.xingjianluo.contentaddressedobjectstorejava.store;

import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 存储全局并发控制（单进程、单根目录）。
 *
 * <ul>
 *   <li>读操作（read / readVersion / readSnapshot / 查询）：持读锁；</li>
 *   <li>多阶段写入（put）：从 stage 到 journal 登记、版本提交、journal 完成的整个流程持读锁，
 *       期间保证引用内容不被 GC；</li>
 *   <li>GC：持写锁，与一切读/写互斥，确保 mark 阶段看到的引用集合在 sweep 期间不变化，
 *       绝不删除“正在被引用”的内容；</li>
 *   <li>版本条件提交的“检查 + 指针推进”临界区在 put 流程内升级为写锁语义——
 *       put 流程先持读锁，提交时使用一把独立的提交互斥锁，保证并发提交严格串行、
 *       条件判断原子生效，同时读操作不会读到中间状态（指针原子切换）。</li>
 * </ul>
 *
 * <p>采用公平锁，避免竞争写入或回收线程饥饿。</p>
 */
public final class StoreConcurrency {

    private final ReadWriteLock lock = new ReentrantReadWriteLock(true);
    private final Object commitMonitor = new Object();

    public void acquireRead() {
        lock.readLock().lock();
    }

    public void releaseRead() {
        lock.readLock().unlock();
    }

    public void acquireWrite() {
        lock.writeLock().lock();
    }

    public void releaseWrite() {
        lock.writeLock().unlock();
    }

    /** 在提交互斥区内执行动作，返回其结果。 */
    public <T> T withCommit(CommitAction<T> action) throws java.io.IOException {
        synchronized (commitMonitor) {
            return action.run();
        }
    }

    @FunctionalInterface
    public interface CommitAction<T> {
        T run() throws java.io.IOException;
    }
}
