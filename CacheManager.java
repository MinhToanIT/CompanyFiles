package com.vcc.pool.core.storage;

import com.vcc.pool.core.PoolData;
import com.vcc.pool.core.base.BaseWorker;
import com.vcc.pool.core.storage.db.action.Action;
import com.vcc.pool.core.storage.db.rank.Ranking;
import com.vcc.pool.core.storage.db.upload.Upload;
import com.vcc.pool.core.task.RemoteSendRequestTask;
import com.vcc.pool.core.task.UploadFileTask;
import com.vcc.pool.util.PoolLogger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class CacheManager {
    private final String TAG = CacheManager.class.getSimpleName();

    private List<Ranking> rankings;
    private List<Action> actions;
    private List<Upload> uploads;
    private Map<Integer, List<String>> showIdsMap;
    private Map<Integer, List<List<Integer>>> patternMap;
    private List<Integer> getFirstDataIdsMap;
    private List<BaseWorker> tasks;

    private boolean clientWaitData;
    private PoolData.Mode mode;

    public CacheManager() {
        rankings = new ArrayList<>();
        actions = new ArrayList<>();
        uploads = new ArrayList<>();
        tasks = new ArrayList<>();
        showIdsMap = new HashMap<>();
        patternMap = new HashMap<>();
        getFirstDataIdsMap = new ArrayList<>();
        clientWaitData = false;
        mode = PoolData.Mode.RANK_FORMULA;
    }

    public void clear() {
        clearSwitchDatabase();
        if (patternMap != null) {
            patternMap.clear();
        }
        if (showIdsMap != null) {
            showIdsMap.clear();
        }
    }

    public void clearSwitchDatabase() {
        if (rankings != null) {
            rankings.clear();
        }
        if (actions != null) {
            actions.clear();
        }
        clientWaitData = false;
    }

    /*
     * Area : mode function
     */
    public void changeMode(PoolData.Mode mode) {
        this.mode = mode;
    }

    public PoolData.Mode getMode() {
        return mode;
    }

    /*
     * Area : clientWaitData function
     */
    public void changeClientWaitState(boolean isWait) {
        clientWaitData = isWait;
    }

    public boolean isClientWait() {
        return clientWaitData;
    }

    /*
     * Area : ranking list function
     */

    public void setRankings(List<Ranking> rankings) {
        this.rankings = rankings;
    }

    public boolean isRankEmpty() {
        return !(rankings != null && rankings.size() > 0);
    }

    public List<Ranking> getRankings() {
        return rankings;
    }

    /*
     * Area : getFirstDataIdsMap function
     */
    public void registerFirstData(int id) {
        if (!getFirstDataIdsMap.contains(id)) {
            getFirstDataIdsMap.add(id);
        }
    }

    public void unregisterFirstData(int id) {
        if (getFirstDataIdsMap.contains(id)) {
            getFirstDataIdsMap.remove(id);
        }
    }

    public boolean isRegisterFirstData(int id) {
        return getFirstDataIdsMap.contains(id);
    }

    /*
     * Area : action list function
     */
    public void pushAction(Action action) {
        actions.add(action);
    }

    public List<Action> pullAction() {
        List<Action> result = new ArrayList<>();
        result.addAll(actions);
        actions.removeAll(result);
        return result;
    }

    /*
     * Area : upload list function
     */
    public void pushUpload(Upload upload) {
        uploads.add(upload);
    }

    public List<Upload> pullUpload() {
        List<Upload> result = new ArrayList<>();
        result.addAll(uploads);
        uploads.removeAll(result);
        return result;
    }

    /*
     * Area : task list function
     */
    public void pushTask(BaseWorker task) {
        boolean isNeedAdd = true;
        if (task instanceof UploadFileTask) {
            for (BaseWorker item : tasks) {
                if (item instanceof UploadFileTask) {
                    if (item.id == task.id && ((UploadFileTask) item).filePath.equals(((UploadFileTask) task).filePath)) {
                        isNeedAdd = false;
                        break;
                    }
                }
            }

        } else {
            for (BaseWorker item : tasks) {
                if (item instanceof RemoteSendRequestTask) {
                    if (item.id == task.id && ((RemoteSendRequestTask) item).item.id == ((RemoteSendRequestTask) task).item.id) {
                        isNeedAdd = false;
                        break;
                    }
                }
            }
        }
        if (isNeedAdd)
            tasks.add(task);
        PoolLogger.i(TAG, "task list size:" + tasks.size());
    }

    public List<BaseWorker> pullTask() {
        return tasks;
    }

    /*
     * Area : pattern list function
     */
    public void setPattern(int id, List<List<Integer>> pattern) {
        if (pattern == null) {
            patternMap.put(id, new ArrayList<List<Integer>>());
        } else {
            patternMap.put(id, pattern);
        }
    }

    public List<List<Integer>> getPattern(int id) {
        if (patternMap.containsKey(id)) {
            return patternMap.get(id);
        }
        return null;
    }

    /*
     * Area : Show ids list function
     */
    public int getAvailableCount(int id, int breakPoint) {
        if (showIdsMap.containsKey(id)) {
            List<String> showIds = showIdsMap.get(id);
            if (showIds != null) {
                int avaiableCount = 0;
                for (int i = 0; i < rankings.size(); i++) {
                    Ranking item = rankings.get(i);
                    if (item != null && !showIds.contains(item.id + "")) {
                        avaiableCount++;
                    }
                    if (breakPoint > 0 && avaiableCount >= breakPoint) {
                        break;
                    }
                }
                return avaiableCount;
            } else {
                PoolLogger.d(TAG, String.format("NullPointException : list showids null with id[%s]", id));
            }
        } else {
            PoolLogger.d(TAG, String.format("getAvailableCount Map not found id[%s]", id));
        }
        return 0;
    }

    public void updateId(int id) {
        if (!showIdsMap.containsKey(id)) {
            showIdsMap.put(id, new ArrayList<String>());
        }
    }

    public void clearListShow(int id) {
        if (showIdsMap.containsKey(id)) {
            showIdsMap.get(id).clear();
            showIdsMap.put(id, new ArrayList<String>());
        } else {
            PoolLogger.d(TAG, String.format("clearListShow Map ids not found id[%s]", id));
        }
    }

    public List<String> getListShoyById(int id) {
        if (showIdsMap.containsKey(id)) {
            return showIdsMap.get(id);
        } else {
            PoolLogger.d(TAG, String.format("getListShoyById Map ids not found id[%s]", id));
            return null;
        }
    }

    public void setListShowById(int id, List<String> data) {
        if (showIdsMap.containsKey(id)) {
            clearListShow(id);
            showIdsMap.put(id, data);
        } else {
            PoolLogger.d(TAG, String.format("putListShowById Map ids not found id[%s]", id));
        }
    }

    public void putListShowById(int id, List<String> data) {
        if (showIdsMap.containsKey(id)) {
            List<String> temp = showIdsMap.get(id);
            temp.addAll(data);
            showIdsMap.put(id, temp);
        } else {
            PoolLogger.d(TAG, String.format("putListShowById Map ids not found id[%s]", id));
        }
    }

    public List<String> getAllShowIds() {
        List<String> result = new ArrayList<>();
        Set<Integer> keys = showIdsMap.keySet();
        for (Integer key : keys) {
            List<String> ids = showIdsMap.get(key);
            if (ids != null && ids.size() > 0) {
                for (String id : ids) {
                    if (!result.contains(id)) {
                        result.add(id);
                    }
                }
            }
        }
        return result;
    }
}
