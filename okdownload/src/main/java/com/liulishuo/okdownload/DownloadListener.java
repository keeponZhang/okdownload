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

package com.liulishuo.okdownload;

import android.support.annotation.IntRange;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;

import com.liulishuo.okdownload.core.breakpoint.BreakpointInfo;
import com.liulishuo.okdownload.core.cause.EndCause;
import com.liulishuo.okdownload.core.cause.ResumeFailedCause;

import java.util.List;
import java.util.Map;

/**
 * @see com.liulishuo.okdownload.core.listener.DownloadListener1
 * @see com.liulishuo.okdownload.core.listener.DownloadListener2
 * @see com.liulishuo.okdownload.core.listener.DownloadListener3
 * @see com.liulishuo.okdownload.core.listener.DownloadListener4
 * @see com.liulishuo.okdownload.core.listener.DownloadListener4WithSpeed
 */
public interface DownloadListener {
    //DownloadCall#execute 任务开始
    void taskStart(@NonNull DownloadTask task);

    /**
     * On start trial connect state.
     * <p/>
     * The trial connection is used for:
     * 1. check whether the local info is valid to resume downloading
     * 2. get the instance length of this resource.
     * 3. check whether the resource support accept range.
     *
     * @param task                the host task.
     * @param requestHeaderFields the request header fields for this connection.
     */
    void connectTrialStart(@NonNull DownloadTask task,
                           @NonNull Map<String, List<String>> requestHeaderFields);

    /**
     * On end trial connect state.
     * <p/>
     * The trial connection is used for:
     * 1. check whether the local info is valid to resume downloading
     * 2. get the instance length of this resource.
     * 3. check whether the resource support accept range.
     *
     * @param task                 the host task.
     * @param responseCode         the response code of this trial connection.
     * @param responseHeaderFields the response header fields for this trial connection.
     */
    void connectTrialEnd(@NonNull DownloadTask task,
                         int responseCode,
                         @NonNull Map<String, List<String>> responseHeaderFields);
    //DownloadCall#execute 从头开始
    void downloadFromBeginning(@NonNull DownloadTask task, @NonNull BreakpointInfo info,
                               @NonNull ResumeFailedCause cause);
    //DownloadCall#execute 从断点开始
    void downloadFromBreakpoint(@NonNull DownloadTask task, @NonNull BreakpointInfo info);
    //HeaderInterceptor#interceptConnect 创建链接开始
    void connectStart(@NonNull DownloadTask task, @IntRange(from = 0) int blockIndex,
                      @NonNull Map<String, List<String>> requestHeaderFields);
    //HeaderInterceptor#interceptConnect 创建链接结束
    void connectEnd(@NonNull DownloadTask task, @IntRange(from = 0) int blockIndex,
                    int responseCode,
                    @NonNull Map<String, List<String>> responseHeaderFields);
    //DownloadChain#start 开始下载
    void fetchStart(@NonNull DownloadTask task, @IntRange(from = 0) int blockIndex,
                    @IntRange(from = 0) long contentLength);
    //FetchDataInterceptor#interceptFetch->DownloadChain#flushNoCallbackIncreaseBytes 下载进度
    void fetchProgress(@NonNull DownloadTask task, @IntRange(from = 0) int blockIndex,
                       @IntRange(from = 0) long increaseBytes);
    //DownloadChain#start 下载结束
    void fetchEnd(@NonNull DownloadTask task, @IntRange(from = 0) int blockIndex,
                  @IntRange(from = 0) long contentLength);
    //DownloadCall#execute 任务结束
    void taskEnd(@NonNull DownloadTask task, @NonNull EndCause cause,
                 @Nullable Exception realCause);
}
