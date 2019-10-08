package com.vcc.pool.core.task;

import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.vcc.pool.core.ClientConfig;
import com.vcc.pool.core.ITask;
import com.vcc.pool.core.base.BaseWorker;
import com.vcc.pool.core.storage.db.rank.Ranking;
import com.vcc.pool.core.storage.db.upload.Upload;
import com.vcc.pool.core.storage.db.upload.UploadDAO;
import com.vcc.pool.core.task.data.RemoteTaskData;
import com.vcc.pool.core.task.data.UploadTaskData;
import com.vcc.pool.util.PoolHelper;
import com.vcc.pool.util.PoolLogger;
import com.vcc.pool.util.RequestConfig;

import java.io.IOException;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RemoteSendRequestTask extends BaseWorker {
    private final String TAG = RemoteSendRequestTask.class.getSimpleName();

    private OkHttpClient client;
    private ClientConfig config;
    private UploadDAO uploadDAO;
    public Upload item;

    public RemoteSendRequestTask(TaskID id, @NonNull TaskPriority taskPriority, @NonNull ITask callback,
                                 @NonNull OkHttpClient client, @NonNull ClientConfig config,
                                 @NonNull UploadDAO uploadDAO, int type, Upload item) {
        super(id, taskPriority, callback);
        this.client = client;
        this.config = config;
        this.uploadDAO = uploadDAO;
        this.backgroundType = type;
        this.item = item;
    }

    @Override
    public void run() {
        PoolLogger.i(TAG, "begin send request : " + backgroundType);
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

                if (item != null && item.local != null && item.link != null) {
                    item = uploadDAO.getUploadById(item.id);
                    List<String> locals = item.local;
                    final List<UploadTaskData> datas = item.link;

                    PoolLogger.i(TAG, "data size:" + datas.size());
                    if (item.isNeedRequest) {
                        if (datas.size() >= locals.size()) {
                            config.uploadSuccess(item.uploadType, item.cardId, datas);
                            uploadDAO.updateStatusById(item.id, Upload.UploadStatus.UPLOAD_SUCCESS.ordinal());
                            String msg = null;
                            try {
                                msg = sendRequest(item, datas);
                                PoolLogger.d(TAG, "Request success from server");
                            } catch (IOException ioe) {
                                ioe.printStackTrace();
                            }
                            if (!TextUtils.isEmpty(msg)) {
                                PoolLogger.d(TAG, "Request success from server");
                                if (item.isRankRequest) {
                                    List<Ranking> rankings = config.parseRequestData(item.uploadType, msg);
                                    if (rankings != null && rankings.size() > 0) {
                                        PoolLogger.d(TAG, "Request success has ranking : " + rankings.size());
                                        callback.localAddRank(id, rankings);
                                    } else {
                                        PoolLogger.d(TAG, "Request success no ranking object");
                                    }
                                    uploadDAO.updateStatusById(item.id, Upload.UploadStatus.COMPELE.ordinal());
                                    PoolLogger.d(TAG, "Request success update complete");
                                } else {
                                    config.parseRequestData(item.uploadType, msg);
                                    PoolLogger.d(TAG, "Request success no need ranking");
                                    uploadDAO.updateStatusById(item.id, Upload.UploadStatus.COMPELE.ordinal());
                                    PoolLogger.d(TAG, "Request success update complete");
                                }
                            } else {
                                PoolLogger.d(TAG, String.format("request send fail, can't parse from message"));
                                uploadDAO.updateRetryById(item.id, ++item.retryCount);
                            }
                        } else {
                            PoolLogger.d(TAG, String.format("links size != local size, something error"));
                            uploadDAO.updateRetryById(item.id, ++item.retryCount);
                        }
                    } else {
                        if (datas.size() >= locals.size()) {
                            config.uploadSuccess(item.uploadType, item.cardId, datas);
                            uploadDAO.updateStatusById(item.id, Upload.UploadStatus.COMPELE.ordinal());
                        } else {
                            uploadDAO.updateRetryById(item.id, ++item.retryCount);
                        }
                    }

                    if (item.retryCount >= config.getActionRetry()) {
                        uploadDAO.updateStatusById(item.id, Upload.UploadStatus.FAIL.ordinal());
                        config.uploadFail(item.cardId, item.retryCount);
                    }
                } else {
                    PoolLogger.d(TAG, String.format("No data to upload / send request"));
                }

            } else {
                PoolLogger.i(TAG, msgError);
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            callback.complete(this);
            PoolLogger.i(TAG, "Send request end");
        }
    }

    private String sendRequest(Upload item, List<UploadTaskData> links) throws IOException {
        if (item == null) {
            PoolLogger.d(TAG, String.format("NullPointException : item"));
            return null;
        }
        if (callback != null && !PoolHelper.isValidNetwork(callback.getNetworkState())) {
            PoolLogger.d(TAG, String.format("No Network"));
            return null;
        }
        RemoteTaskData requestData = config.getRequest(item.uploadType, item.cardId, links);
        if (requestData == null) {
            PoolLogger.d(TAG, String.format("Can't create remote task data with id[%s] - type[%s]", item.cardId, item.uploadType));
            return null;
        }
        PoolLogger.i(TAG, String.format("sendRequest url[%s] - jsonString : %s", requestData.url, requestData.jsonData));
        RequestConfig requestConfig = new RequestConfig()
                .setType(requestData.requestType)
                .setUrl(requestData.url)
                .setJsonString(requestData.jsonData)
                .setHeaders(requestData.headers)
                .setParams(requestData.params)
                .setContent(requestData.binaryData);
        if (requestConfig != null) {
            Request request = PoolHelper.createRequest(requestConfig);

            uploadDAO.updateStatusById(item.id, Upload.UploadStatus.SENDING_REQUEST.ordinal());
            Response response = client.newCall(request).execute();

            if (response != null && response.isSuccessful()) {
                PoolLogger.i(String.format("Send request success : message[%s] - body[%s]", response.message(), response.body().toString()));
                return PoolHelper.getResponseString(response);
            } else {
                int code = response.code();
                String msg = response.message();
                PoolLogger.i(String.format("Send request fail code[%s] with message : %s", code, msg));
            }
        }
        uploadDAO.updateStatusById(item.id, Upload.UploadStatus.UPLOAD_SUCCESS.ordinal());
        return null;
    }
}
