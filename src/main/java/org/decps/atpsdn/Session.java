package org.decps.atpsdn;

import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;
import org.onosproject.net.packet.PacketContext;

public class Session {
    private String sessionKey;
    private Integer sender;
    private Integer receiver;
    private Integer senderPort;
    private Integer receiverPort;

    private PacketContext s2r_context;
    private PacketContext r2s_context;

    private Integer s2r_seq, s2r_ack, r2s_seq, r2s_ack;

    // we will use dataCount per PA packet to decide if the tcp session
    // needs to be torn down, we will extend it to MLR later
    private Integer dataCount = 0;
    private Boolean initiateTeardown = false;
    private Boolean senderAcked = false;
    private Boolean receiverAcked = false;

    public void initSession(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort, String key){
        this.sender = sender;
        this.receiver = receiver;
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
        this.sessionKey = key;
    }

    public Integer getS2r_seq() {
        return s2r_seq;
    }

    public void setS2r_seq(Integer s2r_seq) {
        this.s2r_seq = s2r_seq;
    }

    public Integer getS2r_ack() {
        return s2r_ack;
    }

    public void setS2r_ack(Integer s2r_ack) {
        this.s2r_ack = s2r_ack;
    }

    public Integer getR2s_seq() {
        return r2s_seq;
    }

    public void setR2s_seq(Integer r2s_seq) {
        this.r2s_seq = r2s_seq;
    }

    public Integer getR2s_ack() {
        return r2s_ack;
    }

    public void setR2s_ack(Integer r2s_ack) {
        this.r2s_ack = r2s_ack;
    }

    public String getSessionKey() {
        return sessionKey;
    }

    public Integer getSender() {
        return sender;
    }

    public Integer getReceiver() {
        return receiver;
    }

    public Integer getSenderPort() {
        return senderPort;
    }

    public Integer getReceiverPort() {
        return receiverPort;
    }

    public Boolean getSenderAcked() {
        return senderAcked;
    }

    public void setSenderAcked(Boolean senderAcked) {
        this.senderAcked = senderAcked;
    }

    public Boolean getReceiverAcked() {
        return receiverAcked;
    }

    public void setReceiverAcked(Boolean receiverAcked) {
        this.receiverAcked = receiverAcked;
    }

    public void setS2r_context(PacketContext s2r_context) {
        this.s2r_context = s2r_context;
    }

    public void setR2s_context(PacketContext r2s_context) {
        this.r2s_context = r2s_context;
    }

    public PacketContext getS2r_context() {
        return s2r_context;
    }

    public PacketContext getR2s_context() {
        return r2s_context;
    }

    public Integer getDataCount() {
        return dataCount;
    }

    public void setDataCount(Integer dc) {
        this.dataCount = dc;
    }

    public Boolean getInitiateTeardown() {
        return initiateTeardown;
    }

    public void setInitiateTeardown(Boolean initiateTeardown) {
        this.initiateTeardown = initiateTeardown;
    }

    public Boolean isPacketDirectionDesiredOne(IPv4 ip, TCP tcp) {
        // check if the current ip and tcp is the part of the packet from sender to receiver
        return ip.getSourceAddress() == sender && ip.getDestinationAddress() == receiver && tcp.getSourcePort() == senderPort && tcp.getDestinationPort() == receiverPort;
    }
}
