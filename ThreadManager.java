package com.vcc.pool.core.base;

import android.support.annotation.NonNull;

import com.vcc.pool.core.ITask;
import com.vcc.pool.core.PoolData;
import com.vcc.pool.core.network.NetworkStatus;
import com.vcc.pool.util.PoolLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

public class ThreadManager implements PoolData {
    private final String TAG = ThreadManager.class.getSimpleName();

    private ExecutorService executorService;
    private List<BaseWorker> penddingTasks;
    private List<BaseWorker> runningTasks;
    private ITask callback;
    private int runningTaskCount = 0;
    private int maximumThread = BackgroundConfig.MAX_THREAD;

    public ThreadManager(@NonNull ITask callback) {
        executorService = Executors.newFixedThreadPool(maximumThread);
        this.callback = callback;
        penddingTasks = new ArrayList<>();
        runningTasks = new ArrayList<>();
    }

    public void clear() {
        if (penddingTasks != null) {
            penddingTasks.clear();
        }
        if (runningTasks != null) {
            runningTasks.clear();
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    //quannk thêm synchronized
    public synchronized void addTask(BaseWorker task) {
        if (task != null) {
            boolean isNeedAdd = true;
            if (runningTasks.contains(task.id)) {
                isNeedAdd = false;
            }
            if (isNeedAdd && penddingTasks.size() > 0) {
                switch (task.id) {
                    case UPLOAD:
                    case SEND_REQUEST:
                        for (int i = 0; i < penddingTasks.size(); i++) {
                            BaseWorker item = penddingTasks.get(i);
                            PoolLogger.i(TAG, "pending task id:" + item.id.name());
                            if (item.id == task.id && item.backgroundType == task.backgroundType && task.backgroundType != BaseWorker.BACKGROUND_TYPE_NOTHING) {
                                isNeedAdd = false;
                                break;
                            }
                        }
                        break;
                    case UPLOAD_ADD:
                    case RANKING:
                    case GET_DATA:
                    case REMOTE_LONG_TERM:
                    case ACTION_ADD:
                    case ACTION:
                    case LOCAL_CACHE_UPDATE:
                    case REMOTE_SHORT_TERM:
                    case LOCAL_INSERT_RANK:
                    case LOCAL_REMOVE_RANK:
                        for (int i = 0; i < penddingTasks.size(); i++) {
                            BaseWorker item = penddingTasks.get(i);
                            if (item.id == task.id) {
                                isNeedAdd = false;
                                break;
                            }
                        }
                        break;
                }
            }
            if (isNeedAdd) {
                penddingTasks.add(task);
                PoolLogger.i(TAG, String.format("addTask : task[%s] - type[%s]", task.id.name(), task.backgroundType));
                runTask();
            } else {
                PoolLogger.i(TAG, String.format("not add Task : task[%s] - type[%s]", task.id.name(), task.backgroundType));
            }
        } else {
            PoolLogger.i(TAG, String.format("addTask : NullPointException task"));
        }
    }

    public void completeTask(BaseWorker worker) {
        runningTaskCount--;
        runningTasks.remove(worker);
        if (worker.id != TaskID.UPLOAD && worker.id != TaskID.SEND_REQUEST)
            runTask();
        PoolLogger.d(TAG, String.format("completeTask : task[%s]", worker.id.name()));
        PoolLogger.d(TAG, "runningTaskCount-- = " + runningTaskCount);
    }

    //quannk thêm synchronized
    private synchronized boolean validRunningTask(List<TaskID> ids) {
        if (ids == null) return false;
        for (int i = 0; i < runningTasks.size(); i++) {
            BaseWorker worker = runningTasks.get(i);
            if (worker != null) {
                for (int j = 0; j < ids.size(); j++) {
                    TaskID id = ids.get(j);
                    if (worker.id == id) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private boolean validRunningTask(List<TaskID> ids, int backgroundType) {
        if (ids == null) return false;
        for (int i = 0; i < runningTasks.size(); i++) {
            BaseWorker worker = runningTasks.get(i);
            if (worker != null) {
                for (int j = 0; j < ids.size(); j++) {
                    TaskID id = ids.get(j);
                    if (worker.id == id && worker.backgroundType == backgroundType) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    //quannk thêm synchronized
    public synchronized void runTask() {
        if (penddingTasks.size() > 0) {
            if (runningTaskCount < maximumThread) {
                BaseWorker worker = null;
                for (int i = 0; i < penddingTasks.size(); i++) {
                    final BaseWorker task = penddingTasks.get(i);
                    boolean isValid = false;
                    switch (task.id) {
                        case REMOTE_SHORT_TERM:
                        case REMOTE_LONG_TERM:
                            NetworkStatus networkStatus = callback.getNetworkState();
                            if (networkStatus != null && networkStatus.isConnected && validRunningTask(new ArrayList<TaskID>() {{
                                add(task.id);
                            }})) {
                                isValid = true;
                            }
                            break;
                        case NONE:
                            break;
                        case RANKING:
                        case ACTION_ADD:
                        case GET_DATA:
                        case LOCAL_CACHE_UPDATE:
                            if (validRunningTask(new ArrayList<TaskID>() {{
                                add(task.id);
                                add(TaskID.LOCAL_REMOVE_RANK);
                            }})) {
                                isValid = true;
                            }
                            break;
                        case LOCAL_REMOVE_RANK:
                            if (validRunningTask(new ArrayList<TaskID>() {{
                                add(task.id);
                                add(TaskID.LOCAL_CACHE_UPDATE);
                                add(TaskID.GET_DATA);
                                add(TaskID.RANKING);
                                add(TaskID.ACTION_ADD);
                            }})) {
                                isValid = true;
                            }
                            break;
                        case UPLOAD:
                        case SEND_REQUEST:
                            if (validRunningTask(new ArrayList<TaskID>() {{
                                add(task.id);
                            }}, task.backgroundType)) {
                                isValid = true;
                            }
                            break;
                        default:
                            isValid = true;
                            break;
                    }
                    if (isValid) {
                        if (worker == null) {
                            worker = task;
                        } else {
                            if (worker.priority.ordinal() < task.priority.ordinal()) {
                                worker = task;
                            }
                        }
                    }
                }
                if (worker != null) {
                    if (penddingTasks.indexOf(worker) != -1)
                        penddingTasks.remove(worker);
                    else PoolLogger.d("worker not exits in pendingTasks");
                    runningTasks.add(worker);
                    PoolLogger.i(TAG, String.format("Run task[%s]", worker.id.name()));

                    runningTaskCount++;
                    PoolLogger.d(TAG, "runningTaskCount++ = " + runningTaskCount);

                    final BaseWorker task = worker;
                    executorService.submit(new Runnable() {
                        @Override
                        public void run() {
                            task.run();
                        }
                    });

                } else {
                    PoolLogger.i(TAG, "not found valid task");
                }
            } else {
                PoolLogger.i(TAG, "Full Thread, Need wait");
            }
        } else {
            PoolLogger.i(TAG, "No task to run");
        }
    }
}
