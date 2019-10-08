package com.vcc.pool.core;

import android.content.ContentResolver;
import android.content.Context;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;

import com.google.gson.Gson;
import com.vcc.pool.core.base.BaseWorker;
import com.vcc.pool.core.base.ThreadManager;
import com.vcc.pool.core.network.NetworkReceiver;
import com.vcc.pool.core.network.NetworkStatus;
import com.vcc.pool.core.storage.CacheManager;
import com.vcc.pool.core.storage.db.RankingRoomDatabase;
import com.vcc.pool.core.storage.db.action.Action;
import com.vcc.pool.core.storage.db.rank.Ranking;
import com.vcc.pool.core.storage.db.rank.RankingDAO;
import com.vcc.pool.core.storage.db.upload.Upload;
import com.vcc.pool.core.storage.db.upload.UploadDAO;
import com.vcc.pool.core.task.BGGetDataTask;
import com.vcc.pool.core.task.BGRankingTask;
import com.vcc.pool.core.task.LocalActionTask;
import com.vcc.pool.core.task.LocalCacheUpdateTask;
import com.vcc.pool.core.task.LocalRankTask;
import com.vcc.pool.core.task.LocalRemoveTask;
import com.vcc.pool.core.task.LocalUploadTask;
import com.vcc.pool.core.task.RemoteActionTask;
import com.vcc.pool.core.task.RemoteDataTask;
import com.vcc.pool.core.task.RemoteSendRequestTask;
import com.vcc.pool.core.task.UploadFileTask;
import com.vcc.pool.core.task.data.UploadTaskData;
import com.vcc.pool.util.PoolHelper;
import com.vcc.pool.util.PoolLogger;

import java.security.cert.CertificateException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import okhttp3.OkHttpClient;

public class PoolManager {
    /*
     * Variable
     */
    private final String TAG = PoolManager.class.getSimpleName();
    private final String SCHEDULE_REQUEST_HANDLER = "ScheduleCheckRequest";
    private final String SCHEDULE_REMOVE_HANDLER = "ScheduleCheckRemove";
    private final String SCHEDULE_ACTION_HANDLER = "ScheduleCheckAction";
    private final String SCHEDULE_UPLOAD_HANDLER = "ScheduleCheckUpload_";
    private final String SCHEDULE_UPLOAD_TYPE_HANDLER = SCHEDULE_UPLOAD_HANDLER + "%s";
    private final int UPLOAD_RAW_TEXT = 0x78952;

    private CacheManager cacheManager;
    private ThreadManager threadManager;

    private OkHttpClient client;
    private OkHttpClient clientUpload;
    private RankingRoomDatabase db;

    private OnCallbackFromTask onCallback;
    private IPoolManager iPoolManager;

    private NetworkReceiver networkReceiver;
    private NetworkStatus networkStatus;
    private ClientConfig clientConfig;
    private ContentResolver contentResolver;

    private Handler requestHandler;

    private Map<String, MySchedule> schedules;

    private long lastTimeRequest;
    private int delayTime;
    private int currentId;
//    private boolean isFirstLoad = true;

    /*
     * Singleton
     */
    private static PoolManager instance;

    public static PoolManager getInstance(Context context) {
        if (instance == null) {
            instance = new PoolManager(context);
        }
        return instance;
    }

    /*
     * Constructor
     */
    public PoolManager(Context context) {
        PoolLogger.i(TAG, "Pool initialize : begin 6 task need run");

        PoolLogger.i(TAG, "Pool initialize : 1.utility");
        onCallback = new OnCallbackFromTask();

        PoolLogger.i(TAG, "Pool initialize : 2.manager");
        cacheManager = new CacheManager();
        threadManager = new ThreadManager(onCallback);
        requestHandler = new Handler();

        schedules = new HashMap<>();
        initHandleSchedule(SCHEDULE_REQUEST_HANDLER, ScheduleCheckRequest.class);
        initHandleSchedule(SCHEDULE_REMOVE_HANDLER, ScheduleCheckRemove.class);
        initHandleSchedule(SCHEDULE_ACTION_HANDLER, ScheduleCheckAction.class);
        initHandleSchedule(String.format(SCHEDULE_UPLOAD_TYPE_HANDLER, UPLOAD_RAW_TEXT), ScheduleCheckUpload.class);
//        initHandleSchedule(String.format(SCHEDULE_UPLOAD_HANDLER, 0), ScheduleCheckUpload.class); // init raw text request no upload
//        initUploadType(UPLOAD_RAW_TEXT, true); // init raw text request no upload

        contentResolver = context.getContentResolver();

        PoolLogger.i(TAG, "Pool initialize : 3.remote");
//        client = new OkHttpClient.Builder()
//                .connectTimeout(PoolData.Network.CONNECT_TIMEOUT, TimeUnit.SECONDS)
//                .readTimeout(PoolData.Network.READ_TIMEOUT, TimeUnit.SECONDS)
//                .writeTimeout(PoolData.Network.WRITE_TIMEOUT, TimeUnit.SECONDS)
//                .retryOnConnectionFailure(true)
//                .build();
        client = getUnsafeOkHttpClient(PoolData.Network.DEFAULT_TIMEOUT);
        clientUpload = getUnsafeOkHttpClient(PoolData.Network.UPLOAD_TIMEOUT);
        delayTime = PoolData.BackgroundConfig.GET_DATA_TIME_LIMIT;

        PoolLogger.i(TAG, "Pool initialize : 4.network status initialize");
        initNetworkStatus(context);

        PoolLogger.i(TAG, "Pool initialize : 5.network handler");
        networkReceiver = new NetworkReceiver();
        networkReceiver.setCallback(new OnNetworkStateChange());
        try {
            context.registerReceiver(networkReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
        } catch (Exception e) {
            e.printStackTrace();
        }

        PoolLogger.i(TAG, "Pool initialize : 6.set default id");
        switchId(0);

        PoolLogger.i(TAG, "Pool initialize : done");
    }

    private void initHandleSchedule(String tag, Class clazz) {
        HandlerThread handlerThread = new HandlerThread(tag);
        handlerThread.start();
        Handler handler = new Handler(handlerThread.getLooper());

        Class[] types = new Class[]{PoolManager.class, String.class, Handler.class};
        Object[] parameters = new Object[]{this, tag, handler};
        Object object = PoolHelper.getObjectFromClass(clazz, types, parameters);
        if (object != null && object instanceof BaseSchedule) {
            PoolLogger.d(TAG, "initHandleSchedule success : " + tag);
            schedules.put(tag, new MySchedule(
                    handler, handlerThread, (BaseSchedule) object
            ));
        }
    }

    private OkHttpClient getUnsafeOkHttpClient(int timeout) {
        try {
            // Create a trust manager that does not validate certificate chains
            final TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        @Override
                        public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String authType) throws CertificateException {
                        }

                        @Override
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                            return new java.security.cert.X509Certificate[]{};
                        }
                    }
            };

            // Install the all-trusting trust manager
            final SSLContext sslContext = SSLContext.getInstance("SSL");
            sslContext.init(null, trustAllCerts, new java.security.SecureRandom());
            // Create an ssl socket factory with our all-trusting manager
            final SSLSocketFactory sslSocketFactory = sslContext.getSocketFactory();

            OkHttpClient.Builder builder = new OkHttpClient.Builder();
            builder.sslSocketFactory(sslSocketFactory, (X509TrustManager) trustAllCerts[0]);
            builder.hostnameVerifier(new HostnameVerifier() {
                @Override
                public boolean verify(String hostname, SSLSession session) {
                    return true;
                }
            });

            builder.retryOnConnectionFailure(true);
            builder.connectTimeout(timeout, TimeUnit.SECONDS);
            builder.readTimeout(timeout, TimeUnit.SECONDS);
            builder.writeTimeout(timeout, TimeUnit.SECONDS);

            OkHttpClient okHttpClient = builder.build();
            return okHttpClient;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /*
     * Public Function
     */
    public void destroy(Context context) {
        PoolLogger.i(TAG, "Pool clear : begin");
        if (networkReceiver != null) {
            try {
                context.unregisterReceiver(networkReceiver);
            } catch (Exception e) {
                e.printStackTrace();
            }
            networkReceiver = null;
        }
        if (requestHandler != null) {
            requestHandler.removeCallbacksAndMessages(null);
            requestHandler = null;
        }

        if (schedules != null) {
            Set<String> keys = schedules.keySet();
            if (keys != null && keys.size() > 0) {
                for (String key : keys) {
                    MySchedule schedule = schedules.get(key);
                    if (schedule == null) {
                        continue;
                    }
                    if (schedule.handlerThread != null) {
                        schedule.handlerThread.interrupt();
                    }
                    if (schedule.handler != null) {
                        schedule.handler.removeCallbacksAndMessages(null);
                    }
                }
            }
        }

        if (cacheManager != null) {
            cacheManager.clear();
            cacheManager = null;
        }
        if (threadManager != null) {
            threadManager.clear();
            threadManager = null;
        }
        client = null;
        onCallback = null;
        db = null;
        instance = null;
        RankingRoomDatabase.clear();
        PoolLogger.i(TAG, "Pool clear : done");
    }

    public String getPrefixUpload() {
        return PoolData.Configure.PARTTERN_PREFIX_UPLOAD_FILE;
    }

    public void setCallback(IPoolManager iPoolManager) {
        this.iPoolManager = iPoolManager;
    }

    public void setDelayTime(int delayTime) {
        this.delayTime = delayTime;
    }

    public void setMode(PoolData.Mode mode) {
        if (cacheManager == null) return;
        if (mode == null) {
            PoolLogger.i(TAG, "Set mode null : reject");
        }
        cacheManager.changeMode(mode);
    }

    public void switchId(int id) {
        currentId = id;
        if (cacheManager == null) return;
        cacheManager.updateId(id);
    }

    public void outLocal() {
        RankingRoomDatabase.clear();
        RankingRoomDatabase.unique = "";
    }

    public void inLocal(@NonNull Context context, @NonNull String unique) {
        if (RankingRoomDatabase.unique != null && RankingRoomDatabase.unique.length() > 0) {
            PoolLogger.w(TAG, "need call outLocal first");
            return;
        }
        RankingRoomDatabase.unique = unique;
        db = RankingRoomDatabase.getDatabase(context);
        cacheManager.clearSwitchDatabase();
        updatePreCache();
        getData(true);
    }

    public void setClientConfig(@NonNull ClientConfig clientConfig) {
        this.clientConfig = clientConfig;
        this.clientConfig.setRefresh(true);

        if (schedules != null) {
            Set<String> keys = schedules.keySet();
            if (keys != null && keys.size() > 0) {
                for (String key : keys) {
                    MySchedule schedule = schedules.get(key);
                    if (schedule == null) {
                        continue;
                    }
                    int delay = 0;
                    if (SCHEDULE_REQUEST_HANDLER.equals(key)) {
                        delay = clientConfig != null ? clientConfig.getCheckRequestTime() : PoolData.BackgroundConfig.SCHEDULE_REQUEST_TIME;
                    } else if (SCHEDULE_REMOVE_HANDLER.equals(key)) {
                        delay = clientConfig != null ? clientConfig.getCheckRemoveTime() : PoolData.BackgroundConfig.SCHEDULE_REMOVE_TIME;
                    } else if (SCHEDULE_ACTION_HANDLER.equals(key)) {
                        delay = clientConfig != null ? clientConfig.getCheckActionTime() : PoolData.BackgroundConfig.SCHEDULE_ACTION_TIME;
                    } else if (key != null && key.startsWith(SCHEDULE_UPLOAD_HANDLER)) {
                        delay = clientConfig != null ? clientConfig.getCheckUploadTime() : PoolData.BackgroundConfig.SCHEDULE_UPLOAD_TIME;
                    }
                    if (schedule.handler != null && schedule.schedule != null) {
                        schedule.handler.removeCallbacksAndMessages(null);
                        schedule.handler.postDelayed(schedule.schedule, delay);
                    }
                }
            }
        }

        if (networkStatus.isConnected) {
            remoteRest(PoolData.TaskID.REMOTE_SHORT_TERM, RemoteDataTask.RemoteType.SHORT_TERM);

            // *************************************************************************************
            // Đây là dòng code ngu si học dành cho hot fix theo yêu cầu bên khác.
            // Nếu cần hỏi. Liên lạc Linh Béo () hoặc Vĩ ()
            // Code này Tú béo đéo xác nhận là thông minh
            remoteRest(PoolData.TaskID.REMOTE_LONG_TERM, RemoteDataTask.RemoteType.LONG_TERM);
            // *************************************************************************************
        } else {
            PoolLogger.i(TAG, "Request for first data : no network");
        }
    }

    public void addAction(Action action) {
        if (!isValid()) return;
        cacheManager.pushAction(action);
        LocalActionTask task = new LocalActionTask(PoolData.TaskID.ACTION_ADD, PoolData.TaskPriority.LOW, onCallback,
                db.actionDAO(), db.rankingDAO());
        threadManager.addTask(task);
    }

    public void upload(Upload upload, LocalUploadTask.LocalUploadType type) {
        if (!isValid()) return;
        if (upload == null) return;
        if (upload.local != null && upload.local.size() > 0) {
            PoolLogger.d(TAG, "upload add with upload type");
        } else {
            PoolLogger.d(TAG, "upload add with raw text type");
            upload.type = UPLOAD_RAW_TEXT;
        }
        cacheManager.pushUpload(upload);
        LocalUploadTask task = new LocalUploadTask(PoolData.TaskID.UPLOAD_ADD, PoolData.TaskPriority.MEDIUM, onCallback,
                db.uploadDAO(), type);
        threadManager.addTask(task);
    }

    public void initUploadType(int type) {
        if (type == UPLOAD_RAW_TEXT) {
            PoolLogger.d(TAG, "please use other type, this is unique type");
            return;
        }
        initUploadType(type, false);
    }

    private void initUploadType(int type, boolean isRunSchedule) {
        String tag = String.format(SCHEDULE_UPLOAD_TYPE_HANDLER, type);
        if (!schedules.containsKey(tag)) {
            initHandleSchedule(tag, ScheduleCheckUpload.class);
            if (isRunSchedule) {
                MySchedule schedule = schedules.get(tag);
                if (schedule != null && schedule.handler != null && schedule.schedule != null) {
                    int delay = clientConfig != null ? clientConfig.getCheckRequestTime() : PoolData.BackgroundConfig.SCHEDULE_UPLOAD_TIME;
                    schedule.handler.removeCallbacksAndMessages(null);
                    schedule.handler.postDelayed(schedule.schedule, delay);
                    PoolLogger.d(TAG, "initUploadType success : " + type);
                } else {
                    PoolLogger.d(TAG, "initUploadType fail : " + type);
                }
            }
        } else {
            PoolLogger.d(TAG, "initUploadType already start");
        }
    }

    public void isLoggerDebug(boolean isDebug) {
        PoolLogger.isDebug = isDebug;

    }

    public void interruptUploadType(int type) {
        String tag = String.format(SCHEDULE_UPLOAD_TYPE_HANDLER, type);
        if (schedules.containsKey(tag)) {
            MySchedule schedule = schedules.get(tag);
            if (schedule == null) {
                schedules.remove(tag);
                return;
            }
            if (schedule.handlerThread != null) {
                schedule.handlerThread.interrupt();
            }
            if (schedule.handler != null) {
                schedule.handler.removeCallbacksAndMessages(null);
            }
        }
    }

    public void getData(boolean isRefresh) {
        if (!isValid()) return;
        if (isRefresh) {
            cacheManager.clearListShow(currentId);
            clientConfig.setRefresh(true);

            remoteRest(PoolData.TaskID.REMOTE_SHORT_TERM, RemoteDataTask.RemoteType.SHORT_TERM);
            cacheManager.changeClientWaitState(true);

            requestHandler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    getDataTask();
                }
            }, delayTime > 0 ? delayTime : PoolData.BackgroundConfig.GET_DATA_TIME_LIMIT);
        } else {
            cacheManager.changeClientWaitState(true);
            getDataTask();
        }
    }

    public void setPattern(int id, List<List<Integer>> pattern) {
        if (!isValid()) return;
        cacheManager.setPattern(id, pattern);
    }

    public void registerGetFirstData(int id) {
        if (cacheManager == null) return;
        cacheManager.registerFirstData(id);
    }

    public void unRegisterGetFirstData(int id) {
        if (cacheManager == null) return;
        cacheManager.unregisterFirstData(id);
    }

    public void removeRankById(final String id) {
        removeRankByIds(new ArrayList<String>() {{
            add(id);
        }});
    }

    public void removeRankByIds(List<String> ids) {
        if (!isValidLocal()) return;
        db.rankingDAO().removeRanks(ids);
        ranking();
    }

    public void removeRankByUserIds(String userId) {
        if (!isValidLocal()) return;
        db.rankingDAO().deleteByUserId(userId);
        ranking();
    }

    public void insertRank(final Ranking ranking) {
        localInsertRank(new ArrayList<Ranking>() {{
            add(ranking);
        }});
    }

    public void insertRank(List<Ranking> rankings) {
        localInsertRank(rankings);
    }

    /*
     * Private Function
     */
    private boolean isValid() {
        if (threadManager == null || cacheManager == null || clientConfig == null) {
            PoolLogger.w(TAG, "NullPointException : threadManager, cacheManager, clientConfig");
            return false;
        } else {
            return true;
        }
    }

    private boolean isValidLocal() {
        if (threadManager == null || cacheManager == null) {
            PoolLogger.w(TAG, "NullPointException : threadManager, cacheManager");
            return false;
        } else {
            return true;
        }
    }

    private boolean isValidDb() {
        if (db == null) {
            PoolLogger.w(TAG, "NullPointException : Database Manager");
            return false;
        } else {
            return true;
        }
    }

    private void initNetworkStatus(Context context) {
        networkStatus = new NetworkStatus();
        networkStatus.isConnected = PoolHelper.isInternetOn(context);
        ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        networkStatus.isWifi = manager.getNetworkInfo(ConnectivityManager.TYPE_WIFI).isConnected();
    }

    private void getDataTask() {
        BGGetDataTask task = new BGGetDataTask(PoolData.TaskID.GET_DATA, PoolData.TaskPriority.MEDIUM, onCallback,
                cacheManager, currentId);
        threadManager.addTask(task);
    }

    private void checkQueueTask(BaseWorker worker) {
        if (!isValid()) return;
        threadManager.completeTask(worker);
    }

    private void ranking() {
        if (!isValid()) return;
        BGRankingTask task = new BGRankingTask(PoolData.TaskID.RANKING, PoolData.TaskPriority.MEDIUM, onCallback, db.rankingDAO(), cacheManager.getMode());
        threadManager.addTask(task);
    }

    private void remoteRest(PoolData.TaskID taskID, RemoteDataTask.RemoteType type) {
        if (clientConfig == null) {
            PoolLogger.w(TAG, "NullPointException : Need call setClientConfig");
            return;
        }
        if (!isValid()) return;
        RemoteDataTask task = new RemoteDataTask(taskID, PoolData.TaskPriority.HIGH, onCallback, type, client, clientConfig, cacheManager, currentId);
        threadManager.addTask(task);
    }

    private void localInsertRank(List<Ranking> data) {
        if (!isValidLocal()) return;
        LocalRankTask task = new LocalRankTask(PoolData.TaskID.LOCAL_INSERT_RANK, PoolData.TaskPriority.MEDIUM, onCallback, db.rankingDAO(), data);
        threadManager.addTask(task);
    }

    private void localRemoveRank() {
        if (!isValidLocal()) return;
        LocalRemoveTask task = new LocalRemoveTask(PoolData.TaskID.LOCAL_REMOVE_RANK, PoolData.TaskPriority.MEDIUM, onCallback, clientConfig, cacheManager, db.rankingDAO(), db.actionDAO());
        threadManager.addTask(task);
    }

    private void updatePreCache() {
        if (!isValidLocal()) return;
        LocalCacheUpdateTask task = new LocalCacheUpdateTask(PoolData.TaskID.LOCAL_CACHE_UPDATE,
                PoolData.TaskPriority.MEDIUM, onCallback, db.rankingDAO());
        threadManager.addTask(task);
    }

    private void pullDataToClient(int id, List<String> ids) {
        if (iPoolManager != null) {
            iPoolManager.receiveData(id, ids);
        }
    }

    private void upload(int type) {
        if (!isValidLocal()) return;
//        RemoteUploadTask task = new RemoteUploadTask(PoolData.TaskID.UPLOAD, PoolData.TaskPriority.MEDIUM, onCallback,
//                clientUpload, clientConfig, db.uploadDAO(), contentResolver, type);
//        threadManager.addTask(task);
        if (cacheManager.pullTask().size() != 0)
            threadManager.addTask(cacheManager.pullTask().get(0));
//        cacheManager.pullTask().remove(0);
    }

    private void action() {
        if (!isValidLocal()) return;
        RemoteActionTask task = new RemoteActionTask(PoolData.TaskID.ACTION, PoolData.TaskPriority.MEDIUM, onCallback,
                clientUpload, clientConfig, db.actionDAO());
        threadManager.addTask(task);
    }

    /*
     * Inner Class
     */
    public class OnCallbackFromTask implements ITask {
        @Override
        public void localAddRank(PoolData.TaskID taskID, List<Ranking> data) {
            switch (taskID) {
                case REMOTE_LONG_TERM:
                    lastTimeRequest = System.currentTimeMillis();
                    break;
                case REMOTE_SHORT_TERM:
                    requestHandler.removeCallbacksAndMessages(null);
//                    clientConfig.setRefresh(false);
//                    cacheManager.changeClientWaitState(false);
                    break;
            }
            localInsertRank(data);
        }

        @Override
        public void needMoreData() {
            remoteRest(PoolData.TaskID.REMOTE_SHORT_TERM, RemoteDataTask.RemoteType.SHORT_TERM);
        }

        @Override
        public void needRanking() {
            ranking();
        }

        @Override
        public void needUpdateCache() {
            updatePreCache();
        }

        @Override
        public void removeCard(List<String> ids) {
            clientConfig.removeData(ids);
        }

        @Override
        public void updateCache(List<Ranking> data) {
            cacheManager.setRankings(data);
            if (cacheManager.isClientWait()) {
                getData(false);
            }
        }

        @Override
        public void pullData(int id, List<String> ids) {
            clientConfig.setRefresh(false);
            cacheManager.changeClientWaitState(false);
            pullDataToClient(id, ids);
        }

        @Override
        public List<Action> getActions() {
            return cacheManager.pullAction();
        }

        @Override
        public List<Upload> getUploads() {
            return cacheManager.pullUpload();
        }

        @Override
        public NetworkStatus getNetworkState() {
            return networkStatus;
        }

        @Override
        public void complete(BaseWorker task) {
            switch (task.id) {
                case REMOTE_LONG_TERM:
//                    lastTimeRequest = System.currentTimeMillis();
                    break;
                case REMOTE_SHORT_TERM:
//                    add
                    clientConfig.setRefresh(false);
                    break;
                case UPLOAD:
                case SEND_REQUEST:
//                    int nextTaskIndex = cacheManager.getCurrentTaskIndex() + 1;
//
//                    if (nextTaskIndex < cacheManager.pullTask().size()) {
//                        threadManager.addTask(cacheManager.pullTask().get(nextTaskIndex));
//                        cacheManager.setCurrentTaskIndex(nextTaskIndex);
//                    }
//                    processComppletedTask();
                    cacheManager.pullTask().remove(0);
                    if (cacheManager.pullTask().size() != 0) {
                        threadManager.addTask(cacheManager.pullTask().get(0));
                    }
                    PoolLogger.i(TAG, "task size:" + cacheManager.pullTask().size());
                    break;
            }
            checkQueueTask(task);
        }

        @Override
        public void fail(BaseWorker task, boolean isValid) {
            if (task != null && isValid) {
                threadManager.addTask(task);
            }
            checkQueueTask(task);
        }

        @Override
        public void noMoreData() {
            if (iPoolManager != null) {
                iPoolManager.noMoreData();
            }
        }

        @Override
        public void remoteFail(BaseWorker task) {
            if (cacheManager.isClientWait()) {
                if (task != null && task instanceof RemoteDataTask) {
                    threadManager.addTask(task);
                }
            }
        }

        @Override
        public void uploadSuccess(String id, String path, String link) {
            clientConfig.uploadFileSuccess(id, path, link);
        }

        @Override
        public void uploadFileFail(String id, String path) {
            clientConfig.uploadFileFail(id, path);
        }

        @Override
        public void changeState() {
            clientConfig.setRefresh(false);
            cacheManager.changeClientWaitState(false);
        }
    }

    public class MySchedule {
        public Handler handler;
        public HandlerThread handlerThread;
        public BaseSchedule schedule;

        public MySchedule(Handler handler, HandlerThread handlerThread, BaseSchedule schedule) {
            this.handler = handler;
            this.handlerThread = handlerThread;
            this.schedule = schedule;
        }
    }

    public class OnNetworkStateChange implements NetworkReceiver.INetworkReceiver {
        @Override
        public void update(boolean isConnected, boolean isWifi, int state, int type, int subtype) {
            PoolLogger.i(TAG, "Pool update network state");
            networkStatus.isConnected = isConnected;
            networkStatus.isWifi = isWifi;
            networkStatus.state = state;
            networkStatus.type = type;
            networkStatus.subType = subtype;
            if (isConnected && isValid()) {
                threadManager.runTask();
            }
        }
    }

    public class BaseSchedule implements Runnable {
        protected String tag;
        protected Handler handler;

        public BaseSchedule(String tag, Handler handler) {
            this.tag = tag;
            this.handler = handler;
        }

        @Override
        public void run() {

        }
    }

    public class ScheduleCheckRequest extends BaseSchedule {
        public ScheduleCheckRequest(String tag, Handler handler) {
            super(tag, handler);
        }

        @Override
        public void run() {
            if (cacheManager == null || handler == null) {
                PoolLogger.w(TAG, "NullPointException : cacheManager, handlers");
                return;
            }

            boolean isNeedRun = false;

            long now = System.currentTimeMillis();
            PoolLogger.d(TAG, String.format("test avaiableCount[%s] : %s", currentId, cacheManager.getAvailableCount(currentId, PoolData.Configure.DATA_MAX_IN_CACHE)));
            if (cacheManager.getAvailableCount(currentId, PoolData.Configure.DATA_MAX_IN_CACHE) < PoolData.Configure.DATA_MAX_IN_CACHE) {
                isNeedRun = isValidTime(now);
            } else {
                PoolLogger.i(TAG, "too many unseen item, not need get data from server");
            }
            if (cacheManager.getAvailableCount(currentId, PoolData.Configure.MIN_ITEM_CACHE) < PoolData.Configure.MIN_ITEM_CACHE) {
                isNeedRun = isValidTime(now);
            } else {
                PoolLogger.i(TAG, "has enough data");
            }

            if (isNeedRun) {
                remoteRest(PoolData.TaskID.REMOTE_LONG_TERM, RemoteDataTask.RemoteType.LONG_TERM);
            }

            handler.postDelayed(this, clientConfig != null ? clientConfig.getCheckRequestTime() : PoolData.BackgroundConfig.SCHEDULE_REQUEST_TIME);
        }

        private boolean isValidTime(long now) {
            if (now - PoolData.BackgroundConfig.SCHEDULE_TIME_BETWEEN_REQUEST > lastTimeRequest) {
                return true;
            } else {
                PoolLogger.i(TAG, "last request and now too close");
                return false;
            }
        }
    }

    public class ScheduleCheckRemove extends BaseSchedule {
        public ScheduleCheckRemove(String tag, Handler handler) {
            super(tag, handler);
        }

        @Override
        public void run() {
            if (cacheManager == null || handler == null || db == null) {
                PoolLogger.w(TAG, "NullPointException : cacheManager, handler, db");
                return;
            }

            RankingDAO rankingDAO = db.rankingDAO();

            int count = rankingDAO.getNumberOfRows();
            int needRemovePoint = (int) (clientConfig.getMaxCardLocal() * clientConfig.getCheckRemoveCardPercent());
            PoolLogger.i(TAG, String.format("count local[Database][%s] - needRemovePoint[%s] : ", count, needRemovePoint));

            if (count > needRemovePoint) {
                localRemoveRank();
            } else {
                PoolLogger.i(TAG, "not need remove");
            }

            handler.postDelayed(this, clientConfig != null ? clientConfig.getCheckRequestTime() : PoolData.BackgroundConfig.SCHEDULE_REQUEST_TIME);
        }
    }

    public class ScheduleCheckAction extends BaseSchedule {
        public ScheduleCheckAction(String tag, Handler handler) {
            super(tag, handler);
        }

        @Override
        public void run() {
            if (cacheManager == null || handler == null || db == null) {
                PoolLogger.w(TAG, "NullPointException : cacheManager, handler, db");
                return;
            }

            List<Action> actions = PoolHelper.getValidAction(db.actionDAO(), clientConfig.getActionRetry());
            if (actions.size() > 0) {
                action();
            } else {
                PoolLogger.i(TAG, "no data actions");
            }

            handler.postDelayed(this, clientConfig != null ? clientConfig.getCheckRequestTime() : PoolData.BackgroundConfig.SCHEDULE_REQUEST_TIME);
        }
    }

    public class ScheduleCheckUpload extends BaseSchedule {
        public ScheduleCheckUpload(String tag, Handler handler) {
            super(tag, handler);
        }

        @Override
        public void run() {
            if (cacheManager == null || handler == null || db == null || schedules == null) {
                PoolLogger.w(TAG, "cacheManager : " + cacheManager);
                PoolLogger.w(TAG, "handler : " + handler);
                PoolLogger.w(TAG, "db : " + db);
                PoolLogger.w(TAG, "schedules : " + schedules);
                PoolLogger.w(TAG, "NullPointException : cacheManager, checkUploadHandler, db, schedules");
                return;
            }
            Set<String> keys = schedules.keySet();
            if (keys == null) {
                PoolLogger.w(TAG, "NullPointException : no key ?");
                return;
            }
            for (String key : keys) {
                try {
                    if (key == null) {
                        continue;
                    }
                    if (key.startsWith(SCHEDULE_UPLOAD_HANDLER)) {
                        int type = Integer.parseInt(key.replace(SCHEDULE_UPLOAD_HANDLER, ""));
                        PoolLogger.i(TAG, "get data upload with type : " + type);
                        List<Upload> uploads = PoolHelper.getValidUpload(db.uploadDAO(), clientConfig.getUploadRetry(), type);
                        List<Upload> sendingRequests = PoolHelper.getValidSendingRequest(db.uploadDAO(), clientConfig.getUploadRetry(), type);
                        PoolLogger.i(TAG, "uploads size:" + uploads.size());
                        if (uploads.size() > 0) {
                            checkUpload(db.uploadDAO(), onCallback, type);
//                            upload(type);
                        } else {
                            PoolLogger.i(TAG, "no data upload with type : " + type);

                        }

                        if (sendingRequests.size() > 0) {
                            checkSendingRequest(db.uploadDAO(), onCallback, type);
                        } else {
                            PoolLogger.i(TAG, "no request sending with type : " + type);

                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            handler.postDelayed(this, clientConfig != null ? clientConfig.getCheckRequestTime() : PoolData.BackgroundConfig.SCHEDULE_REQUEST_TIME);
        }
    }

    private void checkUpload(final UploadDAO uploadDAO, final OnCallbackFromTask callback, int backgroundType) {
        try {
            String msgError = "";
            if (callback == null) {
                msgError += "\nNullPointException : callback";
            }
            if (callback != null && !PoolHelper.isValidNetwork(callback.getNetworkState())) {
                msgError += "\nNo network";
            }
            if (uploadDAO == null) {
                msgError += "\nNullPointException : uploadDAO";
            }
            if (PoolHelper.isValidMsgError(msgError)) {
                List<Upload> uploads = PoolHelper.getValidUpload(uploadDAO, clientConfig.getUploadRetry(), backgroundType);
                if (uploads != null && uploads.size() > 0) {
                    for (int cardPostIndex = 0; cardPostIndex < uploads.size(); cardPostIndex++) {
                        final Upload item = uploads.get(cardPostIndex);
                        if (item != null && item.local != null && item.link != null) {
                            final List<String> locals = item.local;
                            final List<UploadTaskData> datas = item.link;
                            if (locals == null || datas == null) {
                                break;
                            }
                            if (locals.size() <= 0) {
                                PoolLogger.d(TAG, String.format("no file need upload]"));
                            } else if (datas.size() < locals.size()) {

                                if (datas.size() == 0) {
                                    for (int linkIndex = 0; linkIndex < locals.size(); linkIndex++) {
                                        String path = locals.get(linkIndex);
                                        UploadFileTask uploadFileTask = new UploadFileTask(PoolData.TaskID.UPLOAD, PoolData.TaskPriority.MEDIUM, callback, client, clientConfig, uploadDAO, contentResolver, backgroundType, item, linkIndex, path);
                                        cacheManager.pushTask(uploadFileTask);
                                        PoolLogger.i(TAG, "push task when data size is 0");
                                    }
                                } else {
                                    for (int linkIndex = 0; linkIndex < locals.size(); linkIndex++) {
                                        boolean isExist = false;
                                        for (int i = 0; i < datas.size(); i++) {
                                            if (locals.get(linkIndex).equals(datas.get(i).local)) {
                                                isExist = true;
                                                break;
                                            }
                                        }
                                        if (!isExist) {
                                            String path = locals.get(linkIndex);
                                            UploadFileTask uploadFileTask = new UploadFileTask(PoolData.TaskID.UPLOAD, PoolData.TaskPriority.MEDIUM, callback, client, clientConfig, uploadDAO, contentResolver, backgroundType, item, linkIndex, path);
                                            cacheManager.pushTask(uploadFileTask);
                                            PoolLogger.i(TAG, "push task when data size != 0");
                                        } else {
                                            PoolLogger.i(TAG, "don't push task when data size != 0");
                                        }
                                    }
                                }

                                RemoteSendRequestTask remoteSendRequestTask = new RemoteSendRequestTask(PoolData.TaskID.SEND_REQUEST, PoolData.TaskPriority.MEDIUM, callback, client, clientConfig, uploadDAO, backgroundType, item);
                                cacheManager.pushTask(remoteSendRequestTask);
                            }
                        }

                        List<BaseWorker> listTasks = cacheManager.pullTask();
                        for (BaseWorker worker : listTasks) {
                            if (worker instanceof UploadFileTask) {
                                PoolLogger.i(TAG, "local link:" + ((UploadFileTask) worker).filePath);
                            }
                        }

                    }
                    upload(backgroundType);

                } else {
                    PoolLogger.d(TAG, String.format("No data to upload / send request"));
                }
            } else {
                PoolLogger.i(TAG, msgError);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PoolLogger.i(TAG, "end");
        }
    }

    private void checkSendingRequest(final UploadDAO uploadDAO, final OnCallbackFromTask callback, int backgroundType) {
        try {
            String msgError = "";
            if (callback == null) {
                msgError += "\nNullPointException : callback";
            }
            if (callback != null && !PoolHelper.isValidNetwork(callback.getNetworkState())) {
                msgError += "\nNo network";
            }
            if (uploadDAO == null) {
                msgError += "\nNullPointException : uploadDAO";
            }
            if (PoolHelper.isValidMsgError(msgError)) {
                List<Upload> sendingRequests = PoolHelper.getValidSendingRequest(uploadDAO, clientConfig.getUploadRetry(), backgroundType);
                if (sendingRequests != null && sendingRequests.size() > 0) {
                    for (int cardPostIndex = 0; cardPostIndex < sendingRequests.size(); cardPostIndex++) {
                        final Upload item = sendingRequests.get(cardPostIndex);
                        if (item != null && item.local != null && item.link != null) {

                            RemoteSendRequestTask remoteSendRequestTask = new RemoteSendRequestTask(PoolData.TaskID.SEND_REQUEST, PoolData.TaskPriority.MEDIUM, callback, client, clientConfig, uploadDAO, backgroundType, item);
                            cacheManager.pushTask(remoteSendRequestTask);
                        }
                    }
                    upload(backgroundType);

                } else {
                    PoolLogger.d(TAG, String.format("No data to send request"));
                }
            } else {
                PoolLogger.i(TAG, msgError);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            PoolLogger.i(TAG, "end send request");
        }
    }

    private void processComppletedTask() {
        for (BaseWorker worker : cacheManager.pullTask()) {
            if (worker instanceof UploadFileTask) {
                if (((UploadFileTask) worker).item.status == Upload.UploadStatus.COMPELE.ordinal()) {
                    cacheManager.pullTask().remove(worker);
                }
            } else {
                if (worker instanceof RemoteSendRequestTask) {
                    if (((RemoteSendRequestTask) worker).item.status == Upload.UploadStatus.COMPELE.ordinal()) {
                        cacheManager.pullTask().remove(worker);
                    }
                }
            }
        }
    }

}
