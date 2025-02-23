/*
 * Copyright (C) 2019 The Turms Project
 * https://github.com/turms-im/turms
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.turms.server.common.cluster.service.idgen;


import im.turms.common.util.RandomUtil;
import jdk.internal.vm.annotation.Contended;
import lombok.extern.log4j.Log4j2;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The flake ID is designed for turms.
 * ID size: 64 bits.
 * <p>
 * 1 bit for the sign of ID.
 * The most significant bit is always 0 to represent a positive number.
 * Reference: https://stackoverflow.com/questions/8927761/why-is-negative-id-or-zero-considered-a-bad-practice
 * <p>
 * 41 bits for timestamp (69 years)
 * <p>
 * 4 bits for data center ID (16).
 * A data center usually represents a region in cloud.
 * Reserved for future.
 * <p>
 * 8 bits for member ID (256).
 * Note turms-gateway also works as a load balancer to route traffic to turms servers so the number
 * of turms servers is better more than or equals to the number of turms-gateway servers in practice.
 * In other words, the max number that can be represented by the bits for memberId should be better
 * more than the number of turms-gateway servers that you will deploy
 * <p>
 * 10 bits for sequenceNumber (1,024).
 * It can represent up to 1024*1000 sequence numbers per seconds.
 *
 * @author James Chen
 */
@Log4j2
public class SnowflakeIdGenerator {

    /**
     * 2020-10-13 00:00:00 in UTC
     */
    private static final long EPOCH = 1602547200000L;

    private static final int TIMESTAMP_BITS = 41;
    private static final int DATA_CENTER_ID_BITS = 4;
    private static final int MEMBER_ID_BITS = 8;
    private static final int SEQUENCE_NUMBER_BITS = 10;

    private static final long TIMESTAMP_LEFT_SHIFT = SEQUENCE_NUMBER_BITS + MEMBER_ID_BITS + DATA_CENTER_ID_BITS;
    private static final long DATA_CENTER_ID_SHIFT = SEQUENCE_NUMBER_BITS + MEMBER_ID_BITS;
    private static final long MEMBER_ID_SHIFT = SEQUENCE_NUMBER_BITS;

    private static final long SEQUENCE_NUMBER_MASK = (1 << SEQUENCE_NUMBER_BITS) - 1;

    // Used to ensure clock moves forward.
    private final AtomicLong lastTimestamp = new AtomicLong();

    // Because it's vulnerable if turms restarts after the clock goes backwards,
    // we randomize the sequenceNumber on init to decrease chance of collision
    private final AtomicLong sequenceNumber = new AtomicLong(RandomUtil.nextPositiveInt());

    @Contended("nodeInfo")
    private long dataCenterId;

    @Contended("nodeInfo")
    private long memberId;

    public SnowflakeIdGenerator(int dataCenterId, int memberId) {
        updateNodeInfo(dataCenterId, memberId);
    }

    public void updateNodeInfo(int dataCenterId, int memberId) {
        if (dataCenterId >= (1 << DATA_CENTER_ID_BITS)) {
            String reason = String.format("Illegal dataCenterId %d. The dataCenterId must be in the range [0, %d)", dataCenterId,
                    1 << DATA_CENTER_ID_BITS);
            throw new IllegalArgumentException(reason);
        }
        if (memberId >= (1 << MEMBER_ID_BITS)) {
            String reason = String.format("Illegal memberId %d. The memberId must be in the range [0, %d)", memberId, 1 << MEMBER_ID_BITS);
            throw new IllegalArgumentException(reason);
        }
        this.dataCenterId = dataCenterId;
        this.memberId = memberId;
    }

    public long nextIncreasingId() {
        // prepare each part of ID
        long sequenceId = sequenceNumber.incrementAndGet() & SEQUENCE_NUMBER_MASK;
        long timestamp = this.lastTimestamp.updateAndGet(lastTs -> {
            // Don't let timestamp go backwards at least while this JVM is running.
            long nonBackwardsTimestamp = Math.max(lastTs, System.currentTimeMillis());
            if (sequenceId == 0) {
                // Always force the clock to increment whenever sequence number is 0,
                // in case we have a long time-slip backwards
                nonBackwardsTimestamp++;
            }
            return nonBackwardsTimestamp;
        }) - EPOCH;

        // Get ID
        return (timestamp << TIMESTAMP_LEFT_SHIFT)
                | (dataCenterId << DATA_CENTER_ID_SHIFT)
                | (memberId << MEMBER_ID_SHIFT)
                | sequenceId;
    }

    public long nextRandomId() {
        // prepare each part of ID
        long sequenceId = sequenceNumber.incrementAndGet() & SEQUENCE_NUMBER_MASK;
        long timestamp = this.lastTimestamp.updateAndGet(now -> {
            // Don't let timestamp go backwards at least while this JVM is running.
            long nonBackwardsTimestamp = Math.max(now, System.currentTimeMillis());
            if (sequenceId == 0) {
                // Always force the clock to increment whenever sequence number is 0,
                // in case we have a long time-slip backwards
                nonBackwardsTimestamp++;
            }
            return nonBackwardsTimestamp;
        }) - EPOCH;

        // Get ID
        return (sequenceId << (TIMESTAMP_BITS + DATA_CENTER_ID_BITS + MEMBER_ID_BITS))
                | (timestamp << (DATA_CENTER_ID_BITS + MEMBER_ID_BITS))
                | (dataCenterId << MEMBER_ID_BITS)
                | memberId;
    }

}