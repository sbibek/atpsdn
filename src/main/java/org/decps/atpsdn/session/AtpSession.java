package org.decps.atpsdn.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    // this flag is set when we finally detect that the session is active data session
    public Boolean isAtpActiveSession = false;


    /**
     * We will queue the incoming packet for each session so that we can implement our own logic on
     * how we can handle the outgoing rate from this point on towards the broker
     *
     * Also we track how many message are represented by the queued packets so far
     */
    public Queue<PacketInfo> packetQueue = new ConcurrentLinkedQueue<>();
    public Integer totalMessagesQueued = 0;


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

        // now that we have processed the packet, we now add it to the queue and then
        // update the totalMessageQueued according to the total message that we have found
        packetQueue.add(info);
        totalMessagesQueued = payloadManager.totalMessages;

        //log.info(String.format("%d->%d %d", srcPort, dstPort, payloadManager.totalMessages));
    }

    public Boolean isThisExpected(PacketInfo packetInfo) {
        return (nextExpectedSequence == null || (nextExpectedSequence != null && packetInfo.seq.equals(nextExpectedSequence)));
    }

    public Boolean doWeHaveQueuedPackets() {
        return packetQueue.peek() != null;
    }

    public PacketInfo getQueuedPacket() {
        return packetQueue.poll();
    }

}
