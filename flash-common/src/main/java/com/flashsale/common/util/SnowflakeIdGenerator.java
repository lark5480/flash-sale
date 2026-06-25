package com.flashsale.common.util;

/**
 * 雪花算法 ID 生成器 — 替换数据库自增主键
 *
 * <p>结构：1bit符号位 + 41bit时间戳（ms级偏移） + 5bit数据中心 + 5bit机器 + 12bit序列号 = 64bit</p>
 * <ul>
 *   <li>支持 32 个数据中心 × 32 个节点，单节点 QPS 约 409.6 万</li>
 *   <li>自定义 Epoch：2024-01-01，可用约 69 年</li>
 * </ul>
 */
public class SnowflakeIdGenerator {

    private final long workerId;
    private final long datacenterId;
    private long sequence = 0L;
    private long lastTimestamp = -1L;

    private static final long WORKER_ID_BITS = 5L;
    private static final long DATACENTER_ID_BITS = 5L;
    private static final long SEQUENCE_BITS = 12L;

    private static final long MAX_WORKER_ID = ~(-1L << WORKER_ID_BITS);
    private static final long MAX_DATACENTER_ID = ~(-1L << DATACENTER_ID_BITS);
    private static final long SEQUENCE_MASK = ~(-1L << SEQUENCE_BITS);

    private static final long WORKER_ID_SHIFT = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    private static final long EPOCH = 1704067200000L; // 2024-01-01 00:00:00 UTC

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId > MAX_WORKER_ID || workerId < 0) {
            throw new IllegalArgumentException("workerId must be between 0 and " + MAX_WORKER_ID);
        }
        if (datacenterId > MAX_DATACENTER_ID || datacenterId < 0) {
            throw new IllegalArgumentException("datacenterId must be between 0 and " + MAX_DATACENTER_ID);
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /**
     * 生成下一个唯一 ID（线程安全）
     */
    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        if (timestamp < lastTimestamp) {
            long offset = lastTimestamp - timestamp;
            if (offset > 5000) {
                throw new RuntimeException("Clock moved backwards for more than 5 seconds");
            }
            while (timestamp < lastTimestamp) {
                timestamp = System.currentTimeMillis();
            }
        }

        if (lastTimestamp == timestamp) {
            sequence = (sequence + 1) & SEQUENCE_MASK;
            if (sequence == 0) {
                timestamp = tilNextMillis(lastTimestamp);
            }
        } else {
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        return ((timestamp - EPOCH) << TIMESTAMP_LEFT_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long tilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}