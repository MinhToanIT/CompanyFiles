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

public class UploadFileTask extends BaseWorker {
    private final String TAG = UploadFileTask.class.getSimpleName();

    private OkHttpClient client;
    private ClientConfig config;
    private UploadDAO uploadDAO;
    private ContentResolver contentResolver;
    public Upload item;
    private int linkIndex;
    public String filePath;

    public UploadFileTask(TaskID id, @NonNull TaskPriority taskPriority, @NonNull ITask callback,
                          @NonNull OkHttpClient client, @NonNull ClientConfig config,
                          @NonNull UploadDAO uploadDAO, ContentResolver contentResolver, int type, Upload item, int linkIndex, String filePath) {
        super(id, taskPriority, callback);
        this.client = client;
        this.config = config;
        this.uploadDAO = uploadDAO;
        this.contentResolver = contentResolver;
        this.backgroundType = type;
        this.item = item;
        this.linkIndex = linkIndex;
        this.filePath = filePath;
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
                if (item != null && item.local != null && item.link != null) {
                    item = uploadDAO.getUploadById(item.id);
                    List<UploadTaskData> datas = item.link;
                    PoolLogger.i(TAG, "id:" + item.id);
                    PoolLogger.i(TAG, "links size:" + datas.size());
                    UploadTaskData data = null;
                    try {
                        data = uploadFile(item, linkIndex, filePath);

                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    }
                    if (data != null) {
                        PoolLogger.i(TAG, String.format("upload success id[%s] - local[%s] - link[%s] - type[%s] - width[%s] - height[%s]",
                                data.id, data.local, data.link, data.mediaType, data.width, data.height));
                        datas.add(data);
                        PoolLogger.i(TAG, "links size1:" + datas.size());
                        uploadDAO.updateLinkById(item.id, gson.toJson(datas));
                    } else {
                        uploadDAO.updateRetryById(item.id, ++item.retryCount);
//                                        break;
                    }
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

}
