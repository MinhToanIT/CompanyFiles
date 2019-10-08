package com.vcc.pool.core;

public interface PoolData {
    interface Configure {
        int PATTERN_ANY = -1;
        String PARTTERN_PREFIX_UPLOAD_FILE = "_puf_vo_";
        int UPLOAD_TYPE_IMAGE = 2;
        int UPLOAD_TYPE_VIDEO = 1;
        int UPLOAD_RETRY_LIMIT = 3;
        int ACTION_RETRY_LIMIT = 3;

        int DATA_MAX_IN_CACHE = 250;
        int DATA_COUNT_PER_GET = 10;
        int MIN_ITEM_CACHE = (int) (DATA_COUNT_PER_GET * 1.75);
        boolean TWENTY_NEWS_FIRST = true;
    }

    interface BackgroundConfig {
        int MAX_THREAD = 5;
        int SCHEDULE_TIME_BETWEEN_REQUEST = 2000;
        int SCHEDULE_REQUEST_TIME = 5000;
        int SCHEDULE_REMOVE_TIME = 7000;
        int SCHEDULE_UPLOAD_TIME = 5000;
        int SCHEDULE_ACTION_TIME = 5000;
        int GET_DATA_TIME_LIMIT = 1000;
    }

    interface Database {
        String TABLE_RANKING_NAME = "rank";
        String TABLE_ACTION_NAME = "action";
        String TABLE_UPLOAD_NAME = "upload";

        int MAX_CARD_LOCAL = 1000;
        float CHECK_REMOVE_PERCENT = 0.7f;
        long REMOVE_POINT_TIME = 7 * 24 * 60 * 60;
    }

    interface Network {
        int TYPE_MOBILE = 0;
        int TYPE_WIFI = 1;

        int DEFAULT_TIMEOUT = 20;
        int UPLOAD_TIMEOUT = 100;
    }

    enum TaskID {
        NONE(0),

        GET_DATA(1),
        ACTION_ADD(2),
        ACTION(3),
        RANKING(4),

        REMOTE_SHORT_TERM(10),
        REMOTE_LONG_TERM(11),

        LOCAL_INSERT_RANK(20),
        LOCAL_REMOVE_RANK(21),
        LOCAL_CACHE_UPDATE(22),

        UPLOAD_ADD(30),
        UPLOAD(31),
        SEND_REQUEST(32);

        private int id;

        TaskID(int id) {
            this.id = id;
        }

        public int getId() {
            return id;
        }
        }

    enum Mode {
        RANK_FORMULA,
        RANK_BASE_SCORE,
        RANK_TIME
    }

    enum TaskPriority {
        LOW, MEDIUM, HIGH, FORCE
    }
}
