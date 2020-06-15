package org.decps.atpsdn.session;


import org.decps.atpsdn.Utils;

public class PayloadManager {
    public Integer totalMessagesInLastPacket = 0;
    public Integer totalMessages = 0;
    public byte[] previousRemainingBytes;

    private State state = new State();

    private void pushCurrentState() {
        state.totalMessagesInLastPacket = totalMessagesInLastPacket;
        state.totalMessages = totalMessages;
        state.previousRemainingBytes = Utils.createCopy(previousRemainingBytes);
    }

    public void rollbackState() {
        // rolls back to the saved state
        totalMessagesInLastPacket = state.totalMessagesInLastPacket;
        totalMessages = state.totalMessages;
        previousRemainingBytes = Utils.createCopy(state.previousRemainingBytes);
    }

    public void process(byte[] payload) {
        // save the current state
        pushCurrentState();

        if(previousRemainingBytes != null && previousRemainingBytes.length != 0) {
            payload = merge(payload);
        }

        PayloadWrapper wrapper = new PayloadWrapper();
        wrapper.process(false, payload);
       // wrapper.log();
        totalMessagesInLastPacket = wrapper.totalMessageCount;
        totalMessages += wrapper.totalMessageCount;
        if(wrapper.excessBytes != null && wrapper.excessBytes.length != 0) {
            // this means we have excess bytes from this
            previousRemainingBytes = wrapper.excessBytes;
        } else {
            previousRemainingBytes = null;
        }
        //System.out.println(String.format("data remaining for next %d", previousRemainingBytes == null?0:previousRemainingBytes.length));
    }

    private byte[] merge(byte[] payload) {
        int totalLength = previousRemainingBytes.length + payload.length;
        byte[] mergedPayload = new byte[totalLength];
        for(int i = 0; i<previousRemainingBytes.length;i++) {
            mergedPayload[i]  =  previousRemainingBytes[i];
        }

        for(int i = previousRemainingBytes.length; i< totalLength; i++) {
            mergedPayload[i] = payload[i - previousRemainingBytes.length];
        }

        return mergedPayload;
    }

    public void processWithReducedPacketPayload(PacketInfo packetInfo, Integer totalMessagesToKeep) {
        byte[] payload = packetInfo.getPayload();
        // this method expects that the rollback method was called if required before calling this method
        if(previousRemainingBytes != null && previousRemainingBytes.length != 0) {
            payload = merge(payload);
        }

        PayloadWrapper wrapper = new PayloadWrapper();
        byte[] reducedPayload = wrapper.getBytesRepresentingSomeMessages(payload, totalMessagesToKeep);
        // wrapper.log();
        totalMessagesInLastPacket =  totalMessagesToKeep;
        totalMessages += totalMessagesToKeep;
        // we assume no bytes remaining
        previousRemainingBytes = null;

        packetInfo.buildWithModifiedPayload(reducedPayload);
    }


    private class State {
        public Integer totalMessagesInLastPacket = 0;
        public Integer totalMessages = 0;
        public byte[] previousRemainingBytes;
    }
}
