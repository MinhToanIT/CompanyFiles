package com.vcc.pool.core.task;

import com.vcc.pool.core.task.data.UploadTaskData;

public interface UploadFileCallback {
    void onUploadSuccess(UploadTaskData data);
    void onUploadFail();
}
