package com.vcc.pool.core.task;

import android.content.ContentResolver;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.support.annotation.NonNull;
import android.text.TextUtils;

import com.google.gson.Gson;
import com.vcc.pool.core.ClientConfig;
import com.vcc.pool.core.ITask;
import com.vcc.pool.core.base.BaseWorker;
import com.vcc.pool.core.base.ThreadManager;
import com.vcc.pool.core.storage.db.rank.Ranking;
import com.vcc.pool.core.storage.db.upload.Upload;
import com.vcc.pool.core.storage.db.upload.UploadDAO;
import com.vcc.pool.core.task.data.RemoteTaskData;
import com.vcc.pool.core.task.data.UploadTaskData;
import com.vcc.pool.util.PoolHelper;
import com.vcc.pool.util.PoolLogger;
import com.vcc.pool.util.RequestConfig;

import java.io.File;
import java.io.IOException;
import java.util.List;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class RemoteUploadTask extends BaseWorker {
    private final String TAG = RemoteUploadTask.class.getSimpleName();

    private OkHttpClient client;
    private ClientConfig config;
    private UploadDAO uploadDAO;
    private ContentResolver contentResolver;
    private ThreadManager threadManager;

    public RemoteUploadTask(TaskID id, @NonNull TaskPriority taskPriority, @NonNull ITask callback,
                            @NonNull OkHttpClient client, @NonNull ClientConfig config,
                            @NonNull UploadDAO uploadDAO, ContentResolver contentResolver, int type,ThreadManager threadManager) {
        super(id, taskPriority, callback);
        this.client = client;
        this.config = config;
        this.uploadDAO = uploadDAO;
        this.contentResolver = contentResolver;
        this.backgroundType = type;
        this.threadManager = threadManager;
    }

    @Override
    public void run() {
        PoolLogger.i(TAG, "begin upload : " + backgroundType);
        try {
            Gson gson = new Gson();
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
                List<Upload> uploads = PoolHelper.getValidUpload(uploadDAO, config.getUploadRetry(), backgroundType);
                if (uploads != null && uploads.size() > 0) {
                    for (int cardPostIndex = 0; cardPostIndex < uploads.size(); cardPostIndex++) {
                        Upload item = uploads.get(cardPostIndex);
                        if (item != null && item.local != null && item.link != null) {
                            List<String> locals = item.local;
                            final List<UploadTaskData> datas = item.link;
                            if (locals == null || datas == null) {
                                break;
                            }

                            if (locals.size() <= 0) {
                                PoolLogger.d(TAG, String.format("no file need upload]"));
                            } else if (datas.size() < locals.size()) {
                                for (int linkIndex = datas.size(); linkIndex < locals.size(); linkIndex++) {
                                    UploadTaskData data = null;
//                                    try {
                                        String path = locals.get(linkIndex);
                                        UploadFileTask uploadFileTask = new UploadFileTask(TaskID.UPLOAD_FILE, TaskPriority.MEDIUM, callback, client, config, uploadDAO, contentResolver, backgroundType, item, linkIndex, path, new UploadFileCallback() {
                                            @Override
                                            public void onUploadSuccess(UploadTaskData data) {
                                                datas.add(data);
                                            }

                                            @Override
                                            public void onUploadFail() {

                                            }
                                        });
                                        threadManager.addTask(uploadFileTask);

//                                        if(PoolHelper.isImageFile(path)){
//                                            data = RemoteUploadImageTask.getInstance(callback,client,config,contentResolver).uploadFile(item,linkIndex,path);
//                                        }else{
//                                            data = RemoteUploadVideoTask.getInstance(callback,client,config,contentResolver).uploadFile(item,linkIndex,path);
//                                        }
////                                        data = uploadFile(item, linkIndex, locals.get(linkIndex));
//                                    } catch (IOException ioe) {
//                                        ioe.printStackTrace();
//                                    }
//                                    if (data != null) {
//                                        PoolLogger.d(TAG, String.format("upload success id[%s] - local[%s] - link[%s] - type[%s] - width[%s] - height[%s]",
//                                                data.id, data.local, data.link, data.mediaType, data.width, data.height));
//                                        datas.add(data);
//                                        uploadDAO.updateLinkById(item.id, gson.toJson(datas));
//                                    } else {
//                                        uploadDAO.updateRetryById(item.id, ++item.retryCount);
//                                        break;
//                                    }
                                }
                            }

                            if (item.isNeedRequest) {
                                if (datas.size() == locals.size()) {
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
                                if (datas.size() == locals.size()) {
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
                        }
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
            PoolLogger.i(TAG, "end");
        }
    }

    private RequestConfig createRequestConfig(RemoteTaskData remoteTaskData, byte[] data) {
        return new RequestConfig()
                .setType(remoteTaskData.requestType)
                .setUrl(remoteTaskData.url)
                .setJsonString(remoteTaskData.jsonData)
                .setHeaders(remoteTaskData.headers)
                .setParams(remoteTaskData.params)
                .setContent(data);
    }

    private UploadTaskData uploadFile(Upload item, int linkIndex, String path) throws IOException {
        try {
            if (TextUtils.isEmpty(path)) {
                PoolLogger.d(TAG, String.format("NullPointException : path", path));
                return null;
            }
            if (callback != null && !PoolHelper.isValidNetwork(callback.getNetworkState())) {
                PoolLogger.d(TAG, String.format("No Network"));
                return null;
            }
            PoolLogger.d(TAG, String.format("create byte data from item[%s] - index[%s] - path[%s]]", item.cardId, linkIndex, path));
            File file = new File(path);
            int width = 0, height = 0;
            String duration = "";
            byte[] data = null;
            if (file != null && file.canRead() && file.isFile()) {
                if (PoolHelper.isImageFile(path)) {
                    BitmapFactory.Options options = new BitmapFactory.Options();
                    options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                    options.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(path, options);
//                    options.inJustDecodeBounds = false;
//                    Bitmap bitmap = BitmapFactory.decodeFile(path, options);
//                    ByteArrayOutputStream stream = new ByteArrayOutputStream();
//                    bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
//                    data = stream.toByteArray();
//                    bitmap.recycle();
//                    width = options.outWidth;
//                    height = options.outHeight;
                    ExifInterface ei = new ExifInterface(path);
                    int orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
                    switch (orientation) {
                        case ExifInterface.ORIENTATION_ROTATE_90:
                        case ExifInterface.ORIENTATION_ROTATE_270:
                            width = options.outHeight;
                            height = options.outWidth;
                            break;
                        default:
                            width = options.outWidth;
                            height = options.outHeight;
                            break;
                    }
                    PoolLogger.d(TAG, String.format("photo meta w[%s] - h[%s]]", width, height));
                } else if (PoolHelper.isVideoFile(path)) {
                    PoolLogger.d(TAG, String.format("get video meta byte[%s] - file.length(%s)", data != null ? data.length : 0, file.length()));
                    MediaMetadataRetriever metaRetriever = new MediaMetadataRetriever();
                    metaRetriever.setDataSource(path);
                    String s = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
                    if ("90".equals(s) || "270".equals(s)) {
                        width = Integer.parseInt(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                        height = Integer.parseInt(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                    } else {
                        width = Integer.parseInt(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                        height = Integer.parseInt(metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                    }
                    duration = metaRetriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);

                    PoolLogger.d(TAG, String.format("video meta w[%s] - h[%s] - d[%s]]", width, height, duration));
                }
            }

            if (config != null) {
                PoolLogger.d(TAG, String.format("data != null"));
                RequestConfig requestConfig = null;
                int type = -1;
                if (PoolHelper.isImageFile(path)) {
                    RemoteTaskData uploadImageData = config.getUploadImage(item.cardId, linkIndex, path);
                    requestConfig = createRequestConfig(uploadImageData, data);
                    type = config.getImageContentType();
                } else if (PoolHelper.isVideoFile(path)) {
                    RemoteTaskData uploadVideoData = config.getUploadVideo(item.cardId, linkIndex, path);
                    requestConfig = createRequestConfig(uploadVideoData, data);
                    type = config.getVideoContentType();
                } else {
                    PoolLogger.d(TAG, String.format("path type error[need in type image or video]"));
                }
                if (requestConfig != null) {
                    PoolLogger.d(TAG, String.format("create requestConfig success"));
                    Request request = PoolHelper.createRequest(requestConfig, contentResolver);
                    Response response = client.newCall(request).execute();
                    String result = PoolHelper.getResponseString(response);
                    UploadTaskData uploadTaskData = config.parseUploadData(item.uploadType, type, result);
                    if (uploadTaskData != null && !TextUtils.isEmpty(uploadTaskData.link)) {
                        PoolLogger.d(TAG, String.format("parse success from server link[%s]", uploadTaskData.link));
                        uploadTaskData.mediaType = type;
                        uploadTaskData.width = width;
                        uploadTaskData.height = height;
                        uploadTaskData.local = path;
                        uploadTaskData.duration = duration;
                        return uploadTaskData;
                    } else {
                        PoolLogger.i(TAG, String.format("link null, check define parseUploadData() function in ClientConfig class"));
                    }
                }
            } else {
                PoolLogger.d(TAG, String.format("get byte from path error[%s]", path));
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        callback.uploadFileFail(item.cardId, path);
        return null;
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
        return null;
    }
}
