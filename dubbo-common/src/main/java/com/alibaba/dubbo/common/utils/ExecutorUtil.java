/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alibaba.dubbo.common.utils;

import com.alibaba.dubbo.common.Constants;
import com.alibaba.dubbo.common.URL;
import com.alibaba.dubbo.common.logger.Logger;
import com.alibaba.dubbo.common.logger.LoggerFactory;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
/*
 线程池五种状态:
（1）RUNNING：线程池创建之后的初始状态，这种状态下可以执行任务。
执行shutdown（）
（2）SHUTDOWN：该状态下线程池不再接受新任务，但是会将工作队列中的任务执行完毕。
执行shutdownNow（）
（3）STOP：该状态下线程池不再接受新任务，也不会处理工作队列中的剩余任务，并且将会中断所有工作线程。
所有任务停止
（4）TIDYING：该状态下所有任务都已终止或者处理完成，将会执行terminated()钩子方法。
执行terminated()
（5）TERMINATED：执行完terminated()钩子方法之后的状态
 */
public class ExecutorUtil {
    private static final Logger logger = LoggerFactory.getLogger(ExecutorUtil.class);
    private static final ThreadPoolExecutor shutdownExecutor = new ThreadPoolExecutor(0, 1,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(100),
            new NamedThreadFactory("Close-ExecutorService-Timer", true));

    public static boolean isTerminated(Executor executor) {
        if (executor instanceof ExecutorService) {
            if (((ExecutorService) executor).isTerminated()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Use the shutdown pattern from:
     *  https://docs.oracle.com/javase/8/docs/api/java/util/concurrent/ExecutorService.html
     * @param executor the Executor to shutdown
     * @param timeout the timeout in milliseconds before termination
     *
     * dubbo优雅关闭线程池
     *
     */
    public static void gracefulShutdown(Executor executor, int timeout) {
        if (!(executor instanceof ExecutorService) || isTerminated(executor)) {
            return;
        }
        final ExecutorService es = (ExecutorService) executor;
        try {
            // Disable new tasks from being submitted
            // shutdown()执行后，不再接收新任务，
            es.shutdown();
        } catch (SecurityException ex2) {
            return;
        } catch (NullPointerException ex2) {
            return;
        }
        try {
            // Wait a while for existing tasks to terminate
            // 设置超时等待，等待当前线程池中的任务执行完
            if (!es.awaitTermination(timeout, TimeUnit.MILLISECONDS)) {
                //shutdownNow()会中断当前的执行任务
                es.shutdownNow();
            }
        } catch (InterruptedException ex) {
            es.shutdownNow();
            Thread.currentThread().interrupt();
        }
        //判断线程池是否关闭，若没有，进入循环关闭，补充执行awaitTermination方法
        if (!isTerminated(es)) {
            newThreadToCloseExecutor(es);
        }
    }

    public static void shutdownNow(Executor executor, final int timeout) {
        if (!(executor instanceof ExecutorService) || isTerminated(executor)) {
            return;
        }
        final ExecutorService es = (ExecutorService) executor;
        try {
            es.shutdownNow();
        } catch (SecurityException ex2) {
            return;
        } catch (NullPointerException ex2) {
            return;
        }
        try {
            es.awaitTermination(timeout, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        if (!isTerminated(es)) {
            newThreadToCloseExecutor(es);
        }
    }

    private static void newThreadToCloseExecutor(final ExecutorService es) {
        if (!isTerminated(es)) {
            shutdownExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        //循环1000次，每次等待10ms，来判断当前线程池的任务是否全部关闭
                        for (int i = 0; i < 1000; i++) {
                            es.shutdownNow();
                            if (es.awaitTermination(10, TimeUnit.MILLISECONDS)) {
                                break;
                            }
                        }
                    } catch (InterruptedException ex) {
                        Thread.currentThread().interrupt();
                    } catch (Throwable e) {
                        logger.warn(e.getMessage(), e);
                    }
                }
            });
        }
    }

    /**
     * append thread name with url address
     *
     * @return new url with updated thread name
     */
    public static URL setThreadName(URL url, String defaultName) {
        String name = url.getParameter(Constants.THREAD_NAME_KEY, defaultName);
        name = name + "-" + url.getAddress();
        url = url.addParameter(Constants.THREAD_NAME_KEY, name);
        return url;
    }
}
