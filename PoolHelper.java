package com.vcc.pool.util;

import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.ConnectivityManager;
import android.text.TextUtils;
import android.webkit.MimeTypeMap;

import com.vcc.pool.core.PoolData;
import com.vcc.pool.core.network.NetworkStatus;
import com.vcc.pool.core.storage.db.action.Action;
import com.vcc.pool.core.storage.db.action.ActionDAO;
import com.vcc.pool.core.storage.db.upload.Upload;
import com.vcc.pool.core.storage.db.upload.UploadDAO;
import com.vcc.pool.core.task.data.RemoteTaskData;
import com.vcc.pool.core.task.data.UploadTaskData;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import okhttp3.FormBody;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import static android.content.Context.CONNECTIVITY_SERVICE;

public class PoolHelper {
    private static final String TAG = PoolHelper.class.getSimpleName();

    public static boolean isInternetOn(Context context) {
        try {
            ConnectivityManager con = (ConnectivityManager) context.getSystemService(CONNECTIVITY_SERVICE);
            if (con.getNetworkInfo(0).getState() == android.net.NetworkInfo.State.CONNECTED ||
                    con.getNetworkInfo(0).getState() == android.net.NetworkInfo.State.CONNECTING ||
                    con.getNetworkInfo(1).getState() == android.net.NetworkInfo.State.CONNECTING ||
                    con.getNetworkInfo(1).getState() == android.net.NetworkInfo.State.CONNECTED) {
                return true;
            } else if (con.getNetworkInfo(0).getState() == android.net.NetworkInfo.State.DISCONNECTED ||
                    con.getNetworkInfo(1).getState() == android.net.NetworkInfo.State.DISCONNECTED) {
                return false;
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return false;
    }

    public static boolean isImageFile(String path) {
        String mimeType = URLConnection.guessContentTypeFromName(path);
        return mimeType != null && mimeType.startsWith("image");
    }

    public static boolean isVideoFile(String path) {
        String mimeType = URLConnection.guessContentTypeFromName(path);
        return mimeType != null && mimeType.startsWith("video");
    }

    public static byte[] createBitmapFromSdCardImage(String path, UploadTaskData uploadTaskData) {
        if (path == null) return null;
        File file = new File(path);
        if (file != null && file.canRead() && file.isFile()) {
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inPreferredConfig = Bitmap.Config.ARGB_8888;
            options.inJustDecodeBounds = true;
            Bitmap bitmap = BitmapFactory.decodeFile(path, options);
            ByteArrayOutputStream stream = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream);
            final byte[] bitmapdata = stream.toByteArray();
            bitmap.recycle();
            uploadTaskData.width = options.outWidth;
            uploadTaskData.height = options.outHeight;
            return bitmapdata;
        } else {
            return null;
        }
    }

    public static Request createRequest(RequestConfig config) {
        return createRequest(config, null);
    }

    public static Request createRequest(RequestConfig config, ContentResolver contentResolver) {
        Request.Builder rb = new Request.Builder();
        rb.url(config.url);

        rb = createHeaders(config, rb);
//        rb.header("Accept-Encoding", "gzip,deflate");
//        rb.header("Accept", "text/html");
        rb.header("Accept-Language", "en-US,en;q=0.8");
        rb.header("CacheManager-Control", "max-age=0");
        rb.header("Connection", "keep-alive");

        RequestBody body;
        MultipartBody.Builder multiBuilder;
        FormBody.Builder formBuilder;
        MediaType type;

        switch (config.type) {
            case GET:
                String param = createParam(config);
                if (!TextUtils.isEmpty(param)) {
                    String url = config.url;
                    if (config.url.contains("?")) {
                        url += "&" + param;
                    } else {
                        url += "?" + param;
                    }
                    rb.url(url);
                }
                rb.get();
                break;
            case POST_FORM:
                multiBuilder = new MultipartBody.Builder().setType(MultipartBody.FORM);
                multiBuilder = createFormParam(config, multiBuilder);
                body = multiBuilder.build();
                rb.post(body);
                break;
            case POST_WWW_FORM:
                formBuilder = new FormBody.Builder();
                formBuilder = createFormParam(config, formBuilder);
                body = formBuilder.build();
                rb.post(body);
                break;
            case POST_JSON:
                type = MediaType.parse("application/json; charset=utf-8");
//                String postParam = createParam(config);
                body = RequestBody.create(type, config.jsonString);
                rb.post(body);
                break;
            case JSON:
                MediaType jsonType = MediaType.parse("application/json; charset=utf-8");
                String jsonParam = config.jsonString;
                body = RequestBody.create(jsonType, jsonParam);
                rb.post(body);
                break;
            case BINARY:
                MediaType biType = MediaType.parse("text/plain, charset=utf-8");
                body = RequestBody.create(biType, config.content);
                rb.post(body);
                break;
            case UPLOAD:
                multiBuilder = new MultipartBody.Builder();
                multiBuilder.setType(MultipartBody.FORM);
                multiBuilder = createMultiPartParam(config, multiBuilder);
                body = multiBuilder.build();
                rb.post(body);
                break;
            case UPLOAD_FILE:
                multiBuilder = new MultipartBody.Builder();
                multiBuilder.setType(MultipartBody.FORM);
                multiBuilder = createMultiPartFileParam(config, multiBuilder);
                body = multiBuilder.build();
                rb.post(body);
                break;
            default:
                PoolLogger.w("RequestConfig type : not define this type : " + config.type.name());
                return null;
        }
        Request request = rb.build();

        PoolLogger.i("Request url : " + request.url().toString());
        return request;
    }

    private static Request.Builder createHeaders(RequestConfig config, Request.Builder builder) {
        if (config != null && config.headers != null) {
            Set<String> keys = config.headers.keySet();
            if (keys != null && keys.size() > 0) {
                for (String key : keys) {
                    String content = config.headers.get(key);
                    builder.header(key, content);
                }
            }
        }
        return builder;
    }

    private static MultipartBody.Builder createMultiPartParam(RequestConfig config, MultipartBody.Builder builder) {
        if (config != null && config.params != null) {
            Set<String> keys = config.params.keySet();
            if (keys != null && keys.size() > 0) {
                for (String key : keys) {
                    if (key.startsWith(PoolData.Configure.PARTTERN_PREFIX_UPLOAD_FILE)) {
                        String realKey = key.replace(PoolData.Configure.PARTTERN_PREFIX_UPLOAD_FILE, "");
                        String path = config.params.get(key);
                        final String contentType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(path));
                        builder.addFormDataPart(realKey, config.params.get(key), RequestBody.create(MediaType.parse(contentType), config.content));
                    } else {
                        builder.addFormDataPart(key, config.params.get(key));
                    }
                }
            }
        }
        return builder;
    }

    private static MultipartBody.Builder createMultiPartFileParam(RequestConfig config, MultipartBody.Builder builder) {
        if (config != null && config.params != null) {
            Set<String> keys = config.params.keySet();
            if (keys != null && keys.size() > 0) {
                for (String key : keys) {
                    if (key.startsWith(PoolData.Configure.PARTTERN_PREFIX_UPLOAD_FILE)) {
                        String realKey = key.replace(PoolData.Configure.PARTTERN_PREFIX_UPLOAD_FILE, "");
                        String path = config.params.get(key);
                        final File file = new File(path);
                        String extension = getFileExtension(file);
                        String contentType;
                        if (extension != null && extension.length() > 1) {
                            contentType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.substring(1));
                        } else {
                            contentType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(path));
                        }
                        if (TextUtils.isEmpty(extension) && !TextUtils.isEmpty(contentType) && contentType.startsWith("image")) {
                            extension = ".png";
                        }
                        String fileName = "uploadFile_" + System.currentTimeMillis() + extension;
                        builder.addFormDataPart(realKey, fileName, RequestBody.create(MediaType.parse(contentType), file));
                    } else {
                        builder.addFormDataPart(key, config.params.get(key));
                    }
                }
            }
        }
        return builder;
    }

    private static String getFileExtension(File file) {
        String extension = "";

        try {
            if (file != null && file.exists()) {
                String name = file.getName();
                extension = name.substring(name.lastIndexOf("."));
            }
        } catch (Exception e) {
            extension = "";
        }

        return extension;

    }

    private static String createParam(RequestConfig config) {
        StringBuilder builder = new StringBuilder();
        if (config != null && config.params != null) {
            Set<String> keys = config.params.keySet();
            if (keys != null && keys.size() > 0) {
//                builder.append("?");
                boolean isFirst = true;
                for (String key : keys) {
                    String content = config.params.get(key);
                    if (isFirst) {
                        isFirst = false;
                    } else {
                        builder.append("&");
                    }
                    builder.append(key + "=" + content);
                }
            }
        }
        return builder.toString();
    }

    private static MultipartBody.Builder createFormParam(RequestConfig config, MultipartBody.Builder builder) {
        if (config != null && config.params != null) {
            Set<String> keys = config.params.keySet();
            if (keys != null && keys.size() > 0) {
                for (String key : keys) {
                    String content = config.params.get(key);
                    builder.addFormDataPart(key, content);
//                        builder.addPart(Headers.of("Content-Disposition", String.format("form-data; name=\"%s\"", key)), RequestBody.create(null, content));
                }
            }
        }
        return builder;
    }

    private static FormBody.Builder createFormParam(RequestConfig config, FormBody.Builder builder) {
        if (config != null && config.params != null) {
            Set<String> keys = config.params.keySet();
            if (keys != null && keys.size() > 0) {
                for (String key : keys) {
                    String content = config.params.get(key);
                    builder.add(key, content);
                }
            }
        }
        return builder;
    }

    public static String getResponseString(Response response) {
        try {
            return response.body().string();
        } catch (IOException e) {
            PoolLogger.w(e.getMessage());
        }
        return null;
    }

    public static double log2(double num) {
        return (Math.log(num) / Math.log(2));
    }

    public static boolean isValidMsgError(String msg) {
        if (msg != null && msg.length() > 0) {
            PoolLogger.i(msg);
            return false;
        }
        return true;
    }

    public static boolean isValidNetwork(NetworkStatus status) {
        return status != null && status.isConnected;
    }

    public static List<Action> getValidAction(ActionDAO actionDAO, int retryLimit) {
        List<Integer> status = new ArrayList<>();
        status.add(Upload.UploadStatus.PENDING.ordinal());
        List<Integer> types = new ArrayList<>();
        types.add(Action.ActionType.LIKE.ordinal());
        types.add(Action.ActionType.FOLLOW.ordinal());
        types.add(Action.ActionType.SUBSCRIBE.ordinal());
        return actionDAO.getByStatus(status, types, retryLimit);
    }

    public static List<Upload> getValidUpload(UploadDAO uploadDAO, int retryLimit, final int type) {
        return getValidUpload(uploadDAO, retryLimit, new ArrayList<Integer>() {{
            add(type);
        }});
    }

    public static List<Upload> getValidUpload(UploadDAO uploadDAO, int retryLimit, List<Integer> type) {
        List<Integer> status = new ArrayList<>();
        status.add(Upload.UploadStatus.PENDING.ordinal());
        return uploadDAO.getByStatus(status, retryLimit, type);
    }

    public static List<Upload> getValidUploading(UploadDAO uploadDAO, int retryLimit, final int type) {
        return getValidUploading(uploadDAO, retryLimit, new ArrayList<Integer>() {{
            add(type);
        }});
    }

    public static List<Upload> getValidUploading(UploadDAO uploadDAO, int retryLimit, List<Integer> type) {
        List<Integer> status = new ArrayList<>();
        status.add(Upload.UploadStatus.UPLOADING.ordinal());
        return uploadDAO.getByStatus(status, retryLimit, type);
    }

    public static List<Upload> getValidSendingRequest(UploadDAO uploadDAO, int retryLimit, final int type) {
        return getValidSendingRequest(uploadDAO, retryLimit, new ArrayList<Integer>() {{
            add(type);
        }});
    }

    public static List<Upload> getValidSendingRequest(UploadDAO uploadDAO, int retryLimit, List<Integer> type) {
        List<Integer> status = new ArrayList<>();
        status.add(Upload.UploadStatus.SENDING_REQUEST.ordinal());
        status.add(Upload.UploadStatus.UPLOAD_SUCCESS.ordinal());
        return uploadDAO.getByStatus(status, retryLimit, type);
    }

    public static Object getObjectFromClass(Class clazz, Class[] types, Object[] parameters) {
        try {
            if (types != null && types.length > 0 && parameters != null && parameters.length > 0) {
                Constructor constructor = clazz.getConstructor(types);
                Object instanceOfMyClass = constructor.newInstance(parameters);
                return instanceOfMyClass;
            } else {
                return clazz.newInstance();
            }
        } catch (NoSuchMethodException e) {
            e.printStackTrace();
        } catch (InstantiationException e) {
            e.printStackTrace();
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (InvocationTargetException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static RequestConfig createRequestConfig(RemoteTaskData remoteTaskData, byte[] data) {
        return new RequestConfig()
                .setType(remoteTaskData.requestType)
                .setUrl(remoteTaskData.url)
                .setJsonString(remoteTaskData.jsonData)
                .setHeaders(remoteTaskData.headers)
                .setParams(remoteTaskData.params)
                .setContent(data);
    }

}
