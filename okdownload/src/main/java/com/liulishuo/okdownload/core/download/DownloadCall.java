/*
 * Copyright (c) 2017 LingoChamp Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.liulishuo.okdownload.core.download;

import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.liulishuo.okdownload.DownloadTask;
import com.liulishuo.okdownload.OkDownload;
import com.liulishuo.okdownload.core.NamedRunnable;
import com.liulishuo.okdownload.core.Util;
import com.liulishuo.okdownload.core.breakpoint.BlockInfo;
import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo;
import com.liulishuo.okdownload.core.breakpoint.DownloadStore;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;
import com.liulishuo.okdownload.core.file.MultiPointOutputStream;
import com.liulishuo.okdownload.core.file.ProcessFileStrategy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

public class DownloadCall extends NamedRunnable implements Comparable<DownloadCall> {
    private static final ExecutorService EXECUTOR = new ThreadPoolExecutor(0, Integer.MAX_VALUE,
            60, TimeUnit.SECONDS, new SynchronousQueue<Runnable>(),
            Util.threadFactory("OkDownload Block", false));

    private static final String TAG = "DownloadCall";

    static final int MAX_COUNT_RETRY_FOR_PRECONDITION_FAILED = 1;
    public final DownloadTask task;
    public final boolean asyncExecuted;
    @NonNull final ArrayList<DownloadChain> blockChainList;

    @Nullable volatile DownloadCache cache;
    volatile boolean canceled;
    volatile boolean finishing;

    volatile Thread currentThread;
    @NonNull private final DownloadStore store;

    private DownloadCall(DownloadTask task, boolean asyncExecuted, @NonNull DownloadStore store) {
        this(task, asyncExecuted, new ArrayList<DownloadChain>(), store);
    }

    DownloadCall(DownloadTask task, boolean asyncExecuted,
                 @NonNull ArrayList<DownloadChain> runningBlockList,
                 @NonNull DownloadStore store) {
        super("download call: " + task.getId());
        this.task = task;
        this.asyncExecuted = asyncExecuted;
        this.blockChainList = runningBlockList;
        this.store = store;
    }

    public static DownloadCall create(DownloadTask task, boolean asyncExecuted,
                                      @NonNull DownloadStore store) {
        return new DownloadCall(task, asyncExecuted, store);
    }

    public boolean cancel() {
        synchronized (this) {
            if (canceled) return false;
            if (finishing) return false;
            this.canceled = true;
        }

        final long startCancelTime = SystemClock.uptimeMillis();

        OkDownload.with().downloadDispatcher().flyingCanceled(this);

        final DownloadCache cache = this.cache;
        if (cache != null) cache.setUserCanceled();

        // ArrayList#clone is not a thread safe operation,
        // so chains#size may > chains#elementData.length and this will cause
        // ConcurrentModificationException during iterate the ArrayList(ArrayList#next).
        // This is a reproduce example:
        // https://repl.it/talk/share/ConcurrentModificationException/18566.
        // So don't use clone anymore.
        final Object[] chains = blockChainList.toArray();
        if (chains == null || chains.length == 0) {
            if (currentThread != null) {
                Util.d(TAG,
                        "interrupt thread with cancel operation because of chains are not running "
                                + task.getId());
                currentThread.interrupt();
            }
        } else {
            for (Object chain : chains) {
                if (chain instanceof DownloadChain) {
                    ((DownloadChain) chain).cancel();
                }
            }
        }

        if (cache != null) cache.getOutputStream().cancelAsync();

        Util.d(TAG, "cancel task " + task.getId() + " consume: " + (SystemClock
                .uptimeMillis() - startCancelTime) + "ms");
        return true;
    }

    public boolean isCanceled() { return canceled; }

    public boolean isFinishing() { return finishing; }
            // 1.判断当前任务的下载链接长度是否大于0，否则就抛出异常；2.从缓存中获取任务的断点信息，若没有断点信息，则创建断点信息并保存至数据库；
            // 3.创建带缓存的下载输出流；4.访问下载链接判断断点信息是否合理；5.确定文件路径后等待文件锁释放；
            // 6. 判断缓存中是否有相同的任务，若有则复用缓存中的任务的分块信息；
            // 7.检查断点信息是否是可恢复的，若不可恢复，则根据文件大小进行分块，重新下载，否则继续进行下一步；
            // 8.判断断点信息是否是脏数据（文件存在且断点信息正确且下载链接支持断点续传）；
            // 9.若是脏数据则根据文件大小进行分块，重新开始下载，否则从断点位置开始下载；10.开始下载。

    @Override
    public void execute() throws InterruptedException {
        currentThread = Thread.currentThread();

        boolean retry;
        int retryCount = 0;

        // ready param
        final OkDownload okDownload = OkDownload.with();
        final ProcessFileStrategy fileStrategy = okDownload.processFileStrategy();

        // inspect task start
        // 重置store中的taskId，回调taskStart()
        inspectTaskStart();
        do {
            // 0. check basic param before start
            //1.判断当前任务的下载链接长度是否大于0，否则就抛出异常；
            if (task.getUrl().length() <= 0) {
                this.cache = new DownloadCache.PreError(
                        new IOException("unexpected url: " + task.getUrl()));
                break;
            }

            if (canceled) break;

            // 1. create basic info if not exist
            @NonNull final BreakpointInfo info;
            try {
                //2.从缓存中获取任务的断点信息，若没有断点信息，则创建断点信息并保存至数据库
                BreakpointInfo infoOnStore = store.get(task.getId());
                if (infoOnStore == null) {
                    info = store.createAndInsert(task);
                } else {
                    info = infoOnStore;
                }
                setInfoToTask(info);
            } catch (IOException e) {
                this.cache = new DownloadCache.PreError(e);
                break;
            }
            if (canceled) break;

            // ready cache.
            // 3.创建带缓存的下载输出流；
            @NonNull final DownloadCache cache = createCache(info);
            this.cache = cache;

            // 0. check basic param before start，检查url和是否已被取消
            // 1. create basic info if not exist，通过store创建或者取出断点信息(taskId，自增创建的)
            // 2. remote check.校验文件大小，是否支持断点续传，是否需要重定向，设置blockInfo
            // 原文链接：https://blog.csdn.net/ZHENZHEN9310/article/details/103316496
            // 2. remote check.
            // 4.访问下载链接判断断点信息是否合理；
            final BreakpointRemoteCheck remoteCheck = createRemoteCheck(info);
            try {
                remoteCheck.check();
            } catch (IOException e) {
                cache.catchException(e);
                break;
            }
            cache.setRedirectLocation(task.getRedirectLocation());

            // 3. waiting for file lock release after file path is confirmed.
            //5.确定文件路径后等待文件锁释放；
            fileStrategy.getFileLock().waitForRelease(task.getFile().getAbsolutePath());

            // 4. reuse another info if another info is idle and available for reuse.
            // 6. 判断缓存中是否有相同的任务，若有则复用缓存中的任务的分块信息；
            OkDownload.with().downloadStrategy()
                    .inspectAnotherSameInfo(task, info, remoteCheck.getInstanceLength());

            try {
                //7.检查断点信息是否是可恢复的，若不可恢复，则根据文件大小进行分块，重新下载，否则继续进行下一步；
                if (remoteCheck.isResumable()) {
                    // 5. local check
                    // 8.判断断点信息是否是脏数据（文件存在且断点信息正确且下载链接支持断点续传）；
                    final BreakpointLocalCheck localCheck = createLocalCheck(info,
                            remoteCheck.getInstanceLength());
                    localCheck.check();
                    // 9.若是脏数据则根据文件大小进行分块，重新开始下载，否则从断点位置开始下载；
                    if (localCheck.isDirty()) {
                        Util.d(TAG, "breakpoint invalid: download from beginning because of "
                                + "local check is dirty " + task.getId() + " " + localCheck);
                        // 6. assemble block data
                        fileStrategy.discardProcess(task);
                        assembleBlockAndCallbackFromBeginning(info, remoteCheck,
                                localCheck.getCauseOrThrow());
                    } else {
                        okDownload.callbackDispatcher().dispatch()
                                .downloadFromBreakpoint(task, info);
                    }
                } else {
                    Util.d(TAG, "breakpoint invalid: download from beginning because of "
                            + "remote check not resumable " + task.getId() + " " + remoteCheck);
                    // 6. assemble block data
                    fileStrategy.discardProcess(task);
                    assembleBlockAndCallbackFromBeginning(info, remoteCheck,
                            remoteCheck.getCauseOrThrow());
                }
            } catch (IOException e) {
                cache.setUnknownError(e);
                break;
            }

            // 7. start with cache and info.
            // 10. 开始下载
            start(cache, info);

            if (canceled) break;

            // 8. retry if precondition failed.
            // 11. 错误重试机制
            if (cache.isPreconditionFailed()
                    && retryCount++ < MAX_COUNT_RETRY_FOR_PRECONDITION_FAILED) {
                store.remove(task.getId());
                retry = true;
            } else {
                retry = false;
            }
        } while (retry);

        // finish
        finishing = true;
        blockChainList.clear();

        final DownloadCache cache = this.cache;
        if (canceled || cache == null) return;

        final EndCause cause;
        Exception realCause = null;
        if (cache.isServerCanceled() || cache.isUnknownError()
                || cache.isPreconditionFailed()) {
            // error
            cause = EndCause.ERROR;
            realCause = cache.getRealCause();
        } else if (cache.isFileBusyAfterRun()) {
            cause = EndCause.FILE_BUSY;
        } else if (cache.isPreAllocateFailed()) {
            cause = EndCause.PRE_ALLOCATE_FAILED;
            realCause = cache.getRealCause();
        } else {
            cause = EndCause.COMPLETED;
        }
        inspectTaskEnd(cache, cause, realCause);
    }

    private void inspectTaskStart() {
        store.onTaskStart(task.getId());
        OkDownload.with().callbackDispatcher().dispatch().taskStart(task);
    }

    private void inspectTaskEnd(DownloadCache cache, @NonNull EndCause cause,
                                @Nullable Exception realCause) {
        // non-cancel handled on here
        if (cause == EndCause.CANCELED) {
            throw new IllegalAccessError("can't recognize cancelled on here");
        }

        synchronized (this) {
            if (canceled) return;
            finishing = true;
        }

        store.onTaskEnd(task.getId(), cause, realCause);
        if (cause == EndCause.COMPLETED) {
            store.markFileClear(task.getId());
            OkDownload.with().processFileStrategy()
                    .completeProcessStream(cache.getOutputStream(), task);
        }

        OkDownload.with().callbackDispatcher().dispatch().taskEnd(task, cause, realCause);
    }

    // this method is convenient for unit-test.
    DownloadCache createCache(@NonNull BreakpointInfo info) {
        final MultiPointOutputStream outputStream = OkDownload.with().processFileStrategy()
                .createProcessStream(task, info, store);
        return new DownloadCache(outputStream);
    }

    // this method is convenient for unit-test.
    int getPriority() {
        return task.getPriority();
    }
    // 可以看到它是分块下载的，每一个分块都是一个DownloadChain实例，DownloadChain实现了Runnable接口，继续看startBlocks方法:
    void start(final DownloadCache cache, BreakpointInfo info) throws InterruptedException {
        final int blockCount = info.getBlockCount();
        final List<DownloadChain> blockChainList = new ArrayList<>(info.getBlockCount());
        final List<Integer> blockIndexList = new ArrayList<>();
        for (int i = 0; i < blockCount; i++) {
            final BlockInfo blockInfo = info.getBlock(i);
            if (Util.isCorrectFull(blockInfo.getCurrentOffset(), blockInfo.getContentLength())) {
                continue;
            }

            Util.resetBlockIfDirty(blockInfo);
            final DownloadChain chain = DownloadChain.createChain(i, task, info, cache, store);
            blockChainList.add(chain);
            blockIndexList.add(chain.getBlockIndex());
        }

        if (canceled) {
            return;
        }

        cache.getOutputStream().setRequireStreamBlocks(blockIndexList);

        startBlocks(blockChainList);
    }

    @Override
    protected void interrupted(InterruptedException e) {
    }

    @Override
    protected void finished() {
        OkDownload.with().downloadDispatcher().finish(this);
        Util.d(TAG, "call is finished " + task.getId());
    }
    // 对于每一个分块任务，都调用了submitChain方法，由一个线程池去处理每一个DownloadChain分块，核心代码就在这里:
    void startBlocks(List<DownloadChain> tasks) throws InterruptedException {
        ArrayList<Future> futures = new ArrayList<>(tasks.size());
        try {
            for (DownloadChain chain : tasks) {
                futures.add(submitChain(chain));
            }

            blockChainList.addAll(tasks);

            for (Future future : futures) {
                if (!future.isDone()) {
                    try {
                        future.get();
                    } catch (CancellationException | ExecutionException ignore) { }
                }
            }
        } catch (Throwable t) {
            for (Future future : futures) {
                future.cancel(true);
            }
            throw t;
        } finally {
            blockChainList.removeAll(tasks);
        }
    }

    // convenient for unit-test
    @NonNull BreakpointLocalCheck createLocalCheck(@NonNull BreakpointInfo info,
                                                   long responseInstanceLength) {
        return new BreakpointLocalCheck(task, info, responseInstanceLength);
    }

    // convenient for unit-test
    @NonNull BreakpointRemoteCheck createRemoteCheck(@NonNull BreakpointInfo info) {
        return new BreakpointRemoteCheck(task, info);
    }

    // convenient for unit-test
    void setInfoToTask(@NonNull BreakpointInfo info) {
        DownloadTask.TaskHideWrapper.setBreakpointInfo(task, info);
    }

    void assembleBlockAndCallbackFromBeginning(@NonNull BreakpointInfo info,
                                               @NonNull BreakpointRemoteCheck remoteCheck,
                                               @NonNull ResumeFailedCause failedCause) {
        Util.assembleBlock(task, info, remoteCheck.getInstanceLength(),
                remoteCheck.isAcceptRange());
        OkDownload.with().callbackDispatcher().dispatch()
                .downloadFromBeginning(task, info, failedCause);
    }

    Future<?> submitChain(DownloadChain chain) {
        return EXECUTOR.submit(chain);
    }

    public boolean equalsTask(@NonNull DownloadTask task) {
        return this.task.equals(task);
    }

    @Nullable public File getFile() {
        return this.task.getFile();
    }

    @SuppressFBWarnings(value = "Eq", justification = "This special case is just for task priority")
    @Override
    public int compareTo(@NonNull DownloadCall o) {
        return o.getPriority() - getPriority();
    }
}
