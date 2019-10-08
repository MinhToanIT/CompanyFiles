package com.vcc.pool.core.storage.db.upload;

import android.arch.persistence.room.ColumnInfo;
import android.arch.persistence.room.Entity;
import android.arch.persistence.room.Ignore;
import android.arch.persistence.room.PrimaryKey;
import android.arch.persistence.room.TypeConverters;

import com.vcc.pool.core.PoolData;
import com.vcc.pool.core.storage.db.LanguageConverter;
import com.vcc.pool.core.task.data.UploadTaskData;

import java.util.ArrayList;
import java.util.List;

@Entity(tableName = PoolData.Database.TABLE_UPLOAD_NAME)
@TypeConverters(LanguageConverter.class)
public class Upload {
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    public int id;

    @ColumnInfo(name = "status")
    public int status;

    @ColumnInfo(name = "type")
    public int type;

    @ColumnInfo(name = "uploadType")
    public int uploadType;

    @ColumnInfo(name = "isNeedRequest")
    public boolean isNeedRequest;

    @ColumnInfo(name = "isRankRequest")
    public boolean isRankRequest;

    @ColumnInfo(name = "retryCount")
    public int retryCount;

    @ColumnInfo(name = "cardId")
    public String cardId;

    @ColumnInfo(name = "local")
    public List<String> local;

    @ColumnInfo(name = "link")
    public List<UploadTaskData> link;

    public Upload(int id, int type, int status, int uploadType, int retryCount, String cardId, List<String> local, List<UploadTaskData> link) {
        this.id = id;
        this.type = type;
        this.status = status;
        this.uploadType = uploadType;
        this.retryCount = retryCount;
        this.cardId = cardId;
        this.local = local;
        this.link = link;
    }

    @Ignore
    public Upload(int type, int uploadType, String cardId, List<String> local) {
        this(type, uploadType, true, cardId, local);
    }

    @Ignore
    public Upload(int type, int uploadType, boolean isNeedRequest, String cardId, List<String> local) {
        this.type = type;
        this.status = UploadStatus.PENDING.ordinal();
        this.uploadType = uploadType;
        this.isNeedRequest = isNeedRequest;
        this.isRankRequest = true;
        this.retryCount = 0;
        this.cardId = cardId;
        this.local = new ArrayList<>();
        if (local != null) {
            this.local.addAll(local);
        }
        this.link = new ArrayList<>();
    }

    @Ignore
    public Upload(int type, int uploadType, boolean isNeedRequest, boolean isRankRequest, String cardId, List<String> local) {
        this.type = type;
        this.status = UploadStatus.PENDING.ordinal();
        this.uploadType = uploadType;
        this.isNeedRequest = isNeedRequest;
        this.isRankRequest = isRankRequest;
        this.retryCount = 0;
        this.cardId = cardId;
        this.local = new ArrayList<>();
        if (local != null) {
            this.local.addAll(local);
        }
        this.link = new ArrayList<>();
    }

    public enum UploadStatus {
        PENDING, UPLOADING, UPLOAD_SUCCESS, COMPELE, FAIL, SENDING_REQUEST
    }
}
