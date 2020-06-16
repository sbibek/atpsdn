package org.decps.atpsdn.atp;

import org.decps.atpsdn.session.PacketInfo;
import org.onosproject.net.packet.PacketContext;

public class ContextTracker {
    private PacketContext senderToBroker;
    private PacketContext brokerToSender;

    public void update(PacketInfo info) {
        if (info.srcPort == 9092) {
            // means this is from broker
            brokerToSender = info.context;
        } else if (info.dstPort == 9092) {
            senderToBroker = info.context;
        }
    }

    public PacketContext getReverseContext(PacketInfo info) {
        if (info.srcPort == 9092) {
            return senderToBroker;
        } else if (info.dstPort == 9092) {
            return brokerToSender;
        }
        return null;
    }
}
