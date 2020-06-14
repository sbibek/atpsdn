package org.decps.atpsdn.session;

import org.decps.atpsdn.PacketInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AtpSession {
    private final Logger log = LoggerFactory.getLogger(getClass());

    private PayloadManager payloadManager = new PayloadManager();


    /**
     * we need to skip all the retransmissions
     */
    Long nextExpectedSequence = null;

    // **** parameters defining this session *********
    public Integer srcAddr, dstAddr, srcPort, dstPort;
    public String key;
    // ***********************************************

    public PacketInfo packetInfo;

    // this flag is set when we finally detect that the session is active data session
    public Boolean isAtpActiveSession = false;

    public AtpSession(String key, Integer srcAddr, Integer dstAddr, Integer srcPort, Integer dstPort) {
        this.key = key;
        this.srcAddr = srcAddr;
        this.dstAddr = dstAddr;
        this.srcPort = srcPort;
        this.dstPort = dstPort;
    }

    public void push(PacketInfo info) {
        payloadManager.process(info.getPayload());
        nextExpectedSequence = info.seq + info.payloadLength;
        log.info(String.format("%d->%d %d", srcPort, dstPort, payloadManager.totalMessages));
    }

    public Boolean isThisExpected(PacketInfo packetInfo) {
        return (nextExpectedSequence == null) ? true : (nextExpectedSequence != null && nextExpectedSequence != packetInfo.seq);
    }

}
