package org.decps.atpsdn;

import org.onlab.packet.IPv4;
import org.onlab.packet.TCP;

public class Session {
    private Integer sender;
    private Integer receiver;
    private Integer senderPort;
    private Integer receiverPort;

    // we will use dataCount per PA packet to decide if the tcp session
    // needs to be torn down, we will extend it to MLR later
    private Integer dataCount = 0;

    public void initSession(Integer sender, Integer receiver, Integer senderPort, Integer receiverPort){
        this.sender = sender;
        this.receiver = receiver;
        this.senderPort = senderPort;
        this.receiverPort = receiverPort;
    }

    public Integer getDataCount() {
        return dataCount;
    }

    public void setDataCount(Integer dc) {
        this.dataCount = dc;
    }

    public Boolean isPacketDirectionDesiredOne(IPv4 ip, TCP tcp) {
        // check if the current ip and tcp is the part of the packet from sender to receiver
        return ip.getSourceAddress() == sender && ip.getDestinationAddress() == receiver && tcp.getSourcePort() == senderPort && tcp.getDestinationPort() == receiverPort;
    }
}
